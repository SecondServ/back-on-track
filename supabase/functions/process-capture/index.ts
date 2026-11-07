// 入口：客户端上传完音频后调这个函数。
//   音频 → ASR → LLM（纠错+归类+提取）→ 落库 → 排提醒
//
// 刻意不用 @supabase/supabase-js：Edge Function 跑的是 Deno，
// 加载 npm 包要走兼容层，冷启动开销大（曾导致 150s 墙钟超时被平台掐掉）。
// 我们只需要几个简单的 REST 调用，原生 fetch 足够。
//
// 全程带调用者的 JWT，读写一律走 RLS，不用 service role。

import { analyze } from "../_shared/analysis.ts";
import { transcribe, type AsrProvider } from "../_shared/asr.ts";
import { asrHint, loadProfile, routeOf } from "../_shared/profile.ts";
import { planReminders } from "../_shared/reminders.ts";
import { dateLabel, formatMeal, localDateIso } from "../_shared/meal.ts";
import type { MealEntry } from "../_shared/meal.ts";

type MealSlots = MealEntry[];

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const AUDIO_BUCKET = "captures";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);

  const t0 = Date.now();
  const mark = (s: string) => console.log(`[${Date.now() - t0}ms] ${s}`);

  // 带调用者身份的通用请求头
  const H = { apikey: ANON, Authorization: auth };
  const JH = { ...H, "Content-Type": "application/json" };

  // ── 鉴权 ────────────────────────────────────────────────────────────────
  const ures = await fetch(`${SUPA_URL}/auth/v1/user`, { headers: H });
  if (!ures.ok) return json({ error: "invalid_token" }, 401);
  const user = await ures.json();
  mark("鉴权通过");

  const { capture_id } = await req.json().catch(() => ({}));
  if (!capture_id) return json({ error: "missing_capture_id" }, 400);

  const setCapture = (patch: Record<string, unknown>) =>
    fetch(`${SUPA_URL}/rest/v1/captures?id=eq.${capture_id}`, {
      method: "PATCH", headers: JH, body: JSON.stringify(patch),
    });

  const cres = await fetch(
    `${SUPA_URL}/rest/v1/captures?id=eq.${capture_id}&select=*`, { headers: H });
  const [capture] = await cres.json();
  if (!capture) return json({ error: "capture_not_found" }, 404);

  const tz = capture.client_tz ?? "America/Los_Angeles";
  const profile = await loadProfile(SUPA_URL, H);

  try {
    await setCapture({ asr_status: "transcribing" });

    // ── 1. 转录 ───────────────────────────────────────────────────────────
    let transcript: string | null = capture.final_text;

    if (!transcript && capture.audio_path) {
      mark("开始下载音频");
      const dl = await fetch(
        `${SUPA_URL}/storage/v1/object/${AUDIO_BUCKET}/${capture.audio_path}`,
        { headers: H },
      );
      if (!dl.ok) {
        throw new Error(`音频下载失败 ${dl.status}: ${await dl.text()}`);
      }
      const audio = await dl.blob();
      mark(`音频下载完成 ${audio.size}B`);

      transcript = await transcribe(
        audio,
        (Deno.env.get("ASR_PROVIDER") ?? "openai") as AsrProvider,
        Deno.env.get("ASR_API_KEY")!,
        asrHint(profile),
        routeOf(profile),
      );
      mark(`转录完成 ${transcript.length} 字`);
    }

    if (!transcript?.trim()) throw new Error("empty_transcript");

    // 转录立刻落库 —— 它是全链路最慢最贵的一步，
    // 绝不能因为后面的分析失败就丢掉（PRD 3.2：原始转录永不删除）。
    await setCapture({ final_text: transcript, asr_status: "done" });

    // ── 2. 一次调用完成纠错 + 归类 + 提取 ─────────────────────────────────
    mark("开始分析");
    // 用户在客户端手动指定过分类时（滑到「待办」），AI 不得改写
    const forcedKind = capture.kind_is_manual ? capture.kind : undefined;
    const a = await analyze({
      transcript, now: new Date().toISOString(), tz, forcedKind,
      profile: profile?.body,
      route: routeOf(profile),
    });
    mark("分析完成");

    // 饮食：标题用那一天的日期，正文换成按餐次整理的清单。
    // 原话不丢 —— 转写原文一直在 final_text 里，详情页折叠着。
    //
    // 一条语音可能同时说了今天和昨天：第一天写回本条，其余每天**各建一条**，
    // 都挂同一段录音（说的时候就是一次，回放时两条都能听到原话）。
    const extraDays: { date: string | null; slots: MealSlots }[] = [];
    if (a.kind === "meal" && a.meal?.days?.length) {
      const [first, ...rest] = a.meal.days.filter((d) => d.slots?.length);
      if (first) {
        const iso = first.date ?? localDateIso(tz);
        a.summary = dateLabel(iso);
        a.corrected_text = formatMeal(first);
        a.logged_on = iso;
        extraDays.push(...rest);
      }
    }

    await setCapture({
      corrected_text: a.corrected_text,
      summary: a.summary,
      // 模型解析出「昨天」之类才有值；没有就留空 = 录音当天
      ...(a.logged_on ? { logged_on: a.logged_on } : {}),
      language: a.language,
      ...(forcedKind ? {} : { kind: a.kind, kind_confidence: a.kind_confidence }),
    });

    // 日记顺手记下心情。他亲手改过的（mood_is_manual）不覆盖 ——
    // 过滤条件写在 URL 上，改过的那条压根匹配不到
    if (a.kind === "diary" && a.mood) {
      await fetch(
        `${SUPA_URL}/rest/v1/captures?id=eq.${capture_id}&mood_is_manual=is.false`,
        { method: "PATCH", headers: JH, body: JSON.stringify({ mood: a.mood }) },
      );
    }

    // 多出来的那几天：各建一条独立的饮食笔记，挂同一段录音
    for (const day of extraDays) {
      const iso = day.date ?? localDateIso(tz);
      const r = await fetch(`${SUPA_URL}/rest/v1/captures`, {
        method: "POST", headers: { ...JH, Prefer: "return=representation" },
        body: JSON.stringify([{
          user_id: user.id,
          kind: "meal",
          // 已经明确是饮食了，别让后续流程再去猜
          kind_is_manual: true,
          summary: dateLabel(iso),
          corrected_text: formatMeal(day),
          final_text: transcript,
          logged_on: iso,
          audio_path: capture.audio_path,
          duration_ms: capture.duration_ms,
          asr_status: "done",
          source: capture.source,
          client_tz: tz,
          // 跟原条同一时刻：这几条本来就是一次说出来的
          created_at: capture.created_at,
        }]),
      });
      if (!r.ok) {
        console.error(`拆分饮食失败 ${r.status}: ${await r.text()}`);
        continue;
      }
      const [row] = await r.json();
      // 下游 meals 表也得跟上，否则这一天在按天汇总时是缺的
      await fetch(`${SUPA_URL}/rest/v1/meals`, {
        method: "POST", headers: JH,
        body: JSON.stringify([{
          user_id: user.id,
          capture_id: row.id,
          items: day.slots,
          kcal_est: 0,
          logged_on: iso,
        }]),
      });
    }

    // ── 3. 落到各模块 ─────────────────────────────────────────────────────
    const uid = user.id;
    const base = { user_id: uid, capture_id };

    const insert = async (table: string, rows: unknown[], ret = false) => {
      if (!rows.length) return [];
      const r = await fetch(`${SUPA_URL}/rest/v1/${table}`, {
        method: "POST",
        headers: ret ? { ...JH, Prefer: "return=representation" } : JH,
        body: JSON.stringify(rows),
      });
      if (!r.ok) throw new Error(`写入 ${table} 失败 ${r.status}: ${await r.text()}`);
      return ret ? await r.json() : [];
    };

    // 兜底：归类是待办，却一条待办都没提取出来
    // （比如用户右滑强制成待办，但话里没有明确的动作）。
    // 不兜的话这条语音在「记录」里被过滤掉、「待办」里又没有，哪儿都看不见。
    if (a.kind === "task" && a.tasks.length === 0) {
      a.tasks.push({
        title: a.summary || transcript.slice(0, 30),
        due_at: null,
        due_is_fuzzy: false,
        priority: "medium",
        needs_reminder: false,
        // 兜底建出来的待办一律是一次性的：话里既然没提取出动作，
        // 更不可能提取出「每周几」这种循环规则。
        // 这个字段之前漏了，下游靠 ?. 兜住了没炸，但 deno check 一直过不去 ——
        // 类型检查是部署前唯一的网，让它长期报红等于没有网。
        recurrence: { kind: "none" as const, interval: 1, weekdays: [] },
      });
    }

    // 只有「用户明确指定是待办」的才建待办：右滑指定的，或者整段话本身
    // 就是在交代一件事（kind=task）。
    //
    // 从日记 / 灵感 / 备忘里「顺带」识别出来的一律丢掉。不是技术上做不到 ——
    // 试过「先挂起等用户确认」那套，但一条待办挂在一条日记底下，
    // 笔记和待办的关系会变得没法追溯，复杂度远大于收益。
    // 以后若有「计划」这类专门的 category，再单独给它开口子。
    if (a.kind === "task" && a.tasks.length) {
      const rows = await insert("tasks", a.tasks.map((t) => ({
        ...base,
        title: t.title,
        due_at: t.due_at,
        due_is_fuzzy: t.due_is_fuzzy,
        priority: t.priority,
        wants_reminder: t.needs_reminder,
        recur_kind: t.recurrence && t.recurrence.kind !== "none"
          ? t.recurrence.kind : null,
        recur_interval: t.recurrence?.interval ?? 1,
        recur_weekdays: t.recurrence?.weekdays?.length ? t.recurrence.weekdays : null,
      })), true) as { id: string }[];

      // ── 4. 排递进式提醒 ─────────────────────────────────────────────────
      const reminders = rows.flatMap((row, i) =>
        a.tasks[i].needs_reminder
          ? planReminders(a.tasks[i].due_at, tz)
            .map((r) => ({ user_id: uid, task_id: row.id, ...r }))
          : []
      );
      await insert("reminders", reminders);
      mark(`待办 ${rows.length} 条，提醒 ${reminders.length} 条`);
    }

    if (a.idea) {
      await insert("ideas", [{ ...base, raw_text: transcript, organized: a.idea }]);
    }
    if (a.content) {
      await insert("content_pieces", [{
        ...base,
        angle: a.content.angle,
        clean_text: a.content.clean_text,
        titles: a.content.titles,
        hooks: a.content.hooks,
      }]);
    }
    if (a.note) {
      await insert("notes", [{ ...base, body: a.note.body, tags: a.note.tags }]);
    }
    // meals 行只对应**这一条**笔记的那一天；多出来的天在上面拆分时
    // 已经连同它们自己的 capture 一起写好了
    const firstDay = a.meal?.days?.find((d) => d.slots?.length);
    if (firstDay) {
      await insert("meals", [{
        ...base,
        items: firstDay.slots,
        kcal_est: Math.round(a.meal!.kcal_est || 0),   // 列是整数，模型可能给小数
        logged_on: firstDay.date ?? localDateIso(tz),  // 用户当地日期，不是服务器 UTC
      }]);
    }

    mark("全部完成");
    return json({ ok: true, analysis: a });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("失败:", msg);
    // 转录已经拿到的话，状态保持 done —— 失败的是分析，不是转录。
    // 这样重试时能跳过转录，直接重跑分析。
    const r = await fetch(
      `${SUPA_URL}/rest/v1/captures?id=eq.${capture_id}&select=final_text`,
      { headers: H });
    const [cur] = await r.json().catch(() => [null]);
    await setCapture({
      asr_error: msg,
      ...(cur?.final_text ? {} : { asr_status: "failed" }),
    });
    return json({ error: msg }, 500);
  }
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

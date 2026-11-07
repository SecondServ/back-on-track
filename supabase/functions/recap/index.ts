// 月度回顾：饮食 / 灵感·选题·备忘 / 待办 三个板块。
//
// 分工是刻意的：**能算的都用代码算，模型只负责写字**。
// 完成率、完成条数、月份边界这些交给模型迟早会错一次，而且错得不声不响；
// 反过来「这个月你在琢磨什么」只有模型写得出来。
//
// 结果缓存在 recaps 表里（见 0020 迁移）：这个页面是拿来每天看一眼的，
// 每次都重算等于每天为同一个月付一次钱。
import { loadProfile, routeOf } from "../_shared/profile.ts";
import { callLLM, llmProvider, supportsWeb } from "../_shared/llm.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const PROVIDER = llmProvider();

/** 日记永远不进回顾 —— 它是加锁的，回顾页可没有那道锁 */
const IDEA_KINDS = ["idea", "content", "memo", "note"];

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);

  const H = { apikey: ANON, Authorization: auth };
  const JH = { ...H, "Content-Type": "application/json" };

  const body = await req.json().catch(() => ({}));
  const { month, section, offset_minutes, force } = body;
  if (!month || !section) return json({ error: "missing_params" }, 400);
  if (!["meal", "ideas", "tasks"].includes(section)) {
    return json({ error: "bad_section" }, 400);
  }

  const ures = await fetch(`${SUPA_URL}/auth/v1/user`, { headers: H });
  if (!ures.ok) return json({ error: "invalid_token" }, 401);
  const user = await ures.json();

  // 已经算过就直接给，除非明确要求重算
  if (!force) {
    const hit = await fetch(
      `${SUPA_URL}/rest/v1/recaps?month=eq.${month}&section=eq.${section}` +
        `&select=data,generated_at`,
      { headers: H },
    );
    if (hit.ok) {
      const rows = await hit.json();
      if (rows[0]) {
        return json({ ok: true, cached: true, ...rows[0].data, generated_at: rows[0].generated_at });
      }
    }
  }

  // 月份边界按用户本地时区切 —— 用 UTC 切的话，月初月末那几条会跑到隔壁月去
  const off = Number(offset_minutes ?? 0);
  const [y, m] = month.split("-").map(Number);
  const start = new Date(Date.UTC(y, m - 1, 1) - off * 60_000).toISOString();
  const end = new Date(Date.UTC(y, m, 1) - off * 60_000).toISOString();

  try {
    // 用户在 App 里选的线路（默认 / 中国）。回顾这几段全是模型活儿，
    // 不跟着走的话他在国内切了线路、回顾页还是在打 OpenAI
    const route = routeOf(await loadProfile(SUPA_URL, H));
    const data = section === "tasks"
      ? await tasksRecap(H, start, end, off, route)
      : await notesRecap(H, start, end, section, off, route);

    await fetch(`${SUPA_URL}/rest/v1/recaps?on_conflict=user_id,month,section`, {
      method: "POST",
      headers: { ...JH, Prefer: "resolution=merge-duplicates" },
      body: JSON.stringify([{
        user_id: user.id, month, section, data, generated_at: new Date().toISOString(),
      }]),
    });

    return json({ ok: true, cached: false, ...data });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error(`recap ${section} ${month} 失败:`, msg);
    return json({ error: msg }, 500);
  }
});

// ── 待办：数字自己算，模型只写感受 ────────────────────────────────────────

/** 某个 UTC 时间戳落在用户本地的哪一号 */
function localDay(iso: string, off: number): number {
  return new Date(new Date(iso).getTime() + off * 60_000).getUTCDate();
}

/** 按天汇总成 [{d, n}]，没有记录的那天补 0 —— 柱状图缺天会看不出节奏 */
function byDay(dates: string[], off: number, start: string, end: string) {
  const days = Math.round(
    (new Date(end).getTime() - new Date(start).getTime()) / 86_400_000);
  const counts = new Array(days).fill(0);
  const first = localDay(start, off);
  for (const iso of dates) {
    const idx = localDay(iso, off) - first;
    if (idx >= 0 && idx < days) counts[idx]++;
  }
  return counts.map((n, i) => ({ d: first + i, n }));
}

async function tasksRecap(
  H: Record<string, string>, start: string, end: string, off: number,
  route: string,
) {
  const q = (f: string) =>
    fetch(`${SUPA_URL}/rest/v1/tasks?${f}&deleted_at=is.null&select=title,status,completed_at,created_at,due_at,priority&limit=500`,
      { headers: H }).then((r) => r.json());

  const done = await q(`status=eq.done&completed_at=gte.${start}&completed_at=lt.${end}`);
  const created = await q(`created_at=gte.${start}&created_at=lt.${end}`);
  // 未完成的要看**全部**，不只这个月 —— 拖得最久的那几条恰恰是几个月前建的
  const open = await q(`status=neq.done`);
  // 完成率的分母用「这个月建的」而不是「所有未完成的」——
  // 否则历史积压会把每个月的数字都压到很难看，看一眼就不想看了
  //
  // 分子也必须是「这个月建的里面完成了几件」。以前分子用的是「这个月完成的全部」，
  // 把上个月建、这个月才做完的也算进来，分子分母不是一拨人，10 月算出了 217%。
  const createdDone = (created as { status: string }[]).filter((t) => t.status === "done").length;
  const rate = created.length ? Math.round((createdDone / created.length) * 100) : 0;

  const now = Date.now();
  const dayMs = 86_400_000;

  // 拖了多久：全部未完成的按「建了多少天」排，最久的几条揪出来
  const stale = (open as { title: string; created_at: string }[])
    .map((t) => ({
      title: t.title,
      days: Math.floor((now - new Date(t.created_at).getTime()) / dayMs),
    }))
    .filter((t) => t.days >= 3)
    .sort((a, b) => b.days - a.days)
    .slice(0, 6);

  // 完成得快的：从建到完成花了多少小时，取最快的三条。
  // 不足 5 分钟的不算 —— 那是「做完了顺手记一笔」，不是「做得快」
  const swift = (done as { title: string; created_at: string; completed_at: string }[])
    .map((t) => ({
      title: t.title,
      hours: (new Date(t.completed_at).getTime() - new Date(t.created_at).getTime()) / 3_600_000,
    }))
    .filter((t) => t.hours >= 0.08)
    .sort((a, b) => a.hours - b.hours)
    .slice(0, 3)
    .map((t) => ({ title: t.title, hours: Math.round(t.hours * 10) / 10 }));

  // 日历要的是「这一天发生了什么」：完成了哪几件、哪几件到期。
  // 全在代码里按本地日期归位 —— 日期算错是最容易发生、又最难被发现的那类 bug
  const cal: Record<number, { done: string[]; due: string[] }> = {};
  const cell = (d: number) => (cal[d] ??= { done: [], due: [] });
  for (const t of done as { title: string; completed_at: string }[]) {
    cell(localDay(t.completed_at, off)).done.push(t.title);
  }
  for (const t of open as { title: string; due_at: string | null }[]) {
    if (!t.due_at) continue;
    if (t.due_at >= start && t.due_at < end) cell(localDay(t.due_at, off)).due.push(t.title);
  }
  const calendar = Object.entries(cal)
    .map(([d, v]) => ({ d: Number(d), done: v.done, due: v.due }))
    .sort((a, b) => a.d - b.d);

  const titles = done.map((t: { title: string }) => `- ${t.title}`).join("\n") || "（这个月没有完成的待办）";
  const staleText = stale.map((t) => `- ${t.title}（已经 ${t.days} 天）`).join("\n") || "（没有拖着的）";
  const prose = await ask(
    `你在帮一个人回顾他这个月完成的事。目的是**给他情绪价值** —— 让他看见自己确实往前走了。

- 说人话，别喊口号，别用「赋能」「闭环」这种词
- **notable 只给 3 条**，挑最有分量的。给多了就摊薄了，看的人反而没感觉
- **praise**：对他完成得快的那几件说一句，具体、别肉麻
- **nudge**：一句温馨提醒。有拖了好几天的就点名催一句（「X 已经挂了 N 天，找十分钟开个头」）；
  要是没什么积压、完成得不错，就顺口鼓励一句。像朋友随口说的，
  **不要说教、不要愧疚绑架、不要喊口号**
- 如果这个月完成得少，就诚实地说，但把注意力放在「做成的那几件」上，不要说教
- 全程用「你」称呼他`,
    `这个月他完成了 ${done.length} 件，新建了 ${created.length} 件。\n\n` +
      `完成的是：\n${titles}\n\n还拖着没做的：\n${staleText}`,
    {
      type: "object", additionalProperties: false,
      required: ["summary", "notable", "praise", "nudge"],
      properties: {
        summary: { type: "string", description: "两三句话的总体回顾" },
        notable: {
          type: "array", description: "**恰好 3 条**，最有分量的，每条一句话",
          items: { type: "string" },
        },
        praise: { type: "string", description: "夸他完成得快的那几件；没有就留空" },
        nudge: { type: "string", description: "提醒还拖着的那几件；没有就留空" },
      },
    },
    false, route,
  );

  // 画图用的数字全在这儿算好 —— 让模型报数字迟早会错一次，而且错得不声不响
  const priorities = { high: 0, medium: 0, low: 0 } as Record<string, number>;
  for (const t of done as { priority?: string }[]) {
    const k = t.priority ?? "medium";
    if (k in priorities) priorities[k]++;
  }
  return {
    done: done.length,
    created: created.length,
    created_done: createdDone,
    rate,
    open_count: open.length,
    calendar,
    stale,
    swift,
    by_day: byDay(done.map((t: { completed_at: string }) => t.completed_at), off, start, end),
    by_priority: priorities,
    ...prose,
  };
}

// ── 饮食 / 灵感 ──────────────────────────────────────────────────────────

async function notesRecap(
  H: Record<string, string>, start: string, end: string, section: string, off: number,
  route: string,
) {
  const kinds = section === "meal" ? ["meal"] : IDEA_KINDS;
  const inList = kinds.map((k) => `"${k}"`).join(",");
  const r = await fetch(
    `${SUPA_URL}/rest/v1/captures?kind=in.(${inList})&deleted_at=is.null` +
      `&created_at=gte.${start}&created_at=lt.${end}` +
      `&select=kind,summary,corrected_text,created_at,logged_on&order=created_at&limit=300`,
    { headers: H },
  );
  if (!r.ok) throw new Error(`读取笔记失败 ${r.status}`);
  const rows = await r.json() as {
    kind: string; summary: string | null; corrected_text: string | null;
    created_at: string; logged_on: string | null;
  }[];

  /**
   * 这条该算在哪一天：**补记的按它说的那天算**，没补记就按录音时间。
   * logged_on 是纯日期（没有时分），换算时取当地中午 —— 取零点的话
   * 时区一偏就会掉到前一天去。
   */
  const dayOf = (x: { created_at: string; logged_on: string | null }) => {
    if (!x.logged_on) return x.created_at;
    const [yy, mm, dd] = x.logged_on.split("-").map(Number);
    return new Date(Date.UTC(yy, mm - 1, dd, 12) - off * 60_000).toISOString();
  };

  if (!rows.length) return section === "meal"
    ? { count: 0, foods: [], categories: [], by_day: [], summary: "这个月还没有饮食记录。", advice: "" }
    : { count: 0, by_kind: {}, by_day: [], summary: "这个月还没有这几类笔记。", themes: [], questions: "", next: [] };

  const days = byDay(rows.map(dayOf), off, start, end);

  // 喂给模型的日期也用归属日期 —— 逐日卡路里得落在对的那天
  // 资讯页还要参考他这个月**问过什么** —— 提问最直接地暴露当下在关心什么，
  // 比笔记还快一步：很多念头是先问出来的，还没来得及记
  let asked = "";
  if (section !== "meal") {
    const qr = await fetch(
      `${SUPA_URL}/rest/v1/qa_turns?role=eq.user` +
        `&created_at=gte.${start}&created_at=lt.${end}` +
        `&select=text,created_at&order=created_at&limit=100`,
      { headers: H },
    );
    if (qr.ok) {
      const qs = await qr.json() as { text: string }[];
      if (qs.length) {
        asked = "\n\n【这个月他问过的问题】\n" +
          qs.map((q) => `- ${q.text.slice(0, 200)}`).join("\n");
      }
    }
  }

  const corpus = rows.map((x) =>
    `【${x.kind}｜${(x.logged_on ?? x.created_at).slice(0, 10)}】${x.corrected_text ?? x.summary ?? ""}`,
  ).join("\n") + asked;

  if (section === "meal") {
    const prose = await ask(
      `你在帮一个人回顾他这个月吃了什么。

- **foods**：**按品类合并**之后再计数，按次数从多到少，最多 12 个。
  合并的尺度是「同一类吃食」，不是「同一道菜」：
    · 三合一拌面 / 豚骨拉面 / 日本拉面 → 都算「拉面」
    · Double Cheeseburger / Chicken Burger / Fish Burger → 都算「汉堡」
    · 饺子 / 煎饺 / 水饺 → 都算「饺子」
    · 烤鸡胸 / 炸鸡 **不合并** —— 做法差别太大，合起来就看不出问题了
  名字用中文品类名（「拉面」「汉堡」「饺子」），不要带具体店名或口味。
  宁可粗一点也不要碎 —— 这是拿去画面积图的，十几个「1 次」的小方块等于没信息
- **categories**：按**食物本身**归成 4-6 个大类（面食 / 肉类 / 饮料 / 蔬果 / 甜食…），各自次数。
  这是拿去画饼图的，类别之间不要重叠
- **styles**：换一个维度 —— 按**在哪吃 + 什么菜系**分。只能用下面这七个桶，**每餐只进一个**：
    在家吃 / 快餐 / 美式 / 西式 / 中餐 / 日韩 / 东南亚
  规则：自己做的、吃剩菜的一律进「在家吃」；外食再按菜系分。
  拿不准时选更具体的那个（越南粉 → 东南亚，不是中餐；In-N-Out → 快餐，不是美式）。
  没出现过的桶就不要返回
- **restaurants**：他常去的**餐厅/店名**排名，按出现次数从多到少，最多 10 个。
  只收真实店名（Shake Shack、In-N-Out、Texas Roadhouse、星巴克、三号拉面…），
  「家里」「公司」这种不算。一次都没明确提到店名就返回空数组
- **calories**：**逐日**估算摄入的总卡路里，只给**有记录的那几天**（没记录的日子不要编）。
  d 是几号，kcal 是那天的估算总热量。
  估算依据是他写下的食物、分量和做法；信息不全就按常见份量给一个合理估计 ——
  这是帮他看趋势的粗估，不必精确到个位，但**不要系统性地往低了估**
- **summary**：**最多三句话**说清饮食面貌。这页以图为主，文字只是补充，别写成小作文
- **advice**：一到两条**具体可执行**的建议，基于他实际吃的东西来提，不要泛泛的「多吃蔬菜」
- **health**：给这个月的饮食打一个 0-100 的健康指数，外加一句话说明为什么。
  打分依据是他**实际吃的东西**：蔬果occurrence、外食/油炸频率、蛋白质来源是否单一、
  正餐是否规律。评分要诚实但不刻薄 —— 60 分左右是「普通人的正常水平」，
  不要动辄打 20 分，也不要为了让他高兴就给 90。note 一句话，别说教
- 不要评判，不要说教，他记录饮食是为了看清自己，不是为了被批评`,
      corpus,
      {
        type: "object", additionalProperties: false,
        required: ["foods", "categories", "styles", "restaurants", "calories",
                   "summary", "advice", "health"],
        properties: {
          foods: {
            type: "array",
            items: {
              type: "object", additionalProperties: false,
              required: ["name", "count"],
              properties: { name: { type: "string" }, count: { type: "integer" } },
            },
          },
          categories: {
            type: "array",
            description: "按食物本身分的 4-6 个大类，给每类次数",
            items: {
              type: "object", additionalProperties: false,
              required: ["name", "count"],
              properties: { name: { type: "string" }, count: { type: "integer" } },
            },
          },
          styles: {
            type: "array",
            description: "按做法/餐食类型分的 4-6 类（快餐、油炸、中餐、家里做…），给每类次数",
            items: {
              type: "object", additionalProperties: false,
              required: ["name", "count"],
              properties: { name: { type: "string" }, count: { type: "integer" } },
            },
          },
          restaurants: {
            type: "array",
            description: "常去的餐厅/店名排名，最多 10 个",
            items: {
              type: "object", additionalProperties: false,
              required: ["name", "count"],
              properties: { name: { type: "string" }, count: { type: "integer" } },
            },
          },
          calories: {
            type: "array",
            description: "逐日估算的总热量，只给有记录的那几天",
            items: {
              type: "object", additionalProperties: false,
              required: ["d", "kcal"],
              properties: { d: { type: "integer" }, kcal: { type: "integer" } },
            },
          },
          summary: { type: "string" },
          advice: { type: "string" },
          health: {
            type: "object", additionalProperties: false,
            required: ["score", "note"],
            properties: {
              score: { type: "integer", description: "0-100" },
              note: { type: "string", description: "一句话说明这个分数从哪来" },
            },
          },
        },
      },
      false, route,
    );
    // 日均只除以**有记录的天数**：没记录的那天不是「吃了 0 卡」，是「不知道」。
    // 除以整月天数会得出一个没有意义的低数字，看着还挺好看 —— 那种数字最误导人
    const cals = (prose.calories ?? []) as { d: number; kcal: number }[];
    const logged = cals.filter((c) => c.kcal > 0);
    const avgKcal = logged.length
      ? Math.round(logged.reduce((a, c) => a + c.kcal, 0) / logged.length)
      : 0;
    return {
      count: rows.length, by_day: days, avg_kcal: avgKcal,
      kcal_days: logged.length, ...prose,
    };
  }

  const prose = await ask(
    `你在给一个人做一份**个人资讯简报**。输入是他这个月记下的灵感、选题和随笔 ——
它们代表他最近在想什么、在做什么方向。
输入里还会附上**他这个月问过的问题** —— 那些问题往往比笔记更早暴露他在关心什么，
选资讯时要一并参考。

你的任务不是总结他说过的话，而是**基于这些方向去联网找他会感兴趣的新东西**。

- **summary**：**最多两句话** —— 他最近的关注点落在哪儿。别复述笔记内容
- **themes**：把散落的笔记归并成几条线索（最多 4 条）。
  title 是线索名字，note 一句话说清它由哪些记录汇成 —— 这是解释下面的资讯为什么选它
- **feed**：**这一份的主体**，5-8 条资讯。**必须先联网搜**，只给**近期**的东西：
    · 跟他方向直接相关的行业新闻、产品发布、研究报告
    · 他所在领域里 good to know 的技术进展（哪怕不是他正在做的，但知道了有用）
    · 有人已经做过类似的事 —— 做成了什么样、踩了什么坑
  每条给：title（标题）、source（出处，如 TechCrunch / arXiv / 某公司博客）、
  url（真实链接）、why（**一句话**说清这条跟他哪个方向有关、为什么值得看）。
  **why 里不要放链接、不要写引用标记** —— 链接只放在 url 字段里，界面会自己做成可点的
  **链接必须是搜到的真实链接，不许编**。搜不到足够的就少给几条
- **questions**：他抛出过、还悬着值得继续挖的问题。没有就留空字符串
- 用「你」称呼他，别吹捧`,
    corpus,
    {
      type: "object", additionalProperties: false,
      required: ["summary", "themes", "feed", "questions"],
      properties: {
        summary: { type: "string" },
        themes: {
          type: "array",
          items: {
            type: "object", additionalProperties: false,
            required: ["title", "note"],
            properties: { title: { type: "string" }, note: { type: "string" } },
          },
        },
        feed: {
          type: "array",
          description: "5-8 条联网搜来的资讯",
          items: {
            type: "object", additionalProperties: false,
            required: ["title", "source", "url", "why"],
            properties: {
              title: { type: "string" },
              source: { type: "string" },
              url: { type: "string" },
              why: { type: "string" },
            },
          },
        },
        questions: { type: "string" },
      },
    },
    true, route,   // 联网
  );
  const byKind: Record<string, number> = {};
  for (const x of rows) {
    const k = x.kind === "note" ? "memo" : x.kind;   // note 是 memo 的旧名字
    byKind[k] = (byKind[k] ?? 0) + 1;
  }
  return { count: rows.length, by_kind: byKind, by_day: days, ...prose };
}

// ── 模型调用 ─────────────────────────────────────────────────────────────

async function ask(
  system: string, user: string, schema: Record<string, unknown>,
  web = false, route = "default",
): Promise<Record<string, unknown>> {
  const raw = await callLLM({
    system, user, schema, schemaName: "recap", route,
    // 联网只有 OpenAI 有；换别家时资讯页会退化成「凭模型记忆推荐」，
    // 所以那边的提示词也要跟着松口，别再许诺「我去搜」
    web: web && supportsWeb(route),
    effort: "low",
    maxTokens: 6000,
  });
  return JSON.parse(raw);
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

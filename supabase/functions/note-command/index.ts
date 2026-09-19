// 对着一条笔记下口头指令，结果**附在笔记下面**，不碰原话。
//
// 指令可以是「整理一下」「改正式点」「扩写」，也可以是
// 「帮我研究一下这个灵感可不可行」——后者要联网查，所以这里开了 web_search。
//
// 跟 answer-question 的区别：那边是一问一答的独立对话；
// 这边每次都以**这条笔记的正文**为上下文，产出挂在这条笔记名下。
import { loadProfile } from "../_shared/profile.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const PROVIDER = Deno.env.get("LLM_PROVIDER") ?? "openai";

const SYSTEM = `你在帮一个人处理他自己记下的笔记。

他会给你两样东西：
- <笔记>：他之前记下的内容（语音转写而来）
- <指令>：他刚刚口头说的要求（同样是转写，可能有口误、重复或不完整）

按指令去做，**只输出结果本身**。

指令大致是两类，按类办事：
1. **加工文字**（整理、改正式、扩写、缩写、翻译、提炼要点）——
   输出加工后的文字。不要复述原文，也不要解释你改了什么。
2. **研究 / 调研 / 核实**（「这个想法可不可行」「有没有人做过」「查一下行情」）——
   **先联网查**，再给结论。有依据的地方带上来源链接；
   不确定就直说不确定，**不要编**，尤其是数字、公司名、价格。

通用要求：
- 不要开场白（「好的」「以下是」），不要用代码块把整段包起来
- 他中英混说，保留他的用词习惯，别擅自翻译或统一术语
- 结果会显示在他那条笔记的**下方**，原话原封不动留着 ——
  所以不需要把原文抄一遍，直接给增量`;

/**
 * 笔记下面那三个常驻按钮。
 *
 * 提示词放服务端而不是客户端：措辞是会反复调的，放这边改完部署就生效，
 * 不用等用户装新版本；客户端只发一个动作名，存进库里的也就是「整理」这种
 * 干净标签，而不是一大段提示词。
 *
 * 返回 null = 这个分类没有这个按钮（比如备忘暂时没有第三个）。
 */
function preset(action: string, kind: string): { label: string; prompt: string } | null {
  if (action === "tidy") {
    return {
      label: "整理",
      // 他强调过：这不是润色。只做「听得懂」到「读得顺」这一步
      prompt: `把这段语音转写**整理干净**，这是最小程度的改动：

- 去掉口癖和语气词（「呃」「那个」「就是说」）、重复的话、说岔了又改口的部分
- 补上正确的断句和标点，该分段的地方分段
- **不要修饰、不要润色、不要换更漂亮的词**，也不要补充原话里没有的内容
- 条目式的内容可以用 bullet point 列出来，但别为了好看硬拆 —— 原本是连贯的话就让它连贯
- 保留他的语气和用词习惯，中英混说照旧`,
    };
  }
  if (action === "polish") {
    const byKind: Record<string, string> = {
      idea: `把这条灵感**理成有条理的思路**：

- 抽出核心想法，把支撑它的点分层列清楚
- 补上他话里隐含但没说透的逻辑关系（因果、前提、取舍）
- 结尾给两三个**值得继续想下去的问题**，帮他往深处推一步
- 不要吹捧，也不要替他下结论`,
      content: `把这条选题**整理成适合发自媒体的样子**：

- 开头给一个能抓住人的切入点，不要标题党
- 中间用小标题或 bullet 把内容拆得好读
- 保留他原本的观点和态度 —— 这是他的选题，不是通稿
- 结尾留一个能引发评论的点
- 不要写成「大家好」「今天我们来聊聊」这种模板腔`,
      memo: `把这段随笔**润出文学性**：

- 他说的时候就带着情绪和画面，把这些留住、并且写得更准
- 修整节奏和句式，该短的短，该铺的铺
- 用词可以讲究一点，但**不要堆砌辞藻**，也不要拔高成鸡汤
- 他的真实感受是底线，不要替他添没有的情绪`,
    };
    const p = byKind[kind === "note" ? "memo" : kind];
    return p ? { label: "美化", prompt: p } : null;
  }
  if (action === "extra") {
    if (kind === "content") {
      return {
        label: "英文版",
        prompt: `把这条选题**翻成自然的英文**：

- 按意思翻，不要逐字对译；英文读者读起来要像本来就是英文写的
- 该本地化的地方本地化（度量单位、平台名、文化梗），必要时换成英文语境里的对应说法
- 保留他的语气：口语的地方保持口语，别翻成论文腔
- 只输出英文，不要附中文对照`,
      };
    }
    if (kind === "idea") {
      return {
        label: "研究",
        prompt: `把这条灵感当成一个待验证的想法，**联网查过之后**给一份简短的调研：

1. **可行性**：真正的门槛在哪（成本、技能、时间、牌照/资质）
2. **已经有人做了吗**：找到类似的产品或案例，各自做得怎么样，带链接
3. **关键不确定性**：哪几个假设一旦不成立，这事就不成立
4. **下一步怎么验**：给一个花最少钱、最少时间就能验证的办法

要求：数字、公司名、价格必须来自搜到的来源并附链接；查不到就明说查不到，**不要编**。
篇幅控制在一屏能读完。`,
      };
    }
    return null;   // 备忘的第三个还没想好
  }
  return null;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);

  const H = { apikey: ANON, Authorization: auth };
  const JH = { ...H, "Content-Type": "application/json" };

  const { capture_id, instruction, action } = await req.json().catch(() => ({}));
  if (!capture_id) return json({ error: "missing_capture_id" }, 400);
  if (!instruction?.trim() && !action) return json({ error: "missing_params" }, 400);

  const ures = await fetch(`${SUPA_URL}/auth/v1/user`, { headers: H });
  if (!ures.ok) return json({ error: "invalid_token" }, 401);
  const user = await ures.json();

  // 走用户自己的 JWT，RLS 保证他只碰得到自己的笔记
  const res = await fetch(
    `${SUPA_URL}/rest/v1/captures?id=eq.${capture_id}&select=corrected_text,final_text,kind`,
    { headers: H },
  );
  if (!res.ok) return json({ error: `读取笔记失败 ${res.status}` }, 500);
  const rows = await res.json();
  const note = (rows[0]?.corrected_text ?? rows[0]?.final_text ?? "").trim();
  if (!note) return json({ error: "empty_note" }, 400);
  const kind = rows[0]?.kind ?? "";

  // 按钮走预设提示词；语音下的指令按原样用
  const chosen = action ? preset(action, kind) : null;
  if (action && !chosen) return json({ error: "unknown_action" }, 400);
  const promptText = chosen?.prompt ?? instruction.trim();
  const label = chosen?.label ?? instruction.trim();
  // 只有「研究」和语音指令需要联网：整理/美化/翻译都是纯文本加工，
  // 让它去搜反而会跑题，还白花时间
  const needsWeb = action === "extra" && kind === "idea" || !action;

  const profile = await loadProfile(SUPA_URL, H);
  const system = profile?.body?.trim()
    ? `${SYSTEM}\n\n## 关于这个人\n\n${profile.body.trim()}`
    : SYSTEM;
  // 这条笔记之前下过的指令和产出，一并带上。
  // 不带的话，你说「再正式一点」时模型根本不知道上一版长什么样，只能从原文重来；
  // 带上之后它能接着改，也不会把上一次已经做过的事再做一遍。
  const prevRes = await fetch(
    `${SUPA_URL}/rest/v1/note_outputs?capture_id=eq.${capture_id}` +
      `&select=instruction,text&order=created_at&limit=10`,
    { headers: H },
  );
  const prev = prevRes.ok ? await prevRes.json() as { instruction: string; text: string }[] : [];
  const history = prev.length
    ? "\n\n<之前的指令和结果>\n" +
      prev.map((x, i) =>
        `${i + 1}. 指令：${x.instruction}\n   结果：${x.text.slice(0, 800)}`).join("\n") +
      "\n</之前的指令和结果>\n\n" +
      "注意：上面是**历史**，供你理解他已经要过什么、改到哪一步了。" +
      "这一次要做的只有下面那条指令。"
    : "";

  const userMsg =
    `<笔记>\n${note}\n</笔记>${history}\n\n<指令>\n${promptText}\n</指令>`;

  try {
    const text = (await run(system, userMsg, needsWeb)).trim();
    if (!text) throw new Error("empty_result");

    const ins = await fetch(`${SUPA_URL}/rest/v1/note_outputs`, {
      method: "POST",
      headers: { ...JH, Prefer: "return=representation" },
      body: JSON.stringify([{
        user_id: user.id,
        capture_id,
        instruction: label,
        text,
        // 改写型的产出可以拿去覆盖原文；调研型的不行 —— 那是报告，不是这篇笔记。
        // 同一个 extra 按钮在选题里是翻译（可覆盖）、在灵感里是调研（不可覆盖）
        replaceable: action === "tidy" || action === "polish" ||
          (action === "extra" && kind === "content"),
      }]),
    });
    if (!ins.ok) throw new Error(`写入结果失败 ${ins.status}: ${await ins.text()}`);
    const [row] = await ins.json();
    // instruction 必须回传：预设按钮的标签（「整理」）只有服务端知道，
    // 客户端手里只有动作名，拿不到就会在卡片上显示成「指令：」后面空着
    return json({
      ok: true, id: row.id, text, instruction: label,
      replaceable: row.replaceable, created_at: row.created_at,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("指令执行失败:", msg);
    return json({ error: msg }, 500);
  }
});

async function run(system: string, user: string, web: boolean): Promise<string> {
  if (PROVIDER === "anthropic") {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": Deno.env.get("ANTHROPIC_API_KEY")!,
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: Deno.env.get("LLM_MODEL") ?? "claude-opus-5",
        max_tokens: 4000,
        system,
        messages: [{ role: "user", content: user }],
      }),
    });
    if (!r.ok) throw new Error(`anthropic command ${r.status}: ${await r.text()}`);
    const j = await r.json();
    return (j.content ?? []).find((x: { type: string }) => x.type === "text")?.text ?? "";
  }

  const r = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${Deno.env.get("LLM_API_KEY") ?? Deno.env.get("ASR_API_KEY")!}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: Deno.env.get("LLM_MODEL") ?? "gpt-5.6-terra",
      // 只在需要时开联网：调研类全靠它，但纯文本加工开着会跑题
      ...(web ? { tools: [{ type: "web_search" }] } : {}),
      // 加工文字用不上深思考；调研类的成本主要在搜索，不在推理档位
      reasoning: { effort: "low" },
      input: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
    }),
  });
  if (!r.ok) throw new Error(`openai command ${r.status}: ${await r.text()}`);
  const j = await r.json();
  for (const item of j.output ?? []) {
    for (const c of item.content ?? []) {
      if (c.type === "output_text") return c.text as string;
    }
  }
  throw new Error(`no_output_text: ${JSON.stringify(j).slice(0, 200)}`);
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

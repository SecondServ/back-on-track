// 「问问题」笔记里的 AI 回答。
//
// 跟 process-capture 完全分开：那条管线是「把随口说的话整理成结构化数据」，
// 这里是「回答一个问题」——目标不同，prompt 也不该混在一起。
//
// 每次都把整条对话带上，所以 AI 记得前面聊过什么，可以一问一答地追问。
import { loadProfile } from "../_shared/profile.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
// 用的是哪家的 key。客户端拿不到这个信息（key 只在服务端），
// 但它要照这个挑 AI 头像，所以每次回答都把它一起回报。
const PROVIDER = Deno.env.get("LLM_PROVIDER") ?? "openai";

const SYSTEM = `你在回答一个人随口问出的问题。他中英文混说，问题往往不完整、口语化。

- **直接回答**，不要复述问题，不要写"好的，让我来回答"这类开场白
- 简明：默认三五句话说清楚。他要细节会追问
- 不确定就说不确定，**不要编**。尤其是人名、数字、日期这类能被查证的东西
- 他问的是中文就用中文答；夹英文术语时保留英文原词，不要硬翻
- 这是一段会继续的对话，前面聊过的内容都在上下文里，别重复已经说过的
- 涉及时效性的问题（价格、新闻、哪款最新、现在怎么样）**先联网查**，别凭记忆答；
  查过之后用一句话点出信息大概是什么时候的`;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);

  const H = { apikey: ANON, Authorization: auth };
  const JH = { ...H, "Content-Type": "application/json" };

  const { capture_id, deep } = await req.json().catch(() => ({}));
  if (!capture_id) return json({ error: "missing_capture_id" }, 400);
  console.log(`answer-question deep=${deep === true} provider=${PROVIDER}`);

  const ures = await fetch(`${SUPA_URL}/auth/v1/user`, { headers: H });
  if (!ures.ok) return json({ error: "invalid_token" }, 401);
  const user = await ures.json();

  // 整条对话，按时间顺序
  const tres = await fetch(
    `${SUPA_URL}/rest/v1/qa_turns?capture_id=eq.${capture_id}` +
      `&select=role,text&order=created_at`,
    { headers: H },
  );
  if (!tres.ok) return json({ error: `读取对话失败 ${tres.status}` }, 500);
  const turns = (await tres.json()) as { role: string; text: string }[];
  if (!turns.length) return json({ error: "no_question" }, 400);

  const profile = await loadProfile(SUPA_URL, H);
  const system = profile?.body?.trim()
    ? `${SYSTEM}\n\n## 关于提问的这个人\n\n${profile.body.trim()}`
    : SYSTEM;

  try {
    const answer = await ask(system, turns, deep === true);
    if (!answer.trim()) throw new Error("empty_answer");

    const ins = await fetch(`${SUPA_URL}/rest/v1/qa_turns`, {
      method: "POST",
      headers: JH,
      body: JSON.stringify([{
        user_id: user.id,
        capture_id,
        role: "assistant",
        text: answer.trim(),
      }]),
    });
    if (!ins.ok) throw new Error(`写入回答失败 ${ins.status}: ${await ins.text()}`);

    return json({ ok: true, text: answer.trim(), provider: PROVIDER });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.error("回答失败:", msg);
    return json({ error: msg }, 500);
  }
});

/** 跟 _shared/analysis.ts 里的清洗调用同构：同一套 provider 开关和环境变量 */
async function ask(
  system: string, turns: { role: string; text: string }[], deep: boolean,
): Promise<string> {
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
        // 开思考时 max_tokens 必须大于思考预算，否则正文没地方写
        max_tokens: deep ? 8000 : 2000,
        ...(deep ? { thinking: { type: "enabled", budget_tokens: 4000 } } : {}),
        system,
        messages: turns.map((t) => ({
          role: t.role === "assistant" ? "assistant" : "user",
          content: t.text,
        })),
      }),
    });
    if (!r.ok) throw new Error(`anthropic ask ${r.status}: ${await r.text()}`);
    const j = await r.json();
    const b = (j.content ?? []).find((x: { type: string }) => x.type === "text");
    return b?.text ?? "";
  }

  const r = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${Deno.env.get("LLM_API_KEY") ?? Deno.env.get("ASR_API_KEY")!}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: Deno.env.get("LLM_MODEL") ?? "gpt-5.6-terra",
      // 联网。没有它，模型只能凭训练时的记忆答「最新款是哪个」这类问题，
      // 而这个功能的目标就是「像 ChatGPT 那样问什么答什么」。
      tools: [{ type: "web_search" }],
      // 默认低推理档：这是随口一问的对话，不是解题。
      // 高档位会思考很久，等回答的那几十秒足以让人不想再用 ——
      // 所以「想深一点」做成用户按需打开的开关，而不是默认代价。
      reasoning: { effort: deep ? "high" : "low" },
      input: [
        { role: "system", content: system },
        ...turns.map((t) => ({
          role: t.role === "assistant" ? "assistant" : "user",
          content: t.text,
        })),
      ],
    }),
  });
  if (!r.ok) throw new Error(`openai ask ${r.status}: ${await r.text()}`);
  const j = await r.json();
  for (const item of j.output ?? []) {
    for (const c of item.content ?? []) {
      if (c.type === "output_text") return c.text as string;
    }
  }
  throw new Error(`no_output_text: ${JSON.stringify(j).slice(0, 300)}`);
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

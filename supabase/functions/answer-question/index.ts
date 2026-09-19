// 「问问题」笔记里的 AI 回答。
//
// 跟 process-capture 完全分开：那条管线是「把随口说的话整理成结构化数据」，
// 这里是「回答一个问题」——目标不同，prompt 也不该混在一起。
//
// 每次都把整条对话带上，所以 AI 记得前面聊过什么，可以一问一答地追问。
import { loadProfile, routeOf } from "../_shared/profile.ts";
import { callLLM, llmProvider, supportsWeb } from "../_shared/llm.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
// 用的是哪家的 key。客户端拿不到这个信息（key 只在服务端），
// 但它要照这个挑 AI 头像，所以每次回答都把它一起回报。
const PROVIDER = llmProvider();   // 仅用于日志；实际按用户线路走

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
    const answer = await ask(system, turns, deep === true, routeOf(profile));
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

async function ask(
  system: string, turns: { role: string; text: string }[], deep: boolean,
  route: string,
): Promise<string> {
  return await callLLM({
    system,
    route,
    turns: turns.map((t) => ({ role: t.role, content: t.text })),
    // 联网只有 OpenAI 那条通路有。换成别家时这里自动降级成「凭模型自己的知识答」
    web: supportsWeb(route),
    effort: deep ? "high" : "low",
    maxTokens: deep ? 8000 : 2000,
  });
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

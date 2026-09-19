// 所有模型调用的唯一出口。
//
// 为什么要这一层：三家的接口形状完全不同 ——
//   · OpenAI    用 Responses API，支持联网搜索和**严格** JSON schema
//   · DeepSeek  只兼容老的 chat/completions，没有联网，JSON 只能靠 json_object 模式
//   · Anthropic 用 messages，schema 只能写进提示词里劝
// 五个调用点各自适配一遍的话，换家就得改五处，漏一处就是线上事故。
//
// 切换只改环境变量：
//   LLM_PROVIDER=openai    （默认）LLM_MODEL 默认 gpt-5.6-terra
//   LLM_PROVIDER=deepseek  LLM_BASE_URL 默认 https://api.deepseek.com/v1
//                          LLM_MODEL 默认 deepseek-chat
//   LLM_PROVIDER=anthropic LLM_MODEL 默认 claude-opus-5

export interface LlmCall {
  system: string;
  /** 单轮就给 user；多轮对话（问答）给 turns，二选一 */
  user?: string;
  turns?: { role: string; content: string }[];
  /** 要结构化输出就给 JSON Schema */
  schema?: Record<string, unknown>;
  schemaName?: string;
  /** 要联网搜索。**只有 OpenAI 支持**，其余家会被忽略 */
  web?: boolean;
  maxTokens?: number;
  /** 推理档位，只对 OpenAI 有意义 */
  effort?: "low" | "high";
  /** 走哪条线路。default = OpenAI，china = DeepSeek。不给就读环境变量 */
  route?: string;
}

export function llmProvider(route?: string): string {
  // 用户在手机上选的线路优先；没选过才看环境变量
  if (route === "china") return "deepseek";
  if (route === "default") return "openai";
  return Deno.env.get("LLM_PROVIDER") ?? "openai";
}

/**
 * 这条线路支不支持联网搜索 —— 调用方据此决定要不要在提示词里许诺「我会去查」。
 * 中国线路（DeepSeek）没有联网，许诺了模型就会开始编。
 */
export function supportsWeb(route?: string): boolean {
  return llmProvider(route) === "openai";
}

/** 单轮/多轮统一成一串消息。role 只允许 user/assistant，其余一律当 user */
function msgs(o: LlmCall): { role: string; content: string }[] {
  if (o.turns?.length) {
    return o.turns.map((t) => ({
      role: t.role === "assistant" ? "assistant" : "user",
      content: t.content,
    }));
  }
  return [{ role: "user", content: o.user ?? "" }];
}

export async function callLLM(opts: LlmCall): Promise<string> {
  switch (llmProvider(opts.route)) {
    case "anthropic": return await viaAnthropic(opts);
    case "deepseek": return await viaChatCompletions(opts);
    default: return await viaResponses(opts);
  }
}

// ── OpenAI：Responses API ────────────────────────────────────────────────

async function viaResponses(o: LlmCall): Promise<string> {
  const r = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${Deno.env.get("LLM_API_KEY") ?? Deno.env.get("ASR_API_KEY")!}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: Deno.env.get("LLM_MODEL") ?? "gpt-5.6-terra",
      ...(o.effort ? { reasoning: { effort: o.effort } } : {}),
      ...(o.web ? { tools: [{ type: "web_search" }] } : {}),
      input: [{ role: "system", content: o.system }, ...msgs(o)],
      ...(o.schema
        ? {
          text: {
            format: {
              type: "json_schema",
              name: o.schemaName ?? "result",
              strict: true,
              schema: o.schema,
            },
          },
        }
        : {}),
    }),
  });
  if (!r.ok) throw new Error(`openai ${r.status}: ${await r.text()}`);
  const j = await r.json();
  for (const item of j.output ?? []) {
    for (const c of item.content ?? []) {
      if (c.type === "refusal") throw new Error(`model_refused: ${c.refusal}`);
      if (c.type === "output_text") return c.text as string;
    }
  }
  throw new Error(`no_output_text: ${JSON.stringify(j).slice(0, 300)}`);
}

// ── DeepSeek 及任何兼容 chat/completions 的服务 ──────────────────────────

async function viaChatCompletions(o: LlmCall): Promise<string> {
  const base = (Deno.env.get("LLM_BASE_URL") ?? "https://api.deepseek.com/v1")
    .replace(/\/+$/, "");
  // json_object 模式有个硬性要求：提示词里必须出现 "json" 字样，
  // 而且它**不校验结构**，只保证是合法 JSON —— 所以把 schema 贴进提示词里劝
  const system = o.schema
    ? `${o.system}\n\n只输出一个 JSON 对象，严格符合这个结构（不要多加字段，不要用代码块包起来）：\n${
      JSON.stringify(o.schema)
    }`
    : o.system;

  const r = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: {
      // 中国线路用单独的 key，跟 OpenAI 那把互不影响 ——
      // 两条线路可以同时配好，切换时不用再碰 secrets
      Authorization: `Bearer ${
        Deno.env.get("CN_LLM_API_KEY") ?? Deno.env.get("LLM_API_KEY")!
      }`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: Deno.env.get("LLM_MODEL") ?? "deepseek-chat",
      max_tokens: o.maxTokens ?? 4000,
      ...(o.schema ? { response_format: { type: "json_object" } } : {}),
      messages: [{ role: "system", content: system }, ...msgs(o)],
    }),
  });
  if (!r.ok) throw new Error(`deepseek ${r.status} @${base}: ${await r.text()}`);
  const j = await r.json();
  const text = j.choices?.[0]?.message?.content;
  if (typeof text !== "string" || !text.trim()) {
    throw new Error(`no_content: ${JSON.stringify(j).slice(0, 300)}`);
  }
  // 有的兼容实现仍会用 ```json 包起来，统一剥掉
  return text.replace(/^\s*```(?:json)?\s*|\s*```\s*$/g, "").trim();
}

// ── Anthropic ───────────────────────────────────────────────────────────

async function viaAnthropic(o: LlmCall): Promise<string> {
  const system = o.schema
    ? `${o.system}\n\n只输出一个 JSON 对象，符合这个结构：\n${JSON.stringify(o.schema)}`
    : o.system;
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": Deno.env.get("ANTHROPIC_API_KEY")!,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: Deno.env.get("LLM_MODEL") ?? "claude-opus-5",
      max_tokens: o.maxTokens ?? 4000,
      system,
      messages: msgs(o),
    }),
  });
  if (!r.ok) throw new Error(`anthropic ${r.status}: ${await r.text()}`);
  const j = await r.json();
  const text = (j.content ?? []).find((x: { type: string }) => x.type === "text")?.text;
  if (!text) throw new Error(`no_content: ${JSON.stringify(j).slice(0, 300)}`);
  return String(text).replace(/^\s*```(?:json)?\s*|\s*```\s*$/g, "").trim();
}

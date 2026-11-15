// 用户档案：让管线认识说话的这个人。
//
// 拆成两块喂给不同环节，是因为它们的预算差了两个数量级：
//   glossary → ASR 的 prompt 参数，只有约 224 token，塞长文会被静默截断
//   body     → LLM 的 system prompt，可以放几百字的背景
// 「张三」「Foster City」这类词在 ASR 那一步就听错了，
// 后面的 LLM 看不到音频，只能顺着错字猜，补不回来 —— 所以必须在 ASR 就给提示。

export type Profile = {
  body: string;
  glossary: string[];
  /** 用哪条线路跑 AI：default = OpenAI，china = DeepSeek + 硅基流动 */
  route: string;
  /**
   * 他在「名词校对」里确认过的通讯录：人、地方、事物。
   * heard = 曾经被转写成的样子（跟 term 一样或 null 就是没听错过）
   */
  terms: {
    term: string;
    heard: string | null;
    category: string;
    full_name: string | null;
    relation: string | null;
  }[];
};

/** 读当前用户的档案。没建过、或读失败都返回 null，绝不因此让整条管线失败 */
export async function loadProfile(
  supaUrl: string, headers: Record<string, string>,
): Promise<Profile | null> {
  try {
    const r = await fetch(
      `${supaUrl}/rest/v1/user_profile?select=body,glossary,ai_route&limit=1`,
      { headers },
    );
    if (!r.ok) return null;
    const [row] = await r.json();
    if (!row) return null;
    return {
      body: row.body ?? "",
      glossary: row.glossary ?? [],
      route: row.ai_route ?? "default",
      terms: await loadConfirmedTerms(supaUrl, headers),
    };
  } catch {
    return null;
  }
}

/**
 * 专有名词表 → ASR 的听写提示。
 *
 * Whisper 系的 prompt 是「假装这是上一段的转写」，所以写成自然句子比堆词表管用。
 * 留足余量截断：宁可少给几个词，也不要整段被平台悄悄切掉。
 */
/** 没建过档案也得有条线路可走 */
export function routeOf(p: Profile | null): string {
  return p?.route ?? "default";
}

export function asrHint(p: Profile | null): string | undefined {
  // 手写的词排前面，再接「名词校对」里确认过的（最近确认的在前）。
  // 总长有上限，排在后面的会被截掉 —— 所以手写的、新近的优先
  const words = [...new Set([
    ...(p?.glossary ?? []),
    ...(p?.terms ?? []).map((t) => t.term),
  ].map((w) => w.trim()).filter(Boolean))];
  if (!words.length) return undefined;
  return `以下是可能出现的人名、地名和专有名词：${words.join("、")}。`.slice(0, 600);
}

/** 「名词校对」里确认过的写法，最近确认的在前。读失败就当没有 */
async function loadConfirmedTerms(
  supaUrl: string, headers: Record<string, string>,
): Promise<Profile["terms"]> {
  try {
    const r = await fetch(
      `${supaUrl}/rest/v1/glossary_terms?status=eq.confirmed&select=term,heard,category,full_name,relation` +
        `&order=confirmed_at.desc.nullslast&limit=300`,
      { headers },
    );
    return r.ok ? await r.json() : [];
  } catch {
    return [];
  }
}

const CATEGORY_TITLE: Record<string, string> = { person: "人", place: "地方", other: "事物" };

/**
 * 给 LLM 的背景：档案正文 + 通讯录。
 *
 * 通讯录是背景，不是替换规则：「小张（同事）」和「小章（室友）」可以同时在里面，
 * 转写里出现读音相近的词时，让模型看上下文判断说的是谁，拿不准就保留原样。
 * 以前写过「一律改成」，那会把另一个同音的人也改掉，已经不用了。
 */
export function profileForLLM(p: Profile | null): string | undefined {
  if (!p) return undefined;
  const groups: Record<string, string[]> = {};
  for (const e of groupTerms(p.terms ?? [])) {
    const notes = [e.full_name, e.relation].filter((x): x is string => !!x?.trim());
    if (e.heard.length) notes.push(`转写曾听成${e.heard.map((h) => `「${h}」`).join("")}`);
    const line = notes.length ? `- ${e.term}（${notes.join("；")}）` : `- ${e.term}`;
    (groups[e.category] ??= []).push(line);
  }
  const sections = ["person", "place", "other"]
    .filter((c) => groups[c]?.length)
    .map((c) => `${CATEGORY_TITLE[c]}：\n${groups[c].join("\n")}`);
  const terms = sections.length
    ? "### 他的通讯录（生活里的人、地方和事物）\n\n" +
      "转写里出现跟下面某个名字读音相近的词时，结合上下文和括号里的关系判断说的是不是这一个，" +
      "是就用这里的写法。读音相近的可能不止一个，要按上下文选；拿不准就保留原文的写法，不要硬改。\n\n" +
      sections.join("\n\n")
    : "";
  const body = [p.body?.trim(), terms].filter(Boolean).join("\n\n");
  return body || undefined;
}

export type TermEntry = {
  term: string;
  category: string;
  full_name: string | null;
  relation: string | null;
  /** 这个名字曾被转写成的各种样子（不含它自己，也不含通讯录里另一个人的名字） */
  heard: string[];
};

/**
 * 同一个名字可能有好几行：「侯丁豪」先后被听成侯迪豪、洪钧豪……每次确认都是一行。
 * 合成一条：曾被听成的写法全部收齐；全名、关系取最先遇到的那个（列表按最近确认排，所以是最新的）。
 */
export function groupTerms(rows: Profile["terms"]): TermEntry[] {
  const names = new Set(rows.map((t) => t.term));
  const map = new Map<string, TermEntry>();
  for (const t of rows) {
    let e = map.get(t.term);
    if (!e) {
      e = { term: t.term, category: t.category, full_name: null, relation: null, heard: [] };
      map.set(t.term, e);
    }
    e.full_name ??= t.full_name?.trim() || null;
    e.relation ??= t.relation?.trim() || null;
    // 曾经被听成的样子只是提示。要是那个写法本身也是通讯录里另一个人，就不提，免得混在一起
    const h = t.heard?.trim();
    if (h && h !== t.term && !names.has(h) && !e.heard.includes(h)) e.heard.push(h);
  }
  return [...map.values()];
}

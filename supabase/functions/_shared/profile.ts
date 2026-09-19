// 用户档案：让管线认识说话的这个人。
//
// 拆成两块喂给不同环节，是因为它们的预算差了两个数量级：
//   glossary → ASR 的 prompt 参数，只有约 224 token，塞长文会被静默截断
//   body     → LLM 的 system prompt，可以放几百字的背景
// 「张三」「Foster City」这类词在 ASR 那一步就听错了，
// 后面的 LLM 看不到音频，只能顺着错字猜，补不回来 —— 所以必须在 ASR 就给提示。

export type Profile = { body: string; glossary: string[] };

/** 读当前用户的档案。没建过、或读失败都返回 null，绝不因此让整条管线失败 */
export async function loadProfile(
  supaUrl: string, headers: Record<string, string>,
): Promise<Profile | null> {
  try {
    const r = await fetch(
      `${supaUrl}/rest/v1/user_profile?select=body,glossary&limit=1`, { headers });
    if (!r.ok) return null;
    const [row] = await r.json();
    if (!row) return null;
    return { body: row.body ?? "", glossary: row.glossary ?? [] };
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
export function asrHint(p: Profile | null): string | undefined {
  const words = (p?.glossary ?? []).map((w) => w.trim()).filter(Boolean);
  if (!words.length) return undefined;
  return `以下是可能出现的人名、地名和专有名词：${words.join("、")}。`.slice(0, 600);
}

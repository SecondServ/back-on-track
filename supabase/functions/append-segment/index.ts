// 在已有记录上追加一段语音：转写 → 轻量清洗 → 把文字交还给客户端。
//
// 这里**不碰 captures 的正文**：插在哪里只有客户端知道（光标位置）。
// 也不重新分类：用户是在补充这条记录，不是新说一件事，
// 分类、标题、已经产生的待办都保持原样。

import { cleanText } from "../_shared/analysis.ts";
import { transcribe, type AsrProvider } from "../_shared/asr.ts";
import { asrHint, loadProfile, routeOf } from "../_shared/profile.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const AUDIO_BUCKET = "captures";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);

  const H = { apikey: ANON, Authorization: auth };
  const JH = { ...H, "Content-Type": "application/json" };

  const { capture_id, audio_path, duration_ms } = await req.json().catch(() => ({}));
  if (!capture_id || !audio_path) return json({ error: "missing_params" }, 400);

  const ures = await fetch(`${SUPA_URL}/auth/v1/user`, { headers: H });
  if (!ures.ok) return json({ error: "invalid_token" }, 401);
  const user = await ures.json();

  // 先建好这一段，界面上能立刻看到「转写中」
  const insRes = await fetch(`${SUPA_URL}/rest/v1/capture_segments`, {
    method: "POST",
    headers: { ...JH, Prefer: "return=representation" },
    body: JSON.stringify([{
      user_id: user.id,
      capture_id,
      audio_path,
      duration_ms: duration_ms ?? null,
      asr_status: "transcribing",
    }]),
  });
  if (!insRes.ok) return json({ error: `建分段失败 ${insRes.status}` }, 500);
  const [segment] = await insRes.json();

  const patchSegment = (patch: Record<string, unknown>) =>
    fetch(`${SUPA_URL}/rest/v1/capture_segments?id=eq.${segment.id}`, {
      method: "PATCH", headers: JH, body: JSON.stringify(patch),
    });

  try {
    const dl = await fetch(
      `${SUPA_URL}/storage/v1/object/${AUDIO_BUCKET}/${audio_path}`, { headers: H });
    if (!dl.ok) throw new Error(`音频下载失败 ${dl.status}`);

    const profile = await loadProfile(SUPA_URL, H);
    const raw = await transcribe(
      await dl.blob(),
      (Deno.env.get("ASR_PROVIDER") ?? "openai") as AsrProvider,
      Deno.env.get("ASR_API_KEY")!,
      asrHint(profile),
      routeOf(profile),
    );
    if (!raw?.trim()) throw new Error("empty_transcript");

    const cleaned = await cleanText(raw, profile?.body, routeOf(profile));
    await patchSegment({ final_text: raw, corrected_text: cleaned, asr_status: "done" });

    // 只把这一段存好并交还文字，**不改 captures 的正文**。
    // 插在哪里由客户端决定 —— 只有它知道光标在哪；
    // 而且原话是客户端用「首段 + 各分段」自己拼的，
    // 服务端再往 final_text 后面接一次会让原话重复一遍。
    return json({ ok: true, text: cleaned });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await patchSegment({ asr_status: "failed", asr_error: msg });
    return json({ error: msg }, 500);
  }
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

// 只转写，不落库。
//
// append-segment 必须绑一条记录（要 capture_id），可「用户档案」这类输入
// 不属于任何一条记录。与其为了转写去伪造一条记录，不如留一个纯粹的转写口子：
// 传音频，还文字，什么都不写。将来别的地方要语音输入也用它。
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

  const { audio_path } = await req.json().catch(() => ({}));
  if (!audio_path) return json({ error: "missing_params" }, 400);

  // 存储策略只认第一层文件夹，越权的路径在下载这一步就会被挡掉
  const dl = await fetch(
    `${SUPA_URL}/storage/v1/object/${AUDIO_BUCKET}/${audio_path}`, { headers: H });
  if (!dl.ok) return json({ error: `音频下载失败 ${dl.status}` }, 500);

  try {
    const profile = await loadProfile(SUPA_URL, H);
    const raw = await transcribe(
      await dl.blob(),
      (Deno.env.get("ASR_PROVIDER") ?? "openai") as AsrProvider,
      Deno.env.get("ASR_API_KEY")!,
      asrHint(profile),
      routeOf(profile),
    );
    if (!raw?.trim()) return json({ error: "empty_transcript" }, 500);
    return json({ ok: true, text: await cleanText(raw, profile?.body, routeOf(profile)) });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

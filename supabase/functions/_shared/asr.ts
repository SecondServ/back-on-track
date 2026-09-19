// 服务端 ASR。中英混说是硬需求，端上按单一 locale 工作的引擎都不行。
// M1 的任务之一就是拿真实语音横评这几家，所以这里做成可切换的。

export type AsrProvider = "openai" | "elevenlabs" | "deepgram";

/** 带超时的 fetch —— 没有它，卡住的请求会被平台以 WORKER_RESOURCE_LIMIT 掐掉，
 *  什么线索都留不下。宁可自己先失败，也要留下错误信息。 */
async function fetchWithTimeout(
  url: string, init: RequestInit, ms = 60_000,
): Promise<Response> {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: ac.signal });
  } catch (e) {
    if (e instanceof Error && e.name === "AbortError") {
      throw new Error(`ASR 请求超过 ${ms / 1000}s 未返回: ${url}`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

export async function transcribe(
  audio: Blob,
  provider: AsrProvider,
  apiKey: string,
  /** 听写提示：人名地名等专有名词，帮 ASR 把音听对。见 _shared/profile.ts */
  hint?: string,
): Promise<string> {
  switch (provider) {
    case "openai": {
      const form = new FormData();
      form.append("file", audio, "audio.m4a");
      // whisper-1 是默认；ASR_MODEL 可换成 gpt-4o-transcribe 等，同一个 key
      form.append("model", Deno.env.get("ASR_MODEL") ?? "whisper-1");
      // 不传 language —— 传了会把混说压成单语，反而更差
      // prompt 是纠正专有名词的唯一时机：LLM 在下游看不到音频，补不回听错的名字
      if (hint) form.append("prompt", hint);
      const r = await fetchWithTimeout("https://api.openai.com/v1/audio/transcriptions", {
        method: "POST",
        headers: { Authorization: `Bearer ${apiKey}` },
        body: form,
      });
      if (!r.ok) throw new Error(`openai asr ${r.status}: ${await r.text()}`);
      return (await r.json()).text;
    }

    case "elevenlabs": {
      const form = new FormData();
      form.append("file", audio, "audio.m4a");
      form.append("model_id", "scribe_v1");
      const r = await fetchWithTimeout("https://api.elevenlabs.io/v1/speech-to-text", {
        method: "POST",
        headers: { "xi-api-key": apiKey },
        body: form,
      });
      if (!r.ok) throw new Error(`11labs asr ${r.status}: ${await r.text()}`);
      return (await r.json()).text;
    }

    case "deepgram": {
      const r = await fetchWithTimeout(
        "https://api.deepgram.com/v1/listen?model=nova-3&detect_language=true",
        {
          method: "POST",
          headers: { Authorization: `Token ${apiKey}` },
          body: audio,
        },
      );
      if (!r.ok) throw new Error(`deepgram asr ${r.status}: ${await r.text()}`);
      const j = await r.json();
      return j.results.channels[0].alternatives[0].transcript;
    }
  }
}

#!/usr/bin/env node
// ASR 横评：同一段音频过多家，结果并排输出。
//
//   node tools/asr-bench.mjs 我的录音.m4a
//
// 只跑「设了 key 的那几家」，没设的自动跳过。key 从环境变量读：
//   DEEPGRAM_API_KEY / ASSEMBLYAI_API_KEY / OPENAI_API_KEY / ELEVENLABS_API_KEY

import { readFile } from "node:fs/promises";
import { basename } from "node:path";

const file = process.argv[2];
if (!file) {
  console.error("用法: node tools/asr-bench.mjs <音频文件>");
  process.exit(1);
}

const buf = await readFile(file);
const blob = new Blob([buf]);
console.log(`\n音频: ${basename(file)}  (${(buf.length / 1024).toFixed(0)} KB)\n`);

// ── 各家适配 ──────────────────────────────────────────────────────────────

async function deepgram(key) {
  // 不指定 language，让它自己检测；指定了反而会把混说压成单语
  const r = await fetch(
    "https://api.deepgram.com/v1/listen?model=nova-3&detect_language=true&punctuate=true",
    { method: "POST", headers: { Authorization: `Token ${key}` }, body: buf },
  );
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
  const j = await r.json();
  return j.results.channels[0].alternatives[0].transcript;
}

async function openai(key, model = "whisper-1") {
  const form = new FormData();
  form.append("file", blob, basename(file));
  form.append("model", model);
  const r = await fetch("https://api.openai.com/v1/audio/transcriptions", {
    method: "POST", headers: { Authorization: `Bearer ${key}` }, body: form,
  });
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
  return (await r.json()).text;
}

async function elevenlabs(key) {
  const form = new FormData();
  form.append("file", blob, basename(file));
  form.append("model_id", "scribe_v1");
  const r = await fetch("https://api.elevenlabs.io/v1/speech-to-text", {
    method: "POST", headers: { "xi-api-key": key }, body: form,
  });
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
  return (await r.json()).text;
}

async function assemblyai(key) {
  // 两步：先上传，再轮询
  const up = await fetch("https://api.assemblyai.com/v2/upload", {
    method: "POST", headers: { authorization: key }, body: buf,
  });
  if (!up.ok) throw new Error(`upload ${up.status} ${await up.text()}`);
  const { upload_url } = await up.json();

  const cr = await fetch("https://api.assemblyai.com/v2/transcript", {
    method: "POST",
    headers: { authorization: key, "content-type": "application/json" },
    body: JSON.stringify({
      audio_url: upload_url,
      speech_model: "universal",
      language_detection: true,
    }),
  });
  if (!cr.ok) throw new Error(`create ${cr.status} ${await cr.text()}`);
  const { id } = await cr.json();

  for (let i = 0; i < 60; i++) {
    await new Promise((r) => setTimeout(r, 2000));
    const p = await fetch(`https://api.assemblyai.com/v2/transcript/${id}`, {
      headers: { authorization: key },
    });
    const j = await p.json();
    if (j.status === "completed") return j.text;
    if (j.status === "error") throw new Error(j.error);
  }
  throw new Error("轮询超时");
}

// ── 跑 ────────────────────────────────────────────────────────────────────

const PROVIDERS = [
  ["Deepgram nova-3", "DEEPGRAM_API_KEY", deepgram],
  ["AssemblyAI universal", "ASSEMBLYAI_API_KEY", assemblyai],
  ["OpenAI whisper-1", "OPENAI_API_KEY", (k) => openai(k, "whisper-1")],
  ["OpenAI gpt-4o-transcribe", "OPENAI_API_KEY", (k) => openai(k, "gpt-4o-transcribe")],
  ["ElevenLabs scribe", "ELEVENLABS_API_KEY", elevenlabs],
];

// 防呆：key 里出现非 ASCII，几乎肯定是把说明文字里的占位符原样贴进来了。
// 不拦的话会得到一句莫名其妙的 "Cannot convert argument to a ByteString"。
for (const [name, env] of PROVIDERS) {
  const v = process.env[env];
  if (v && !/^[\x21-\x7e]+$/.test(v)) {
    console.error(`\n❌ ${env} 看起来不是真的 key：${JSON.stringify(v.slice(0, 20))}`);
    console.error(`   里面有非 ASCII 字符 —— 是不是把「你的key」这类占位符直接贴进去了？`);
    console.error(`   去 https://platform.openai.com/api-keys 复制真正的 key（sk- 开头）。\n`);
    process.exit(1);
  }
}

const active = PROVIDERS.filter(([, env]) => process.env[env]);
if (!active.length) {
  console.error("一个 key 都没设。至少设一个再跑，例如：");
  console.error("  export DEEPGRAM_API_KEY=xxx\n");
  process.exit(1);
}

const skipped = PROVIDERS.filter(([, env]) => !process.env[env]);
if (skipped.length) {
  console.log(`跳过（没设 key）: ${skipped.map(([n]) => n).join(", ")}\n`);
}

const results = await Promise.all(active.map(async ([name, env, fn]) => {
  const t0 = Date.now();
  try {
    const text = await fn(process.env[env]);
    return { name, ms: Date.now() - t0, text };
  } catch (e) {
    return { name, ms: Date.now() - t0, error: e.message.slice(0, 300) };
  }
}));

for (const r of results) {
  console.log("─".repeat(70));
  console.log(`${r.name}   ${(r.ms / 1000).toFixed(1)}s`);
  console.log("─".repeat(70));
  console.log(r.error ? `  ⚠️  ${r.error}` : `  ${r.text}`);
  console.log();
}

console.log("─".repeat(70));
console.log("看什么：中英切换处有没有塌、专有名词对不对、标点合不合理。");
console.log("速度只做参考 —— 这是批量接口，流式是另一套。");

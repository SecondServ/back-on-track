#!/usr/bin/env node
// 端到端验证：登录 → 上传音频 → 建 capture → 调函数 → 检查各表落库情况
// 由 tools/e2e-test.sh 调用（它负责隐藏输入密码）

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

const URL = "https://zezoyztdjrpymerxjjog.supabase.co";
const ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inplem95enRkanJweW1lcnhqam9nIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg4OTg0ODksImV4cCI6MjEwNDQ3NDQ4OX0.SEVNgzg2eVfsQJCHKe0OnV_RdSOph5EMdW4mTiPdvDU";

const { TEST_EMAIL, TEST_PASSWORD } = process.env;
const audioPath = process.argv[2];
if (!audioPath) { console.error("用法: e2e-test.sh <音频文件>"); process.exit(1); }

const step = (n, s) => console.log(`\n─── ${n} ${s} ${"─".repeat(Math.max(0, 50 - s.length))}`);
const die = (m) => { console.error(`\n❌ ${m}`); process.exit(1); };

// ── 1. 登录 ───────────────────────────────────────────────────────────────
step("1/5", "登录");
const auth = await fetch(`${URL}/auth/v1/token?grant_type=password`, {
  method: "POST",
  headers: { apikey: ANON, "Content-Type": "application/json" },
  body: JSON.stringify({ email: TEST_EMAIL, password: TEST_PASSWORD }),
});
if (!auth.ok) die(`登录失败 ${auth.status}: ${await auth.text()}`);
const { access_token: jwt, user } = await auth.json();
console.log(`  ✅ 用户 ${user.id}`);

const H = { apikey: ANON, Authorization: `Bearer ${jwt}` };

// ── 2. 上传音频 ───────────────────────────────────────────────────────────
step("2/5", "上传音频到 Storage");
const audio = await readFile(audioPath);
const captureId = randomUUID();
// 路径必须是 {user_id}/xxx —— 0002 的 Storage 规则靠第一层文件夹判断归属
const objectPath = `${user.id}/${captureId}.m4a`;

const up = await fetch(`${URL}/storage/v1/object/captures/${objectPath}`, {
  method: "POST",
  headers: { ...H, "Content-Type": "audio/m4a" },
  body: audio,
});
if (!up.ok) die(`上传失败 ${up.status}: ${await up.text()}\n   → 多半是 0002_storage_policies.sql 没跑`);
console.log(`  ✅ ${objectPath}  (${(audio.length / 1024).toFixed(0)} KB)`);

// ── 3. 建 capture 记录 ────────────────────────────────────────────────────
step("3/5", "写入 captures 表");
const ins = await fetch(`${URL}/rest/v1/captures`, {
  method: "POST",
  headers: { ...H, "Content-Type": "application/json", Prefer: "return=representation" },
  body: JSON.stringify({
    id: captureId, user_id: user.id, audio_path: objectPath,
    source: "e2e-test", client_tz: "America/Los_Angeles",
  }),
});
if (!ins.ok) die(`插入失败 ${ins.status}: ${await ins.text()}`);
console.log(`  ✅ capture_id = ${captureId}`);

// ── 4. 跑管线 ─────────────────────────────────────────────────────────────
step("4/5", "调用 process-capture（ASR → Claude）");
const t0 = Date.now();
const fn = await fetch(`${URL}/functions/v1/process-capture`, {
  method: "POST",
  headers: { ...H, "Content-Type": "application/json" },
  body: JSON.stringify({ capture_id: captureId }),
});
const secs = ((Date.now() - t0) / 1000).toFixed(1);
const body = await fn.json();
if (!fn.ok) die(`管线失败 ${fn.status} (${secs}s):\n   ${JSON.stringify(body, null, 2)}`);

const a = body.analysis;
console.log(`  ✅ 耗时 ${secs}s\n`);
console.log(`  转录原文 : ${a.corrected_text}`);
console.log(`  摘要     : ${a.summary}`);
console.log(`  语言     : ${a.language}`);
console.log(`  归类     : ${a.kind}  (置信度 ${a.kind_confidence})`);
if (a.tasks?.length) {
  console.log(`  待办     : ${a.tasks.length} 条`);
  a.tasks.forEach((t, i) => console.log(
    `    ${i + 1}. ${t.title}\n       deadline=${t.due_at ?? "无"}${t.due_is_fuzzy ? " (模糊)" : ""}  优先级=${t.priority}  需提醒=${t.needs_reminder}`));
}
for (const k of ["idea", "content", "note", "meal"]) {
  if (a[k]) console.log(`  ${k.padEnd(9)}: ${JSON.stringify(a[k])}`);
}

// ── 5. 核对落库 ───────────────────────────────────────────────────────────
step("5/5", "核对数据库");
for (const t of ["tasks", "ideas", "content_pieces", "notes", "meals"]) {
  const r = await fetch(`${URL}/rest/v1/${t}?capture_id=eq.${captureId}&select=id`, { headers: H });
  const rows = await r.json();
  if (rows.length) console.log(`  ✅ ${t}: ${rows.length} 行`);
}
// 只看本次 capture 产生的提醒 —— 不过滤的话会把历史测试数据也算进来
const tres = await fetch(
  `${URL}/rest/v1/tasks?capture_id=eq.${captureId}&select=id`, { headers: H });
const taskIds = (await tres.json()).map((t) => t.id);
const reminders = taskIds.length
  ? await (await fetch(
      `${URL}/rest/v1/reminders?task_id=in.(${taskIds.join(",")})&select=fire_at,tier,body&order=fire_at`,
      { headers: H })).json()
  : [];
if (reminders.length) {
  console.log(`  ✅ reminders: ${reminders.length} 条`);
  for (const r of reminders) {
    const local = new Date(r.fire_at).toLocaleString("zh-CN", {
      timeZone: "America/Los_Angeles", weekday: "short",
      month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
    console.log(`     tier${r.tier}  ${local}  ${r.body}`);
  }
}

console.log("\n🎉 整条管线跑通了。\n");

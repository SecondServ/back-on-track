// 扫描以前的日记，把里面的人、地方、事物收进「名词校对」。
//
// 每次只扫几篇就返回，告诉客户端还剩多少 —— 客户端循环调用、显示进度。
// 一口气全扫完可能跑到超时，而且中途断了也不知道扫到哪了；
// 现在每篇扫完就打上 terms_scanned_at，断了再点一次会从没扫的接着来，扫过的不重复花钱。
//
// 旧日记的正文一个字都不改，只往通讯录里加待确认的条目。

import { loadProfile, routeOf } from "../_shared/profile.ts";
import { scanTerms } from "../_shared/terms.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;

/** 一次扫几篇。长日记会切成几段，每段一次 AI 调用，所以不贪多 */
const BATCH = 3;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);
  const H = { apikey: ANON, Authorization: auth };

  const ures = await fetch(`${SUPA_URL}/auth/v1/user`, { headers: H });
  if (!ures.ok) return json({ error: "bad_auth" }, 401);
  const user = await ures.json();

  // 最早的先扫。RLS 保证只读得到自己的日记
  const unscanned = `${SUPA_URL}/rest/v1/captures?kind=eq.diary&terms_scanned_at=is.null`;
  const r = await fetch(
    `${unscanned}&select=id,corrected_text,final_text&order=created_at.asc&limit=${BATCH}`,
    { headers: H },
  );
  if (!r.ok) return json({ error: `读取日记失败 ${r.status}: ${await r.text()}` }, 500);
  const rows = await r.json() as { id: string; corrected_text: string | null; final_text: string | null }[];

  const profile = await loadProfile(SUPA_URL, H);
  let found = 0;
  let failed = 0;
  for (const row of rows) {
    try {
      // 依次扫，不并发：后一篇要看得到前一篇刚加进来的词，才不会重复报
      found += await scanTerms({
        supaUrl: SUPA_URL, headers: H, userId: user.id, captureId: row.id,
        text: (row.corrected_text ?? row.final_text ?? ""),
        profile, route: routeOf(profile),
      });
    } catch (e) {
      // 一篇失败不拖累其他篇；它没被打标记，下次还会再扫
      failed++;
      console.error("扫描日记失败", row.id, e);
    }
  }

  // 还剩多少篇没扫（用 count 头，不把正文拉回来）
  const c = await fetch(`${unscanned}&select=id`, {
    method: "HEAD", headers: { ...H, Prefer: "count=exact" },
  });
  const remaining = Number(c.headers.get("content-range")?.split("/")[1] ?? 0);

  return json({ ok: true, scanned: rows.length - failed, failed, found, remaining });
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json" },
  });
}

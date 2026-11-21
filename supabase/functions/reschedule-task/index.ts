// 按待办当前的 deadline 重排它的提醒。
//
// 用在「推迟」之后：只改 due_at 不重排的话，新时间点根本不会响 ——
// 旧提醒还挂在原来的时间上，新的 deadline 一个闹钟都没有。
// 以后手动改 deadline 也走这里。

import { planReminders } from "../_shared/reminders.ts";
import { catchUp, nextOccurrence } from "../_shared/recurrence.ts";
import { shiftWallClock } from "../_shared/tz.ts";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "missing_auth" }, 401);

  const H = { apikey: ANON, Authorization: auth };
  const JH = { ...H, "Content-Type": "application/json" };

  // advance = 用户刚完成了这一次，滚到下一次
  // occurrence = 他完成的是哪一次（那一次的 due_at）。闹钟响的那一刻手机已经自动滚过一次了，
  //   之后他再点「完成」，不能再往后滚 —— 不然明天那次就被白白跳过
  // from_tz = 手机刚从这个时区换到 tz：循环待办的「几点」跟着人走
  const { task_id, tz, advance, occurrence, from_tz } = await req.json().catch(() => ({}));
  if (!task_id) return json({ error: "missing_task_id" }, 400);

  const tr = await fetch(
    `${SUPA_URL}/rest/v1/tasks?id=eq.${task_id}&select=id,user_id,due_at,status,wants_reminder,recur_kind,recur_interval,recur_weekdays`,
    { headers: H },
  );
  if (!tr.ok) return json({ error: `读取待办失败 ${tr.status}` }, 500);
  const [task] = await tr.json();
  if (!task) return json({ error: "task_not_found" }, 404);

  // 撤掉还没发出的旧提醒；已经发出的留着，那是历史
  const del = await fetch(
    `${SUPA_URL}/rest/v1/reminders?task_id=eq.${task_id}&delivered_at=is.null`,
    { method: "DELETE", headers: H },
  );
  if (!del.ok) return json({ error: `撤销旧提醒失败 ${del.status}` }, 500);

  const zone = tz ?? "America/Los_Angeles";
  let dueAt: string | null = task.due_at;

  // 循环待办：完成一次就滚到下一次；很久没管的话一次性追到最近一个还没到的时间点
  if (task.recur_kind && dueAt) {
    const rec = {
      kind: task.recur_kind,
      interval: task.recur_interval ?? 1,
      weekdays: task.recur_weekdays ?? null,
    };
    // 换了时区：先把「原来那边的晚上 10 点」搬成「这边的晚上 10 点」，再往后推。
    // 一次性待办不搬 —— 它通常绑着某地的真实时刻（洛杉矶的看诊预约），挪了反而误事
    if (from_tz && from_tz !== zone) dueAt = shiftWallClock(dueAt, from_tz, zone);
    const alreadyPast = typeof occurrence === "string" &&
      new Date(dueAt!).getTime() > new Date(occurrence).getTime() + 60_000;
    dueAt = advance && !alreadyPast
      ? (nextOccurrence(dueAt!, rec, zone) ?? dueAt)
      : catchUp(dueAt!, rec, zone);
    if (dueAt !== task.due_at) {
      await fetch(`${SUPA_URL}/rest/v1/tasks?id=eq.${task_id}`, {
        method: "PATCH", headers: JH, body: JSON.stringify({ due_at: dueAt }),
      });
    }
  }

  // 只是记一下的待办不排提醒 —— 它在列表里照样是待办，只是不会响
  if (task.status !== "open" || !dueAt || !task.wants_reminder) {
    return json({ ok: true, due_at: dueAt, reminders: 0 });
  }

  const plans = planReminders(dueAt, zone);
  if (plans.length) {
    const ins = await fetch(`${SUPA_URL}/rest/v1/reminders`, {
      method: "POST",
      headers: JH,
      body: JSON.stringify(plans.map((p) => ({ user_id: task.user_id, task_id, ...p }))),
    });
    if (!ins.ok) {
      return json({ error: `写入提醒失败 ${ins.status}: ${await ins.text()}` }, 500);
    }
  }
  return json({ ok: true, due_at: dueAt, reminders: plans.length });
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

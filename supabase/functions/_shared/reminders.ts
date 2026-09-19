// 递进式提醒的档位计算（PRD 功能一）。
//
// PRD 给的例子是任务周五截止：
//   周一 本周需要完成 / 周三 还有两天 / 周四 明天截止 / 周五 今天截止
// 抽象成「截止前 N 天」的偏移，对任意 deadline 都成立。

import { localYmd, zonedTime } from "./tz.ts";

export interface ReminderPlan {
  fire_at: string;   // ISO8601
  tier: number;
  body: string;
  style: "notify" | "alarm";
}

// 长周期：截止前若干天的早上 9 点（PRD 功能一给的例子）
const DAY_TIERS = [
  { daysBefore: 4, body: "本周需要完成" },
  { daysBefore: 2, body: "距离 Deadline 还有两天" },
  { daysBefore: 1, body: "明天截止" },
  { daysBefore: 0, body: "今天截止" },
];

// 短周期：截止前若干分钟。
// 没有这一组的话，"今晚八点之前提醒我"会一条提醒都排不出来——
// 因为当天早上 9 点那档在录音时已经过去了。真实录音踩到过这个坑。
const MINUTE_TIERS = [
  { minutesBefore: 120, body: "还有两小时" },
  { minutesBefore: 30, body: "还有半小时" },
];

const REMIND_HOUR = 9; // 本地时间早上 9 点

export function planReminders(
  dueAt: string | null,
  tz: string,
  now: Date = new Date(),
): ReminderPlan[] {
  if (!dueAt) return [];

  const due = new Date(dueAt);
  if (Number.isNaN(due.getTime())) return [];
  if (due.getTime() <= now.getTime()) return []; // 已经过期，排提醒没意义

  const [y, m, d] = localYmd(due, tz);
  const candidates: { at: number; body: string; style: "notify" | "alarm" }[] = [];

  // 日历档：目标日期当天早 9 点
  for (const t of DAY_TIERS) {
    const target = new Date(Date.UTC(y, m - 1, d) - t.daysBefore * 86400000);
    candidates.push({
      at: zonedTime(
        target.getUTCFullYear(), target.getUTCMonth() + 1, target.getUTCDate(),
        REMIND_HOUR, tz,
      ).getTime(),
      body: t.body,
      style: "notify",
    });
  }

  // 分钟档：临近 deadline 的最后冲刺
  for (const t of MINUTE_TIERS) {
    candidates.push({
      at: due.getTime() - t.minutesBefore * 60_000,
      body: t.body,
      style: "notify",
    });
  }

  // deadline 正点那一刻：闹钟级，全屏响铃、必须手动处理。
  // 前面几档只是预告，这一档才是"现在就得做"。
  candidates.push({ at: due.getTime(), body: "到时间了", style: "alarm" });

  // 只保留「现在之后、且不晚于 deadline」的，去重后按时间排序
  const seen = new Set<number>();
  const kept = candidates
    .filter((c) => c.at > now.getTime() && c.at <= due.getTime())
    .filter((c) => !seen.has(c.at) && seen.add(c.at))
    .sort((a, b) => a.at - b.at);

  // 兜底：deadline 在未来却一条都没排上（比如 10 分钟后就截止）。
  // 用户明确要了提醒，绝不能一条不给。
  if (kept.length === 0) {
    const at = Math.min(
      Math.max(now.getTime() + 2 * 60_000, due.getTime() - 15 * 60_000),
      due.getTime(),
    );
    kept.push({ at, body: "马上就要截止了", style: "alarm" });
  }

  return kept.map((c, i) => ({
    fire_at: new Date(c.at).toISOString(),
    tier: i + 1,
    body: c.body,
    style: c.style,
  }));
}

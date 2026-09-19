// 循环待办：算「下一次什么时候」。
//
// 关键点：按**本地墙上时间**推进，而不是简单加 24 小时。
// 加固定毫秒数的话，跨夏令时之后「每天晚上 10 点」会变成 9 点或 11 点。

import { addDays, addMonths, isoWeekday, localParts, zonedTime } from "./tz.ts";

export type RecurKind = "daily" | "weekly" | "monthly";

export interface Recurrence {
  kind: RecurKind;
  interval: number;
  weekdays: number[] | null;   // 1=周一 … 7=周日，仅 weekly 用
}

/**
 * 从 current 这次开始，往后找第一个晚于 after 的发生时间。
 * 时刻（几点几分）保持跟 current 一致。
 */
export function nextOccurrence(
  currentIso: string,
  rec: Recurrence,
  tz: string,
  after: Date = new Date(),
): string | null {
  const cur = new Date(currentIso);
  if (Number.isNaN(cur.getTime())) return null;

  const { hh, mm } = localParts(cur, tz);
  let { y, m, d } = localParts(cur, tz);
  const interval = Math.max(1, rec.interval || 1);
  const weekdays = rec.weekdays?.length ? [...rec.weekdays].sort((a, b) => a - b) : null;

  // 最多往前找两年，避免规则写错时死循环
  for (let i = 0; i < 800; i++) {
    if (rec.kind === "weekly" && weekdays) {
      // 多个星期几时逐天推进，命中集合里的那几天
      ({ y, m, d } = addDays(y, m, d, 1));
      if (!weekdays.includes(isoWeekday(y, m, d))) continue;
    } else if (rec.kind === "weekly") {
      ({ y, m, d } = addDays(y, m, d, 7 * interval));
    } else if (rec.kind === "monthly") {
      ({ y, m, d } = addMonths(y, m, d, interval));
    } else {
      ({ y, m, d } = addDays(y, m, d, interval));
    }
    const at = zonedTime(y, m, d, hh, tz, mm);
    if (at.getTime() > after.getTime()) return at.toISOString();
  }
  return null;
}

/** due_at 已经过去很久时，一次性滚到下一个还没到的时间点 */
export function catchUp(
  currentIso: string, rec: Recurrence, tz: string, now: Date = new Date(),
): string {
  if (new Date(currentIso).getTime() > now.getTime()) return currentIso;
  return nextOccurrence(currentIso, rec, tz, now) ?? currentIso;
}

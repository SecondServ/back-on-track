// 时区计算。提醒档位和循环规则都要用，抽出来避免两份各算各的。

/** 某个瞬间在指定时区的 UTC 偏移（毫秒） */
export function tzOffsetMs(at: Date, tz: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: tz, hour12: false,
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit",
    }).formatToParts(at).map((p) => [p.type, p.value]),
  );
  const asUtc = Date.UTC(
    +parts.year, +parts.month - 1, +parts.day,
    +parts.hour % 24, +parts.minute, +parts.second,
  );
  return asUtc - at.getTime();
}

/** 该瞬间在指定时区的「墙上时间」：年月日时分 */
export function localParts(at: Date, tz: string) {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: tz, hour12: false,
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit",
    }).formatToParts(at).map((x) => [x.type, x.value]),
  );
  return {
    y: +p.year, m: +p.month, d: +p.day,
    hh: +p.hour % 24, mm: +p.minute,
  };
}

export function localYmd(at: Date, tz: string): [number, number, number] {
  const { y, m, d } = localParts(at, tz);
  return [y, m, d];
}

/** 构造「指定时区某年月日的某个时刻」对应的 UTC 瞬间（含 DST 修正） */
export function zonedTime(
  y: number, m: number, d: number, hour: number, tz: string, minute = 0,
): Date {
  const guess = Date.UTC(y, m - 1, d, hour, minute);
  let out = new Date(guess - tzOffsetMs(new Date(guess), tz));
  const off2 = tzOffsetMs(out, tz);           // 跨 DST 边界时再修一次
  const retry = new Date(guess - off2);
  if (retry.getTime() !== out.getTime()) out = retry;
  return out;
}

/** 周几，1=周一 … 7=周日 */
export function isoWeekday(y: number, m: number, d: number): number {
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay(); // 0=周日
  return dow === 0 ? 7 : dow;
}

const DAYS_IN_MONTH = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** 本地日历上加 n 天 */
export function addDays(y: number, m: number, d: number, n: number) {
  const t = new Date(Date.UTC(y, m - 1, d) + n * 86400000);
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

/** 本地日历上加 n 个月，日期超过当月天数就截到月末（1月31日 + 1月 = 2月28日） */
export function addMonths(y: number, m: number, d: number, n: number) {
  const total = (y * 12 + (m - 1)) + n;
  const ny = Math.floor(total / 12);
  const nm = (total % 12) + 1;
  return { y: ny, m: nm, d: Math.min(d, DAYS_IN_MONTH(ny, nm)) };
}

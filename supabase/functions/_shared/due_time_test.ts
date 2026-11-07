import { assertEquals } from "jsr:@std/assert";
import { nowLine, wallToIso } from "./analysis.ts";

// 时区换算的回归测试。
// 2026-09-29 在上海说「五分钟以后提醒我去填 timesheet」，
// 模型把当地 15:00 写成了 15:00Z，due 早了 8 小时、落在过去，一个提醒都没排。

Deno.test("上海：当地墙上时间换算成 UTC", () => {
  assertEquals(wallToIso("2026-09-29T23:00", "Asia/Shanghai"), "2026-09-29T15:00:00.000Z");
});

Deno.test("模型乱贴 Z 也按当地时间算", () => {
  assertEquals(wallToIso("2026-09-29T23:00:00Z", "Asia/Shanghai"), "2026-09-29T15:00:00.000Z");
  assertEquals(wallToIso("2026-09-29T23:00:00+08:00", "Asia/Shanghai"), "2026-09-29T15:00:00.000Z");
});

Deno.test("洛杉矶夏令时 / 冬令时", () => {
  assertEquals(wallToIso("2026-10-18T22:36", "America/Los_Angeles"), "2026-10-19T05:36:00.000Z");
  assertEquals(wallToIso("2026-11-12T11:00", "America/Los_Angeles"), "2026-11-12T19:00:00.000Z");
});

Deno.test("只有日期 → 早上 9 点；解析不了 → null", () => {
  assertEquals(wallToIso("2026-11-15", "America/Los_Angeles"), "2026-11-15T17:00:00.000Z");
  assertEquals(wallToIso("下周五", "America/Los_Angeles"), null);
  assertEquals(wallToIso(null, "America/Los_Angeles"), null);
});

Deno.test("给模型看的是当地时间", () => {
  assertEquals(
    nowLine("2026-09-29T14:55:48.000Z", "Asia/Shanghai"),
    "当前当地时间：2026-09-29 22:55（星期二）\n时区：Asia/Shanghai（UTC+08:00）",
  );
  assertEquals(
    nowLine("2026-11-07T21:00:00.000Z", "America/Los_Angeles"),
    "当前当地时间：2026-11-07 13:00（星期六）\n时区：America/Los_Angeles（UTC-08:00）",
  );
});

Deno.test("换时区：重复提醒的「几点」跟着人走", async () => {
  const { shiftWallClock } = await import("./tz.ts");
  // 洛杉矶晚上 10 点（冬令时 = 次日 06:00Z）→ 上海晚上 10 点（= 当天 14:00Z）
  assertEquals(
    shiftWallClock("2026-11-08T06:00:00.000Z", "America/Los_Angeles", "Asia/Shanghai"),
    "2026-11-07T14:00:00.000Z",
  );
  // 同一时区不变
  assertEquals(
    shiftWallClock("2026-11-08T06:00:00.000Z", "America/Los_Angeles", "America/Los_Angeles"),
    "2026-11-08T06:00:00.000Z",
  );
});

// 饮食记录的标题和版式。放在代码里而不是交给模型：
// 每天都得一模一样，才方便一眼扫过去，以后按天汇总也不用再解析自由文本。

export type MealSlot = "breakfast" | "lunch" | "dinner" | "snack";
export interface MealEntry { slot: MealSlot; foods: string[]; drinks: string[] }

const LABEL: Record<MealSlot, string> = {
  breakfast: "早餐", lunch: "午餐", dinner: "晚餐", snack: "加餐",
};
const ORDER: MealSlot[] = ["breakfast", "lunch", "dinner", "snack"];

/** 「早餐：培根、鸡蛋；喝了咖啡」每顿一行，按一天的先后排 */
export function formatMeal(meal: { slots: MealEntry[] }): string {
  return [...meal.slots]
    .sort((a, b) => ORDER.indexOf(a.slot) - ORDER.indexOf(b.slot))
    .map((s) => {
      const parts: string[] = [];
      if (s.foods.length) parts.push(s.foods.join("、"));
      if (s.drinks.length) parts.push(`喝了${s.drinks.join("、")}`);
      return `${LABEL[s.slot] ?? "其他"}：${parts.join("；") || "（没说具体吃了什么）"}`;
    })
    .join("\n");
}

/** 「9月11日 周四」—— 按用户所在时区，不是服务器的 UTC 日期 */
export function localDateLabel(tz: string, at: Date = new Date()): string {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("zh-CN", {
      timeZone: tz, month: "numeric", day: "numeric", weekday: "short",
    }).formatToParts(at).map((x) => [x.type, x.value]),
  );
  return `${p.month}月${p.day}日 ${p.weekday}`;
}

/**
 * 「9月17日 周三」—— 给定的当地日期（yyyy-MM-dd）。
 * 按 UTC 中午构造再格式化：取零点的话，时区一偏就会退到前一天。
 */
export function dateLabel(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const at = new Date(Date.UTC(y, m - 1, d, 12));
  const wd = new Intl.DateTimeFormat("zh-CN", { timeZone: "UTC", weekday: "short" })
    .format(at);
  return `${m}月${d}日 ${wd}`;
}

/** 「2026-09-11」，写进 meals.logged_on */
export function localDateIso(tz: string, at: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit",
  }).format(at);
}

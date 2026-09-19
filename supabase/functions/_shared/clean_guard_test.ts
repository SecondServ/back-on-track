import { assertEquals } from "jsr:@std/assert";
import { keepCleanedOrRaw } from "./analysis.ts";

// 清洗结果兜底闸的回归测试。
// 「AI 的答案被当成我说的话存下来」这个 bug 复发过一次，靠提示词防不住，
// 所以规则挪进了确定性代码 —— 这里把它钉住，改 cleanText 之前先跑一遍：
//   npx deno test supabase/functions/_shared/clean_guard_test.ts

const raw1 = "呃 那个 我想问一下 北美最受好评的安卓手机有哪几个";
const clean1 = "我想问一下，北美最受好评的安卓手机有哪几个？";

// 2) 模型把问题答了 —— 必须拦下，退回原话
const raw2 = "你觉得 FDE 这个工作有未来吗";
const answer2 = "FDE（Forward Deployed Engineer）在 AI 公司里需求正在增长，" +
  "它结合了工程能力和客户交付，短期内前景不错；但它对沟通和出差的要求较高，" +
  "长期发展取决于你想走技术深度还是客户关系方向。总体来说是有未来的。";

// 3) 中英混说的正常清洗 —— 必须放行
const raw3 = "帮我记一下 明天要给 FTB 打电话 然后 order plan";
const clean3 = "明天要给 FTB 打电话，然后 order plan。";

// 4) 长度没暴涨但内容完全不相干 —— 必须拦下
const raw4 = "现在旧金山的天气怎么样 请联网查一下";
const answer4 = "今天多云转晴，气温十八度左右，傍晚有风。";

// 5) 模型返回空 —— 退回原话

Deno.test("正常清洗要放行", () => {
  assertEquals(keepCleanedOrRaw(raw1, clean1), clean1);
});

Deno.test("答案冒充要拦下", () => {
  assertEquals(keepCleanedOrRaw(raw2, answer2), raw2);
});

Deno.test("中英混说清洗要放行", () => {
  assertEquals(keepCleanedOrRaw(raw3, clean3), clean3);
});

Deno.test("内容不相干要拦下", () => {
  assertEquals(keepCleanedOrRaw(raw4, answer4), raw4);
});

Deno.test("空返回要退回原话", () => {
  assertEquals(keepCleanedOrRaw(raw1, "   "), raw1);
});

import { assert, assertEquals } from "jsr:@std/assert";
import { chunkText } from "./terms.ts";
import { profileForLLM, type Profile } from "./profile.ts";

Deno.test("长日记切段：不丢字，每段不超长", () => {
  const text = ("今天跟小张吃饭。\n".repeat(900));
  const parts = chunkText(text, 5000);
  assert(parts.length >= 2);
  assertEquals(parts.join(""), text);
  for (const p of parts) assert(p.length <= 5000);
});

Deno.test("短日记不切", () => {
  assertEquals(chunkText("一句话"), ["一句话"]);
});

Deno.test("通讯录：同音两个人都在，不写「一律改成」", () => {
  const p: Profile = {
    body: "", glossary: [], route: "default",
    terms: [
      { term: "小张", heard: "小章", category: "person", full_name: "张伟", relation: "同事" },
      { term: "小章", heard: null, category: "person", full_name: null, relation: "大学室友" },
      { term: "SeatGeek", heard: "SeatGeek", category: "other", full_name: null, relation: null },
    ],
  };
  const s = profileForLLM(p)!;
  assert(s.includes("- 小张（张伟；同事）"), s);
  assert(s.includes("- 小章（大学室友）"), s);
  assert(s.includes("- SeatGeek\n") || s.endsWith("- SeatGeek"), s);
  // 「小章」本身是另一个人，不能当成小张的错写提示
  assert(!s.includes("听成「小章」"), s);
  assert(!s.includes("一律"), s);
});

Deno.test("同一个人好几种写法：合成一条，写法全带上", () => {
  const p: Profile = {
    body: "", glossary: [], route: "default",
    terms: [
      { term: "侯丁豪", heard: "侯迪豪", category: "person", full_name: null, relation: "好兄弟" },
      { term: "侯丁豪", heard: "洪钧豪", category: "person", full_name: null, relation: "好朋友" },
      { term: "侯丁豪", heard: "侯迪豪", category: "person", full_name: null, relation: null },
      { term: "侯丁豪", heard: "侯定豪", category: "person", full_name: null, relation: null },
    ],
  };
  const s = profileForLLM(p)!;
  assertEquals(s.split("- 侯丁豪").length - 1, 1, s);
  assert(s.includes("- 侯丁豪（好兄弟；转写曾听成「侯迪豪」「洪钧豪」「侯定豪」）"), s);
});

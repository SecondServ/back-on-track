-- 档案有两个来源：用户自己写的，和 AI 从日常记录里攒出来的。
--
-- 分成两列而不是合成一段，是因为它们的权威性不一样：
-- 用户写的是事实，AI 攒的是推断。混在一起的话，AI 下次整理就会把
-- 用户亲手写的东西一并改写掉 —— 那是不可接受的。
-- 导出 .md 时再把两部分拼起来，各自标好出处。
alter table user_profile
  add column if not exists ai_body       text not null default '',
  add column if not exists ai_updated_at timestamptz;

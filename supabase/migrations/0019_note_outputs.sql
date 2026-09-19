-- 对着一条笔记下达的指令，以及 AI 给回来的结果。
--
-- **单独一张表，绝不写回 captures.corrected_text** —— 那是用户自己说的话，
-- AI 的产出再有用也不该混进去、更不该覆盖它。分开存之后：
--   · 原话永远是原话，随时能对照
--   · 一条笔记可以反复下指令（整理一次、再让它调研一次），结果按时间叠着看
--   · 某条结果不满意，单独删掉就行，不会碰到正文
--
-- 跟 qa_turns 的区别：那边是一问一答的对话，两边都是「消息」；
-- 这边每行是「一条指令 + 它的产出」，成对存在，所以放一行里。
create table if not exists note_outputs (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  capture_id  uuid not null references captures(id) on delete cascade,
  -- 用户口头说的那句指令（语音转写而来），原样留着 ——
  -- 过几天回看时，「AI 为什么写了这些」全靠它解释
  instruction text not null,
  text        text not null,
  created_at  timestamptz not null default now()
);

create index if not exists note_outputs_capture_idx
  on note_outputs (capture_id, created_at);

alter table note_outputs enable row level security;
drop policy if exists note_outputs_owner on note_outputs;
create policy note_outputs_owner on note_outputs for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

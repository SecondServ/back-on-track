-- 「问问题」笔记里的一问一答。
--
-- 为什么不复用 capture_segments：那张表的每一行都对应一段**录音**，
-- 而这里一半的行是 AI 生成的文字，压根没有音频。混在一起会让
-- 「这条有没有音频」这个判断到处都要打补丁。
--
-- 也不把对话塞进 captures.corrected_text：一问一答是会长的，
-- 而且要能按轮次渲染成气泡，拼成一整段字符串就没法做了。
create table if not exists qa_turns (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  capture_id uuid not null references captures(id) on delete cascade,
  -- user = 我问的（语音转写而来）；assistant = AI 答的
  role       text not null check (role in ('user', 'assistant')),
  text       text not null,
  created_at timestamptz not null default now()
);

create index if not exists qa_turns_capture_idx
  on qa_turns (capture_id, created_at);

alter table qa_turns enable row level security;
drop policy if exists qa_turns_owner on qa_turns;
create policy qa_turns_owner on qa_turns for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

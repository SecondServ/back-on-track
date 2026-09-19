-- 用户档案：让 AI 认识说话的这个人。
--
-- 解决的是「说到名字和地点就识别错」—— 这个错在 ASR 那一步就发生了，
-- 靠后面的 LLM 纠错是补不回来的（它看不到音频，只能猜）。
-- 所以拆成两块，喂给管线的不同环节：
--   glossary —— 专有名词表，短，塞进 ASR 的 prompt 参数做「听写偏置」
--   body     —— 自由文字，长，进 LLM 的 system prompt 做背景理解
-- 分开是因为 ASR 的 prompt 只有约 224 token 的预算，
-- 把长档案整段塞进去会被静默截断，等于白写。
create table if not exists user_profile (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  body       text not null default '',
  glossary   text[] not null default '{}',
  updated_at timestamptz not null default now()
);

alter table user_profile enable row level security;
drop policy if exists user_profile_owner on user_profile;
create policy user_profile_owner on user_profile for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

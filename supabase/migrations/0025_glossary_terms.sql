-- 名词校对表：日记里冒出来的人名、地名、少见词，等他确认标准写法。
--
-- 一行 = 一条「听到的样子 → 标准写法」：
--   heard  正文里实际转出来的写法（比如「小章」）
--   term   标准写法。AI 先猜一个；他确认或改过之后就是定论（比如「小张」）
--
-- 为什么不直接塞进 user_profile.glossary：那是他手写的一串词，没有「待确认」这个状态，
-- 也记不住「它常被听成什么」。确认过的词在服务端读出来，跟手写的那串合并着用。
create table if not exists glossary_terms (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  term         text not null,
  heard        text,
  category     text not null default 'other' check (category in ('person', 'place', 'other')),
  context      text,
  capture_id   uuid references captures(id) on delete set null,
  status       text not null default 'pending'
               check (status in ('pending', 'confirmed', 'ignored')),
  created_at   timestamptz not null default now(),
  confirmed_at timestamptz
);

create index if not exists glossary_terms_user_status on glossary_terms (user_id, status);

alter table glossary_terms enable row level security;
drop policy if exists glossary_terms_owner on glossary_terms;
create policy glossary_terms_owner on glossary_terms for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

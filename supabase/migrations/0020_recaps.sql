-- 月度回顾的缓存。
--
-- 为什么要缓存：一次回顾要把整月的笔记喂给模型，慢（十几秒）且花钱。
-- 而这个页面的用法是「每天打开看一眼」—— 不缓存的话每天都在重复烧同一笔钱。
-- 所以：生成一次存下来，想要新的再手动重算。
--
-- data 用 jsonb 而不是拆成列：三个板块（饮食/灵感/待办）的结构各不相同，
-- 而且大概率还会改。拆成列意味着每调一次结构就要一次迁移。
create table if not exists recaps (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  -- 'YYYY-MM'，按用户本地时区切分的月份
  month        text not null,
  section      text not null check (section in ('meal', 'ideas', 'tasks')),
  data         jsonb not null,
  generated_at timestamptz not null default now(),
  unique (user_id, month, section)
);

alter table recaps enable row level security;
drop policy if exists recaps_owner on recaps;
create policy recaps_owner on recaps for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

-- 循环待办的完成日志：每一次「完成」或「跳过」记一条。
--
-- 为什么单独一张表而不是往记录里插卡片：一个每日待办一个月就是 30 条，
-- 会把记录页淹掉；而完成"记录今天吃了什么"这件事本身就会产生一条饮食记录，
-- 再加一条「已完成」纯属重复。
create table if not exists task_occurrences (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  task_id     uuid not null references tasks(id) on delete cascade,
  occurred_at timestamptz not null default now(),
  outcome     text not null check (outcome in ('done', 'skipped')),
  created_at  timestamptz not null default now()
);

create index if not exists task_occurrences_task_idx
  on task_occurrences (task_id, occurred_at desc);

alter table task_occurrences enable row level security;
drop policy if exists task_occurrences_owner on task_occurrences;
create policy task_occurrences_owner on task_occurrences for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

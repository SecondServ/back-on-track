-- 待办也能删了。
--
-- 用软删除而不是真删：循环待办身上挂着 task_occurrences（每次完成/跳过的打卡，
-- 是算完成率的原始数据），真删会级联掉整段历史。误删一条循环待办
-- 等于抹掉几个月的打卡记录，这个代价不能接受。
--
-- 记录页早就是这么做的（captures.deleted_at），这里保持一致。
alter table tasks
  add column if not exists deleted_at timestamptz;

create index if not exists tasks_alive_idx
  on tasks (user_id, status) where deleted_at is null;

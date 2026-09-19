-- 提醒事项是待办的子集：有的待办只是记一下要做什么，不需要到点响铃。
-- 这个意图 AI 本来就在判断（needs_reminder），但之前用完就丢了 ——
-- 只在处理时决定要不要建提醒，没存下来，所以界面上区分不出来、用户也改不了。
alter table tasks add column if not exists wants_reminder boolean not null default false;

-- 已有数据回填：排过提醒的，就是提醒事项
update tasks t set wants_reminder = true
where wants_reminder = false
  and exists (select 1 from reminders r where r.task_id = t.id);

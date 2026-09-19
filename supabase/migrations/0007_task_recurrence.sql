-- 循环待办（"每天晚上10点记录吃了什么"）。
-- due_at 始终存「下一次」的时间点，循环规则单独存；
-- 这样提醒逻辑完全不用改 —— 它只认 due_at。
alter table tasks
  add column if not exists recur_kind text
    check (recur_kind is null or recur_kind in ('daily','weekly','monthly')),
  add column if not exists recur_interval int not null default 1,
  add column if not exists recur_weekdays int[];

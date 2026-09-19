-- 每条待办自己的「多久催我一次」。
--
-- 原来是一个全局设置：每 7 天把所有没做完的待办列一遍。但「办张手机卡」
-- 和「剪一个纪录片」本来就不该用同一个节奏催 —— 前者一周提一次就烦了，
-- 后者可能一个月提一次才合适。
--
-- nudge_days: null = 用设置里的默认值；0 = 这条永远不催；N = 每 N 天
-- last_nudged_at: 上次催是什么时候，用来判断这条到没到点（每条各自计时）
alter table tasks
  add column if not exists nudge_days     int,
  add column if not exists last_nudged_at timestamptz;

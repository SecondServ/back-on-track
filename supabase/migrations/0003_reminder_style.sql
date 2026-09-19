-- 提醒分两种强度：
--   notify —— 普通通知，deadline 之前的递进提醒用
--   alarm  —— 闹钟级，全屏 + 响铃 + 必须手动处理，只用在 deadline 正点那一刻
--
-- 混成一种的问题是：真正到点的那次会被前面几次预告淹没，
-- 用户看多了就麻木，等于没有提醒。
alter table reminders
  add column if not exists style text not null default 'notify'
  check (style in ('notify', 'alarm'));

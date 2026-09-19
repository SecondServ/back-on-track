-- 「说的是哪天的事」——跟「什么时候说的」分开。
--
-- created_at 是录音时刻，改不得（补传、排序、垃圾箱都靠它）。
-- 但饮食和日记经常是**第二天补记**的：昨天忘了记饮食，今天早上才说。
-- 用 created_at 当归属日期，这一条就会落在今天，热力图和日历全部串位。
--
-- 空 = 就是录音当天。只有补记或用户手动改过的才有值。
alter table captures add column if not exists logged_on date;
create index if not exists captures_logged_on_idx on captures (user_id, logged_on);

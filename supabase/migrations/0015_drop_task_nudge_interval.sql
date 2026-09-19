-- 撤掉「每条待办各自的催促频率」。
--
-- 它要求语音里能区分「这件事每天都要做」（循环待办）和「这件事催我勤一点」
-- （推送频率）—— 两句话长得太像，模型稳定地把后者理解成闹钟提醒。
-- 与其不断往 prompt 里加规则去掰，不如把这个字段拿掉：歧义的来源没有了，
-- 频率回到设置里一个全局默认值。
--
-- 这两列加上还不到一小时、没有任何数据，直接删掉，不留休眠的 schema 债。
alter table tasks
  drop column if exists nudge_days,
  drop column if exists last_nudged_at;

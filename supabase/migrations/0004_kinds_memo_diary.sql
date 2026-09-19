-- 分类调整（2026-09）
--   新增 memo（备忘）、diary（日记）
--   旧的 note（笔记）拆成这两类；project 从未产生过数据，不再使用
-- Postgres 枚举不能删值，note / project 留在类型里，只是不再写入。
alter type capture_kind add value if not exists 'memo';
alter type capture_kind add value if not exists 'diary';

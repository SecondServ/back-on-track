-- 新的笔记类型：问问题。
--
-- 单独放一个文件：ALTER TYPE ... ADD VALUE 在同一个事务里新增并使用这个值会报错，
-- 分开跑最稳妥。
alter type capture_kind add value if not exists 'ask';

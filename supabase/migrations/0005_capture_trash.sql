-- 记录的软删除：左滑删除先进垃圾箱，30 天内可恢复，
-- 过期后由 App 打开时顺手永久清除（连同录音文件）。
alter table captures add column if not exists deleted_at timestamptz;
create index if not exists captures_trash_idx
  on captures (user_id, deleted_at) where deleted_at is not null;

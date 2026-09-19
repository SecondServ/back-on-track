-- AI 从日记 / 灵感 / 备忘里「顺手」提取出来的待办，必须经用户点头才算数。
--
-- 之前只要模型在任何一段话里识别出「要做的事」，就直接建待办并跟着排提醒 ——
-- 一句随口的日记足以在半夜把人闹醒，而用户从没说过那是待办。
--
-- 默认 true：用户自己指定的（右滑到「待办」）、以及整段话本身就是在交代一件事
-- （kind=task）照旧立即生效。只有「其他类型的笔记里顺带提到的」才写 false，
-- 在用户确认之前既不进待办列表，也绝不排提醒。
alter table tasks
  add column if not exists approved boolean not null default true;

create index if not exists tasks_pending_approval_idx
  on tasks (user_id, created_at desc) where approved = false;

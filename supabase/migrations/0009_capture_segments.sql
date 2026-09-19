-- 记录可以继续编辑：追加语音，或者直接改文字。
--
-- 原来一条记录只有一个 audio_path，放不下后续追加的语音。
-- 第一段仍然留在 captures.audio_path（不迁移历史数据），
-- 追加的存这张表，界面上拼成一个列表。
create table if not exists capture_segments (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  capture_id     uuid not null references captures(id) on delete cascade,
  audio_path     text,
  duration_ms    int,
  final_text     text,          -- ASR 原文
  corrected_text text,          -- 去口癖后的
  asr_status     asr_status not null default 'pending',
  asr_error      text,
  created_at     timestamptz not null default now()
);

create index if not exists capture_segments_idx
  on capture_segments (capture_id, created_at);

alter table capture_segments enable row level security;
drop policy if exists capture_segments_owner on capture_segments;
create policy capture_segments_owner on capture_segments for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

-- 用户手动改过正文之后，重新处理这条语音时不要覆盖掉他的编辑
alter table captures
  add column if not exists text_is_manual boolean not null default false;

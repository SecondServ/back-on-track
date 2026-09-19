-- Back on Track — 初始 schema
-- 设计原则：
--   1. captures 是唯一入口表，原始音频与转录永不删除（PRD 3.2 硬要求）
--   2. 下游表一律回指 capture_id，随时能回到 Raw
--   3. 一段语音可产生多条 task —— 一对多，不是一对一
--   4. 虽然是自用，从第一天带 user_id + RLS，将来发布不必重构

create extension if not exists "pgcrypto";

-- ── 入口表 ────────────────────────────────────────────────────────────────

create type capture_kind as enum
  ('task', 'content', 'idea', 'note', 'project', 'meal', 'inbox');

create type asr_status as enum
  ('pending', 'transcribing', 'done', 'failed');

create table captures (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  audio_path      text,                    -- Storage 对象路径；纯文本输入时为 null
  duration_ms     int,
  draft_text      text,                    -- 端上草稿（当前不用，留给将来）
  final_text      text,                    -- 服务端 ASR 定稿
  corrected_text  text,                    -- Claude 纠错后
  summary         text,                    -- 一句话摘要，列表展示用
  language        text,                    -- zh | en | mixed
  asr_status      asr_status not null default 'pending',
  asr_error       text,
  kind            capture_kind,
  kind_confidence real,
  kind_is_manual  boolean not null default false,   -- 用户手动改过类，喂回 prompt 做少样本
  source          text not null default 'android',  -- android | chrome | ios
  client_tz       text,                    -- IANA tz，用于解析"下周""周五"
  created_at      timestamptz not null default now()
);

create index captures_user_created_idx on captures (user_id, created_at desc);
create index captures_kind_idx         on captures (user_id, kind, created_at desc);
create index captures_pending_idx      on captures (asr_status) where asr_status <> 'done';

-- ── 待办 ──────────────────────────────────────────────────────────────────

create type task_status   as enum ('open', 'done', 'dropped');
create type task_priority as enum ('low', 'medium', 'high');

create table tasks (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  capture_id    uuid references captures(id) on delete set null,
  title         text not null,
  due_at        timestamptz,
  due_is_fuzzy  boolean not null default false,  -- "下周"解析出来的，可再问用户
  priority      task_priority not null default 'medium',
  status        task_status   not null default 'open',
  snooze_count  int not null default 0,          -- 推迟次数，触发"要不要重新安排"
  last_snoozed_at timestamptz,
  completed_at  timestamptz,
  created_at    timestamptz not null default now()
);

create index tasks_open_idx on tasks (user_id, status, due_at)
  where status = 'open';

-- 递进式提醒：一个 task 多条，tier 表示第几档（周一/周三/周四/周五）
create table reminders (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references auth.users(id) on delete cascade,
  task_id      uuid not null references tasks(id) on delete cascade,
  fire_at      timestamptz not null,
  tier         smallint not null,
  body         text,
  delivered_at timestamptz,
  created_at   timestamptz not null default now()
);

create index reminders_pending_idx on reminders (user_id, fire_at)
  where delivered_at is null;

-- ── 灵感 ──────────────────────────────────────────────────────────────────

create table ideas (
  id                  uuid primary key default gen_random_uuid(),
  user_id             uuid not null references auth.users(id) on delete cascade,
  capture_id          uuid references captures(id) on delete set null,
  raw_text            text not null,   -- 永远保留原始表达（PRD 3.2）
  organized           jsonb,           -- {summary, problem, solution, target_user, key_features[]}
  analysis            jsonb,           -- 深度分析，用户显式触发才生成
  analyzed_at         timestamptz,
  last_touched_at     timestamptz not null default now(),
  dormant_notified_at timestamptz,     -- 沉睡提醒，每月底最多一次
  created_at          timestamptz not null default now()
);

create index ideas_dormant_idx on ideas (user_id, last_touched_at);

-- ── 内容创作 ──────────────────────────────────────────────────────────────

create table content_pieces (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  capture_id    uuid references captures(id) on delete set null,
  angle         text,             -- 核心观点，一句话
  clean_text    text,             -- 去口癖后的干净转录
  polished_text text,             -- 中度润色，按需生成
  script_text   text,             -- 口播稿，按需生成
  polish_level  text,             -- light | medium | heavy
  titles        text[],
  hooks         text[],
  created_at    timestamptz not null default now()
);

-- ── 笔记 / 项目 ───────────────────────────────────────────────────────────

create table notes (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  capture_id uuid references captures(id) on delete set null,
  body       text not null,
  tags       text[],
  created_at timestamptz not null default now()
);

create table projects (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  name       text not null,
  status     text not null default 'active',
  created_at timestamptz not null default now()
);

-- ── 饮食 ──────────────────────────────────────────────────────────────────

create table meals (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references auth.users(id) on delete cascade,
  capture_id uuid references captures(id) on delete set null,
  items      jsonb,        -- [{name, qty, kcal_est}]
  kcal_est   int,
  photo_path text,
  logged_on  date not null default current_date,
  created_at timestamptz not null default now()
);

create index meals_day_idx on meals (user_id, logged_on);

create table weights (
  id        uuid primary key default gen_random_uuid(),
  user_id   uuid not null references auth.users(id) on delete cascade,
  logged_on date not null default current_date,
  kg        numeric(5,2) not null,
  unique (user_id, logged_on)
);

-- ── 人格记录 ──────────────────────────────────────────────────────────────
-- 注意：默认端上优先，此表仅在用户显式开启同步时才写入。
-- 服务端只做无状态推理，不留存原文。

create table persona_doc (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references auth.users(id) on delete cascade,
  markdown         text not null,
  version          int not null default 1,
  excluded_topics  text[],          -- 用户勾掉的、不计入人格的主题
  sync_enabled     boolean not null default false,
  updated_at       timestamptz not null default now(),
  unique (user_id, version)
);

-- ── RLS ───────────────────────────────────────────────────────────────────
-- 每张表同一条策略：只能读写自己的行。

do $$
declare t text;
begin
  foreach t in array array[
    'captures','tasks','reminders','ideas','content_pieces',
    'notes','projects','meals','weights','persona_doc'
  ]
  loop
    execute format('alter table %I enable row level security', t);
    execute format(
      'create policy %I on %I for all to authenticated
         using (user_id = (select auth.uid()))
         with check (user_id = (select auth.uid()))',
      t || '_owner', t);
  end loop;
end
$$;

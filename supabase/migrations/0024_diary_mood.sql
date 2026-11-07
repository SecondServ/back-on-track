-- 日记的心情，五档：5 开心 / 4 不错 / 3 平和 / 2 低落 / 1 难过。
--
-- AI 记完日记顺手推断一次；推断不对他可以在日记页顶部自己改。
-- mood_is_manual：他亲手改过的，之后任何自动推断（重新处理、补算旧日记）都不许覆盖。
alter table captures
  add column if not exists mood smallint check (mood between 1 and 5),
  add column if not exists mood_is_manual boolean not null default false;

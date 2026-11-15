-- 名词校对 → 通讯录：每个人、地方、事物多两栏「全名」「关系 / 说明」，都可以不填。
--
-- 这些信息不是拿来做「A 一律换成 B」的替换规则，而是给 AI 的背景：
-- 同音的「小张（同事）」和「小章（室友）」都在通讯录里时，AI 看上下文分辨说的是谁。
--
-- relation_is_guess：关系是 AI 从日记原文里摘出来的（比如「Roger，是我校友」），
-- 还没经过他确认。界面上会标成「AI 猜的」。
alter table glossary_terms
  add column if not exists full_name text,
  add column if not exists relation text,
  add column if not exists relation_is_guess boolean not null default false;

-- 「事物」也是通讯录的一部分（公司、App、项目……），沿用 other 这个值，界面上叫「事物」。

-- 日记扫没扫过名词。扫过的「扫描以前的日记」不再重复扫、不重复花钱
alter table captures
  add column if not exists terms_scanned_at timestamptz;

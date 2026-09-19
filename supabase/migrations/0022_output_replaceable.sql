-- 这条产出能不能拿去覆盖原文。
--
-- 只有「整理 / 美化 / 翻译」这类**改写型**的可以 —— 它们的产出仍然是「这篇笔记」，
-- 只是换了个写法。而「研究」产出的是一份报告，把原话覆盖掉就等于把你说的话弄丢了。
--
-- 判断放在服务端：那里同时知道动作和笔记分类（同一个「extra」按钮，
-- 在选题里是翻译、在灵感里是调研），规则留在一处才不会两边打架。
alter table note_outputs add column if not exists replaceable boolean not null default false;

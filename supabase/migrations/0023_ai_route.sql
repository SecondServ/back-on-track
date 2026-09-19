-- 用哪条「线路」跑 AI。
--
-- 为什么存在数据库而不是环境变量：环境变量只有开发者能改（还得重新部署）。
-- 而他出门在外可能联系不上开发者 —— 线路必须是他自己在手机上点得动的东西。
--
--   default = OpenAI（Whisper 转写 + GPT 文字），功能最全，支持联网搜索
--   china   = DeepSeek（文字）+ 硅基流动 SenseVoice（转写），备用通道
--
-- key 仍然只在 Supabase secrets 里，客户端永远拿不到 —— 这里存的只是「用哪套」。
alter table user_profile
  add column if not exists ai_route text not null default 'default'
  check (ai_route in ('default', 'china'));

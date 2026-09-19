-- 让 user_id 自己填上。
-- 否则客户端为了新建这一行，必须先知道自己的 user id —— 平白多一次取用户信息的往返，
-- 还要把 id 明文带在请求体里。RLS 的 with check 仍然保证填的只能是自己。
alter table user_profile alter column user_id set default auth.uid();

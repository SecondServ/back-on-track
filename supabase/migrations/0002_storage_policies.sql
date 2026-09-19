-- Storage 的权限规则
--
-- 补 0001 的疏漏：数据表的 RLS 和 Storage 的 RLS 是两套独立系统，
-- 0001 只配了前者，桶是敞着的（实际表现为谁都传不上去，因为默认拒绝）。
--
-- 路径约定：captures/{user_id}/{capture_id}.m4a
-- 第一层文件夹名就是 user id，规则靠它判断归属。
-- 客户端上传时必须按这个格式拼路径，否则会被拒。

-- 确保是私有桶（公开桶等于谁拿到链接都能听你的录音）
update storage.buckets set public = false where id = 'captures';

-- 只能读自己文件夹里的
create policy "captures_own_select"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'captures'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

-- 只能往自己文件夹里传
create policy "captures_own_insert"
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'captures'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

-- 只能删自己的
create policy "captures_own_delete"
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'captures'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

package com.hector.backontrack

/**
 * anon key 是「公开安全」的 —— 它本来就要随 App 分发。
 * 真正的安全靠数据库那层的 RLS（每行数据只有主人能读写）。
 * 会花钱的 key（OpenAI / Anthropic）只存在 Supabase secrets 里，绝不进 APK。
 */
object Config {
    const val SUPABASE_URL = "https://zezoyztdjrpymerxjjog.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inplem95enRkanJweW1lcnhqam9nIiwicm9sZSI6ImFub24i" +
        "LCJpYXQiOjE3ODg4OTg0ODksImV4cCI6MjEwNDQ3NDQ4OX0." +
        "SEVNgzg2eVfsQJCHKe0OnV_RdSOph5EMdW4mTiPdvDU"

    const val AUDIO_BUCKET = "captures"
}

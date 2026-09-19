#!/usr/bin/env bash
# 一键把后端配好：登录 → 关联项目 → 写入密钥 → 部署函数
#
# key 用隐藏输入，不显示在屏幕上、不进 shell 历史、不落盘（临时文件用完即删）。
set -euo pipefail

cd "$(dirname "$0")/.."
REF=zezoyztdjrpymerxjjog

echo
echo "═══ 1/4  登录 Supabase ═══"
echo "会打开浏览器，授权后回来。已登录的话会直接跳过。"
npx supabase login || true

echo
echo "═══ 2/4  关联项目 ═══"
echo "接下来会问数据库密码 —— 就是你建 Supabase 项目时保存的那个。"
npx supabase link --project-ref "$REF"

echo
echo "═══ 3/4  写入密钥 ═══"
echo "粘贴时屏幕上不会显示任何字符，这是正常的。粘完直接回车。"
echo

read -rsp "Anthropic API Key (sk-ant-...): " ANTHROPIC_KEY; echo
read -rsp "OpenAI API Key (sk-proj-... 或 sk-...): " OPENAI_KEY; echo

if [[ -z "$ANTHROPIC_KEY" || -z "$OPENAI_KEY" ]]; then
  echo "❌ 有 key 是空的，中止。"; exit 1
fi

# 不走命令行参数 —— 那样 key 会短暂出现在系统进程列表里
ENVFILE=$(mktemp)
chmod 600 "$ENVFILE"
trap 'rm -f "$ENVFILE"' EXIT

cat > "$ENVFILE" <<EOF
ANTHROPIC_API_KEY=$ANTHROPIC_KEY
ASR_PROVIDER=openai
ASR_API_KEY=$OPENAI_KEY
EOF

npx supabase secrets set --env-file "$ENVFILE"
rm -f "$ENVFILE"
echo "✅ 密钥已写入 Supabase（临时文件已删除）"

echo
echo "═══ 4/4  部署函数 ═══"
npx supabase functions deploy process-capture

echo
echo "🎉 后端配好了。回去告诉 Claude，他会验证函数是不是活的。"

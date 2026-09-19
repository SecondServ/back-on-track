#!/usr/bin/env bash
# ASR 横评。key 走隐藏输入，不显示、不进历史。
#   bash tools/asr-bench.sh test-audio/sample-01.m4a
set -euo pipefail
cd "$(dirname "$0")/.."

AUDIO="${1:-test-audio/sample-01.m4a}"
[ -f "$AUDIO" ] || { echo "❌ 找不到文件: $AUDIO"; exit 1; }

echo "只填你有的，没有的直接回车跳过。粘贴时不显示字符是正常的。"
echo
read -rsp "OpenAI key   (sk-...，回车跳过): " OPENAI_API_KEY;   echo
read -rsp "Deepgram key (回车跳过): "        DEEPGRAM_API_KEY; echo
export OPENAI_API_KEY DEEPGRAM_API_KEY

node tools/asr-bench.mjs "$AUDIO"

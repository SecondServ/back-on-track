#!/usr/bin/env bash
# 端到端验证。密码走隐藏输入，不显示、不进历史。
#   bash tools/e2e-test.sh ~/Desktop/录音.m4a
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 1 ] || { echo "用法: bash tools/e2e-test.sh <音频文件>"; exit 1; }
[ -f "$1" ] || { echo "❌ 找不到文件: $1"; exit 1; }

read -rp  "测试账号邮箱: " TEST_EMAIL
read -rsp "密码（不显示）: " TEST_PASSWORD; echo
export TEST_EMAIL TEST_PASSWORD
node tools/e2e-test.mjs "$1"

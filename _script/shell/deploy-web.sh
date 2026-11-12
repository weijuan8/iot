#!/bin/bash
# =============================================
# 前端打包上传脚本
# 打包 enjoy-web 静态文件，上传到服务器
# =============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_FILE="${SCRIPT_DIR}/id_ed25519"
WEB_DIR="/Users/weitianyuan/yuange/project/shihe/enjoy-iot/enjoy-web"
DIST_DIR="${WEB_DIR}/dist-test"

SSH_OPT="-o StrictHostKeyChecking=no -p 22 -i ${KEY_FILE}"

GREEN='\033[0;32m'
NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }

# ==========================================
# Step 1: 打包
# ==========================================
info "===== Step 1: 前端打包 ====="
cd "$WEB_DIR"
npm run build:test

if [ ! -d "$DIST_DIR" ]; then
    echo "[ERROR] 打包失败，dist-test 目录不存在！"
    exit 1
fi

info "打包完成 ($(du -sh $DIST_DIR | cut -f1))"

# ==========================================
# Step 2: 上传到服务器
# ==========================================
info "===== Step 2: 上传到服务器 ====="
# 确保远程目录存在
ssh ${SSH_OPT} root@119.45.251.129 "mkdir -p /shihe_back/enjoy-iot/enjoy-web"
rsync -avz --progress \
    -e "ssh ${SSH_OPT}" \
    --delete \
    "$DIST_DIR/" \
    root@119.45.251.129:/shihe_back/enjoy-iot/enjoy-web/

info "===== 完成 ====="
info "测试环境前端已部署到 /shihe_back/enjoy-iot/enjoy-web/"
info "后端 API 域名: https://test-admin-api.paizhi-ai.com"

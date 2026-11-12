#!/bin/bash
# =============================================
# 正式环境部署脚本
# 用法: ./deploy-prod.sh         默认：复制最新测试 jar + 备份正式 jar + 上传正式配置 + 重启
#       ./deploy-prod.sh -reload 仅重启远程服务（跳过复制、上传、备份）
# 说明:
#   - 正式 jar 复用测试环境最新构建产物，不再单独 Maven 打包（保证测试/正式代码一致）
#   - 部署前会自动备份当前正式 jar 到 /shihe_back/backup/，最多保留 10 份
#   - 备份命名: enjoy-iot-prod_YYYYmmdd_HHMMSS.jar
#   - 推荐流程: 先 ./deploy.sh 部署测试 → 验证 → 再 ./deploy-prod.sh 发布正式
# =============================================

set -e

MODE="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_FILE="${SCRIPT_DIR}/id_ed25519"
SSH_OPT="-o StrictHostKeyChecking=no -p 22 -i ${KEY_FILE}"

PROJECT_DIR="/Users/weitianyuan/yuange/project/shihe/enjoy-iot"

GREEN='\033[0;32m'
NC='\033[0m'
info() { echo -e "${GREEN}[INFO]${NC} $1"; }

# ==========================================
# -reload: 仅重启正式服务（不做任何复制/备份/上传）
# ==========================================
if [ "$MODE" = "-reload" ]; then
    info "模式: 仅重启远程服务"

    info "===== 远程重启正式服务 ====="
    ssh ${SSH_OPT} root@119.45.251.129 << 'REMOTE'
set -e

SERVER_DIR="/shihe_back/enjoy-iot/prod-server"
LOG_FILE="${SERVER_DIR}/app-prod.log"

JAR_PATH=$(find "$SERVER_DIR" -maxdepth 1 -name "server.jar" -type f | head -1)
if [ -z "$JAR_PATH" ]; then
    echo "[ERROR] 未找到 server.jar！"
    exit 1
fi

echo "[INFO] 停止旧服务..."
screen -S enjoy-iot-prod -X quit 2>/dev/null && echo "[INFO] screen 会话已关闭" || echo "[INFO] 无 screen 会话"
pkill -f "/shihe_back/enjoy-iot/prod-server/server.jar" 2>/dev/null && echo "[INFO] Java 进程已停止" || echo "[INFO] 无运行进程"
sleep 2

echo "[INFO] 启动: $(basename "$JAR_PATH")"
cd "$SERVER_DIR"
nohup java -Xms512m -Xmx1024m \
    -XX:MaxMetaspaceSize=256m \
    -XX:MaxDirectMemorySize=256m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="$SERVER_DIR/" \
    -Djava.security.egd=file:/dev/./urandom \
    -jar "$JAR_PATH" \
    --spring.profiles.active=prod \
    > "$LOG_FILE" 2>&1 &

echo "[INFO] 等待服务启动..."
sleep 5
echo ""
echo "==================== 启动日志 ===================="
tail -50 "$LOG_FILE"
echo "=================================================="
echo ""
echo "[INFO] 重启完成！"
echo "[INFO] 查看日志: ssh root@119.45.251.129 'tail -f ${LOG_FILE}'"
echo "[INFO] 访问地址: http://119.45.251.129:30011"
REMOTE

    info "===== 正式重启完成 ====="
    exit 0
fi

# ==========================================
# 默认模式: 复制 + 备份 + 上传配置 + 重启
# ==========================================
info "模式: 全部（复制测试 jar + 备份正式 jar + 上传正式配置 + 重启）"

# ==========================================
# Step 1: 上传正式环境配置文件
# ==========================================
info "===== Step 1: 上传正式环境配置文件 ====="
LOCAL_CONF="${PROJECT_DIR}/server/src/main/resources/application-prod.yml"
if [ ! -f "$LOCAL_CONF" ]; then
    echo "[ERROR] 未找到配置文件: $LOCAL_CONF"
    exit 1
fi
rsync -avz --progress \
    -e "ssh ${SSH_OPT}" \
    "$LOCAL_CONF" \
    root@119.45.251.129:/shihe_back/enjoy-iot/prod-server/application-prod.yml
info "配置文件已上传: application-prod.yml"

# ==========================================
# Step 2: 远程部署（备份 + 复制 + 停止 + 启动）
# ==========================================
info "===== 远程部署正式服务 ====="
ssh ${SSH_OPT} root@119.45.251.129 << 'REMOTE'
set -e

TEST_SERVER_DIR="/shihe_back/enjoy-iot/server"
PROD_SERVER_DIR="/shihe_back/enjoy-iot/prod-server"
LOG_FILE="${PROD_SERVER_DIR}/app-prod.log"
BACKUP_DIR="/shihe_back/backup"

mkdir -p "$PROD_SERVER_DIR" "$BACKUP_DIR"

# ================== 1. 备份当前正式 jar ==================
if [ -f "${PROD_SERVER_DIR}/server.jar" ]; then
    BACKUP_NAME="enjoy-iot-prod_$(date +%Y%m%d_%H%M%S).jar"
    cp "${PROD_SERVER_DIR}/server.jar" "${BACKUP_DIR}/${BACKUP_NAME}"
    echo "[INFO] 备份当前正式 jar: ${BACKUP_NAME} ($(du -h "${BACKUP_DIR}/${BACKUP_NAME}" | cut -f1))"

    # 保留最近 10 份，超出的删除
    BACKUP_COUNT=$(ls "$BACKUP_DIR"/enjoy-iot-prod_*.jar 2>/dev/null | wc -l | tr -d ' ')
    if [ "$BACKUP_COUNT" -gt 10 ]; then
        ls -t "$BACKUP_DIR"/enjoy-iot-prod_*.jar | tail -n +11 | xargs rm -f
        echo "[INFO] 清理旧备份，保留最近 10 份"
    fi
else
    echo "[INFO] 当前无正式 jar，跳过备份（首次部署）"
fi

# ================== 2. 复制最新测试 jar 到正式 ==================
if [ ! -f "${TEST_SERVER_DIR}/server.jar" ]; then
    echo "[ERROR] 测试服务器未找到 server.jar: ${TEST_SERVER_DIR}/server.jar"
    echo "[HINT] 请先在本地执行 ./deploy.sh 部署测试环境"
    exit 1
fi
cp -f "${TEST_SERVER_DIR}/server.jar" "${PROD_SERVER_DIR}/server.jar"
echo "[INFO] 已复制测试 jar 到正式: $(du -h "${PROD_SERVER_DIR}/server.jar" | cut -f1)"

# ================== 3. 停止旧服务 ==================
echo "[INFO] 停止旧服务..."
screen -S enjoy-iot-prod -X quit 2>/dev/null && echo "[INFO] screen 会话已关闭" || echo "[INFO] 无 screen 会话"
pkill -f "/shihe_back/enjoy-iot/prod-server/server.jar" 2>/dev/null && echo "[INFO] Java 进程已停止" || echo "[INFO] 无运行进程"
sleep 2

# ================== 4. 启动新服务 ==================
JAR_PATH=$(find "$PROD_SERVER_DIR" -maxdepth 1 -name "server.jar" -type f | head -1)
if [ -z "$JAR_PATH" ]; then
    echo "[ERROR] 未找到 server.jar！"
    exit 1
fi

echo "[INFO] 启动: $(basename "$JAR_PATH")"
cd "$PROD_SERVER_DIR"
nohup java -Xms512m -Xmx1024m \
    -XX:MaxMetaspaceSize=256m \
    -XX:MaxDirectMemorySize=256m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath="$PROD_SERVER_DIR/" \
    -Djava.security.egd=file:/dev/./urandom \
    -jar "$JAR_PATH" \
    --spring.profiles.active=prod \
    > "$LOG_FILE" 2>&1 &

echo "[INFO] 等待服务启动..."
sleep 5
echo ""
echo "==================== 启动日志 ===================="
tail -50 "$LOG_FILE"
echo "=================================================="
echo ""
echo "[INFO] 部署完成！"
echo "[INFO] 查看日志: ssh root@119.45.251.129 'tail -f ${LOG_FILE}'"
echo "[INFO] 访问地址: http://119.45.251.129:30011"
REMOTE

info "===== 正式部署完成 ====="

#!/bin/bash
# =============================================
# 测试环境部署脚本
# 用法: ./deploy.sh         默认：Maven 打包 + 上传 jar + 上传配置 + 重启（不备份 jar）
#       ./deploy.sh -reload 仅重启远程服务（跳过打包和上传）
# 说明: 测试环境 jar 不做历史备份，每次部署直接覆盖。
#       如需回滚，请重新执行 ./deploy.sh 并使用上一个版本的代码。
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

if [ "$MODE" = "-reload" ]; then
    info "模式: 仅重启远程服务"
else
    info "模式: 全部（打包 + 上传 + 重启，不备份 jar）"

    # ==========================================
    # Step 0: 检查 Maven
    # ==========================================
    info "===== Step 0: 检查 Maven ====="
    MAVEN_BIN=$(command -v mvn || true)
    if [ -z "$MAVEN_BIN" ]; then
        echo "[ERROR] 未找到 mvn 命令，请确认 Maven 已安装并加入 PATH"
        exit 1
    fi
    info "使用 Maven: $MAVEN_BIN"
    $MAVEN_BIN -v | head -1

    # ==========================================
    # Step 1: Maven 打包
    # ==========================================
    info "===== Step 1: Maven 打包 ====="
    cd "$PROJECT_DIR"
    $MAVEN_BIN clean package -DskipTests

    LOCAL_JAR="${PROJECT_DIR}/server/target/server.jar"
    if [ ! -f "$LOCAL_JAR" ]; then
        echo "[ERROR] 未找到 jar 文件: $LOCAL_JAR"
        echo "[HINT] 请先执行 mvn clean package -DskipTests 或检查 server 模块"
        exit 1
    fi
    info "打包完成: $(basename "$LOCAL_JAR") ($(du -h "$LOCAL_JAR" | cut -f1))"

    # ==========================================
    # Step 2: 上传 jar 到测试服务器
    # ==========================================
    info "===== Step 2: 上传 jar 到测试服务器 ====="
    ssh ${SSH_OPT} root@119.45.251.129 "mkdir -p /shihe_back/enjoy-iot/server"
    rsync -avz --progress \
        -e "ssh ${SSH_OPT}" \
        "$LOCAL_JAR" \
        root@119.45.251.129:/shihe_back/enjoy-iot/server/

    # ==========================================
    # Step 2.5: 上传配置文件
    # ==========================================
    info "===== Step 2.5: 上传配置文件 ====="
    LOCAL_CONF="${PROJECT_DIR}/server/src/main/resources/application-test.yml"
    if [ ! -f "$LOCAL_CONF" ]; then
        echo "[ERROR] 未找到配置文件: $LOCAL_CONF"
        exit 1
    fi
    rsync -avz --progress \
        -e "ssh ${SSH_OPT}" \
        "$LOCAL_CONF" \
        root@119.45.251.129:/shihe_back/enjoy-iot/server/application-test.yml
    info "配置文件已上传: application-test.yml"
fi

# ==========================================
# Step 3: 远程重启（不备份）
# ==========================================
info "===== 远程重启测试服务 ====="
ssh ${SSH_OPT} root@119.45.251.129 << 'REMOTE'
set -e

SERVER_DIR="/shihe_back/enjoy-iot/server"
LOG_FILE="${SERVER_DIR}/app.log"

JAR_PATH=$(find "$SERVER_DIR" -maxdepth 1 -name "server.jar" -type f | head -1)
if [ -z "$JAR_PATH" ]; then
    echo "[ERROR] 未找到 server.jar！"
    exit 1
fi

echo "[INFO] 停止旧服务..."
screen -S enjoy-iot -X quit 2>/dev/null && echo "[INFO] screen 会话已关闭" || echo "[INFO] 无 screen 会话"
# 注意：必须精确匹配测试路径，不能用 pkill -f "server.jar"，否则会连带杀掉正式服务（prod-server/server.jar）
pkill -f "/shihe_back/enjoy-iot/server/server.jar" 2>/dev/null && echo "[INFO] Java 进程已停止" || echo "[INFO] 无运行进程"
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
    --spring.profiles.active=test \
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
echo "[INFO] 访问地址: http://119.45.251.129:30010"
REMOTE

info "===== 测试部署完成 ====="

#!/bin/bash
# 小萝卜前后端一键打包并部署脚本
# 用法: 在本地运行 ./package.sh，自动构建、打包、上传、部署到服务器

set -e

SERVER="ubuntu@124.221.117.155"
REMOTE_DIR="/home/ubuntu/xlb/backend"

echo "[1/4] 构建前端..."
cd frontend
npm run build
cd ..

echo "[2/4] 同步前端构建产物到后端静态目录..."
# 备份 APK（如果存在）
APK_TMP=""
if [ -f "backend/static/app-debug.apk" ]; then
    APK_TMP=$(mktemp)
    cp "backend/static/app-debug.apk" "$APK_TMP"
fi
rm -rf backend/static/*
cp -r frontend/dist/* backend/static/
# 恢复 APK
if [ -n "$APK_TMP" ] && [ -f "$APK_TMP" ]; then
    cp "$APK_TMP" backend/static/app-debug.apk
    rm -f "$APK_TMP"
fi

echo "[3/4] 打包后端（含前端静态文件）..."
tar czf xlb-backend.tar.gz backend/

echo "[4/4] 上传到服务器并部署..."
scp xlb-backend.tar.gz "${SERVER}:/tmp/"

ssh "${SERVER}" bash -s << 'REMOTE_SCRIPT'
set -e
cd /tmp
tar xzf xlb-backend.tar.gz

if [ ! -f "backend/static/index.html" ]; then
    echo "错误: backend/static/index.html 不存在，部署终止"
    exit 1
fi

echo "  -> 同步后端代码..."
cp -r backend/app/* /home/ubuntu/xlb/backend/app/
cp backend/requirements.txt /home/ubuntu/xlb/backend/

echo "  -> 同步前端静态文件..."
rm -rf /home/ubuntu/xlb/backend/static/*
cp -r backend/static/* /home/ubuntu/xlb/backend/static/

echo "  -> 更新环境变量..."
ENV_FILE="/home/ubuntu/xlb/backend/.env"
if [ -z "$KIMI_API_KEY" ]; then
    echo "  -> 警告: 本地环境变量 KIMI_API_KEY 未设置，跳过同步"
else
    if [ -f "$ENV_FILE" ]; then
        if ! grep -q "^KIMI_API_KEY=" "$ENV_FILE"; then
            echo "KIMI_API_KEY=$KIMI_API_KEY" >> "$ENV_FILE"
        else
            sed -i "s|^KIMI_API_KEY=.*|KIMI_API_KEY=$KIMI_API_KEY|" "$ENV_FILE"
        fi
    else
        echo "KIMI_API_KEY=$KIMI_API_KEY" > "$ENV_FILE"
    fi
    echo "  -> KIMI_API_KEY 已同步到服务器"
fi

echo "  -> 清理 Python 缓存..."
find /home/ubuntu/xlb/backend -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true

echo "  -> 重启服务..."
# 获取旧进程 PID 并精确杀死，避免 pkill -f 匹配到 SSH 命令行自身
OLD_PID=$(pgrep -f "python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000" || true)
if [ -n "$OLD_PID" ]; then
    kill -9 "$OLD_PID" 2>/dev/null || true
    sleep 2
fi

cd /home/ubuntu/xlb/backend
nohup python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1 > /tmp/xlb-backend.log 2>&1 < /dev/null &
sleep 3

NEW_PID=$(pgrep -f "python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000" || true)
if [ -z "$NEW_PID" ]; then
    echo "错误: 服务启动失败"
    exit 1
fi

echo "  -> 验证版本号..."
LATEST=$(curl -s "http://127.0.0.1:8000/api/ota/check?version_code=0&device_id=deploy" | python3 -c "import sys,json; print(json.load(sys.stdin).get('latest_version','unknown'))" 2>/dev/null || echo "unknown")
if [ "$LATEST" = "67" ]; then
    echo "部署成功，最新版本: $LATEST"
else
    echo "警告: API 返回版本号为 $LATEST，请检查"
fi
REMOTE_SCRIPT

echo "全部完成"

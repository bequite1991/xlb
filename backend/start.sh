#!/bin/bash
# 小萝卜机器人后端启动脚本
# 注意: .env 文件包含敏感 API Key，请勿提交到 Git

cd "$(dirname "$0")"

# 加载环境变量
if [ -f .env ]; then
  export $(grep -v '^#' .env | xargs)
fi

# 如需指定端口可修改下方
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 小萝卜机器人后端 (xlb-backend)

FastAPI 驱动的机器人云端服务，负责：
- MQTT 消息中转（机器人 ↔ 云端 ↔ H5 控制台）
- 语音对话（ASR → LLM → TTS）
- OTA 更新检查
- H5 静态文件托管

## 目录结构

```
xlb-backend/
├── app/
│   ├── main.py              # FastAPI 入口
│   ├── config.py            # 环境变量配置
│   ├── routers/             # API 路由
│   │   ├── ota.py
│   │   ├── robot_control.py
│   │   └── voice_chat.py
│   ├── services/            # 业务服务
│   │   ├── llm_service.py   # MiniMax LLM
│   │   ├── tts_service.py   # MiniMax TTS
│   │   └── asr_service.py   # 讯飞 ASR
│   └── utils/
│       └── mqtt_client.py   # MQTT 客户端
├── static/                  # H5 前端构建产物（运行时挂载）
├── requirements.txt
├── start.sh
└── .env.example
```

## 快速开始

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 安装 ffmpeg（系统依赖）
sudo apt-get install ffmpeg

# 3. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 MINIMAX_API_KEY、讯飞 key 等

# 4. 启动服务
chmod +x start.sh
./start.sh
```

## 部署到域名

如需将 H5 指向 `h5.chujian.site`，参考 `docs/nginx-chujian.site.conf` 配置 Nginx 反向代理。

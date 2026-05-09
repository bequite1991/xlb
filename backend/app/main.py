"""
小萝卜机器人后端 (FastAPI)
启动: uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
依赖: pip install -r requirements.txt
      系统还需安装 ffmpeg: sudo apt-get install ffmpeg
"""

import os
import subprocess

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.routers import ota, robot_control, voice_chat, snapshot, face
from app.utils import mqtt_client
from app.db import init_db
from app.scheduler import start_scheduler, shutdown_scheduler

app = FastAPI(title="小萝卜机器人后端", version="1.0.0")

# 检查 ffmpeg
subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)

# 数据库初始化 + 定时任务 + MQTT 启动 + 预生成提示音
@app.on_event("startup")
async def startup_event():
    init_db()
    start_scheduler()
    mqtt_client.start()
    try:
        from app.services import tts_service
        prompt_path = os.path.join(STATIC_DIR, "prompt_listen.mp3")
        if not os.path.exists(prompt_path):
            url = await tts_service.synthesize("你好呀，我是小萝卜，有什么可以帮你的", "prompt")
            if url:
                import shutil
                src = os.path.join("/tmp/xlb_tts", os.path.basename(url))
                if os.path.exists(src):
                    shutil.copy(src, prompt_path)
                    print(f"Prompt TTS generated: {prompt_path}")
    except Exception as e:
        print(f"Prompt TTS pre-gen skipped: {e}")


@app.on_event("shutdown")
async def shutdown_event():
    shutdown_scheduler()

# 注册路由
app.include_router(ota.router)
app.include_router(robot_control.router)
app.include_router(voice_chat.router)
app.include_router(snapshot.router)
app.include_router(face.router)

# 挂载 TTS 音频静态文件
TTS_DIR = "/tmp/xlb_tts"
os.makedirs(TTS_DIR, exist_ok=True)
app.mount("/api/tts_audio", StaticFiles(directory=TTS_DIR), name="tts_audio")

# 挂载 H5 前端静态文件
STATIC_DIR = os.path.join(os.path.dirname(__file__), "..", "static")
os.makedirs(STATIC_DIR, exist_ok=True)
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="frontend")

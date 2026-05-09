import os

from fastapi import APIRouter, Form
from fastapi.responses import FileResponse

from app.config import TTS_OUTPUT_DIR
from app.utils import mqtt_client

router = APIRouter(prefix="/api/robot", tags=["Robot Control"])


@router.get("/{device_id}/status")
def robot_status(device_id: str):
    return mqtt_client.get_status(device_id)


@router.post("/{device_id}/cmd")
def robot_cmd(device_id: str, payload: dict):
    mqtt_client.publish_cmd(device_id, payload)
    return {"success": True}


@router.post("/{device_id}/wifi")
def robot_wifi(device_id: str, ssid: str = Form(...), password: str = Form(...)):
    mqtt_client.publish_cmd(device_id, {"action": "config_wifi", "ssid": ssid, "password": password})
    return {"success": True}


from app.config import PUBLIC_HOST

@router.post("/{device_id}/ota")
def robot_ota(device_id: str):
    apk_path = os.path.join(os.path.dirname(__file__), "..", "..", "static", "app-debug.apk")
    apk_url = f"{PUBLIC_HOST}/app-debug.apk" if os.path.exists(apk_path) else None
    mqtt_client.publish_cmd(device_id, {"action": "update", "apk_url": apk_url})
    return {"success": True, "apk_url": apk_url}


import re

_FILENAME_RE = re.compile(r"^[\w\-]+\.mp3$")

@router.get("/tts_audio/{filename}")
def serve_tts_audio(filename: str):
    if not _FILENAME_RE.match(filename):
        return {"detail": "not found"}
    path = os.path.join(TTS_OUTPUT_DIR, filename)
    if os.path.exists(path) and os.path.commonpath([path, TTS_OUTPUT_DIR]) == TTS_OUTPUT_DIR:
        return FileResponse(path, media_type="audio/mp4")
    return {"detail": "not found"}

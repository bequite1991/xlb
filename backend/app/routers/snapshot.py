import os
import re
import time

from fastapi import APIRouter, File, Form, UploadFile

from app.services import memory_service, vision_service

router = APIRouter(prefix="/api", tags=["Snapshot"])
_DEVICE_ID_RE = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")

SNAPSHOT_PROMPT = "简要描述你看到的环境，适合作为记忆保存。用一句话概括。"


@router.post("/snapshot")
async def snapshot(image: UploadFile = File(...), device_id: str = Form(...)):
    if not device_id or not _DEVICE_ID_RE.match(device_id):
        return {"success": False, "error": "Invalid device_id"}
    upload_path = f"/tmp/xlb_snapshot_{device_id}_{int(time.time())}.jpg"
    try:
        with open(upload_path, "wb") as f:
            f.write(await image.read())

        summary = await vision_service.describe_image(upload_path, prompt=SNAPSHOT_PROMPT)
        if summary and not summary.startswith("出错了"):
            memory_service.add_memories(device_id, [f"[环境快照] {summary}"])
            print(f"[snapshot] {device_id} | summary={summary[:50]}")
            return {"success": True, "summary": summary}
        else:
            return {"success": False, "error": "Vision analysis failed"}
    except Exception as e:
        print(f"[snapshot] ERROR: {e}")
        return {"success": False, "error": "Snapshot failed"}
    finally:
        try:
            os.remove(upload_path)
        except Exception:
            pass

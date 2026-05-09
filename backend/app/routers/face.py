import re
from datetime import datetime

import numpy as np
from fastapi import APIRouter, File, Form, UploadFile

from app.db import get_conn
from app.services import face_service

router = APIRouter(prefix="/api/face", tags=["Face"])

_DEVICE_ID_RE = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")
_MAX_FACES = 5
_RECOGNIZE_THRESHOLD = 0.5


def _sanitize_device_id(device_id: str) -> str:
    if not device_id or not _DEVICE_ID_RE.match(device_id):
        raise ValueError("Invalid device_id")
    return device_id


@router.post("/register")
async def face_register(
    image: UploadFile = File(...),
    device_id: str = Form(...),
    name: str = Form(...),
    relation: str = Form(""),
):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"success": False, "error": "Invalid device_id"}

    name = name.strip()
    if not name or len(name) > 20:
        return {"success": False, "error": "Name must be 1-20 chars"}

    image_data = await image.read()
    if len(image_data) > 2 * 1024 * 1024:
        return {"success": False, "error": "Image too large (max 2MB)"}

    try:
        embedding = face_service.extract_embedding(image_data)
    except FileNotFoundError:
        return {"success": False, "error": "Face model not loaded"}
    except Exception as e:
        return {"success": False, "error": f"Face extraction failed: {e}"}

    with get_conn() as conn:
        count = conn.execute(
            "SELECT COUNT(*) as cnt FROM face_registry WHERE device_id = ?",
            (device_id,)
        ).fetchone()["cnt"]
        if count >= _MAX_FACES:
            return {"success": False, "error": f"最多只能注册 {_MAX_FACES} 个人脸"}

        # 检查是否已存在同名
        existing = conn.execute(
            "SELECT id FROM face_registry WHERE device_id = ? AND name = ?",
            (device_id, name)
        ).fetchone()
        if existing:
            # 更新现有记录
            conn.execute(
                "UPDATE face_registry SET relation = ?, embedding = ?, face_id = ?, updated_at = ? WHERE device_id = ? AND name = ?",
                (relation, embedding.tobytes(), f"face_{int(datetime.now().timestamp()*1000)}", datetime.now().isoformat(), device_id, name)
            )
        else:
            conn.execute(
                "INSERT INTO face_registry (device_id, face_id, name, relation, embedding) VALUES (?, ?, ?, ?, ?)",
                (device_id, f"face_{int(time.time()*1000)}", name, relation, embedding.tobytes())
            )
        conn.commit()

    return {"success": True, "name": name}


@router.post("/recognize")
async def face_recognize(
    image: UploadFile = File(...),
    device_id: str = Form(...),
):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"name": None, "confidence": 0}

    image_data = await image.read()
    if len(image_data) > 2 * 1024 * 1024:
        return {"name": None, "confidence": 0, "error": "Image too large"}

    try:
        embedding = face_service.extract_embedding(image_data)
    except FileNotFoundError:
        return {"name": None, "confidence": 0, "error": "Face model not loaded"}
    except Exception as e:
        return {"name": None, "confidence": 0, "error": str(e)}

    with get_conn() as conn:
        rows = conn.execute(
            "SELECT name, relation, embedding FROM face_registry WHERE device_id = ?",
            (device_id,)
        ).fetchall()

        best_name = None
        best_confidence = 0.0

        for row in rows:
            db_embedding = np.frombuffer(row["embedding"], dtype=np.float32)
            confidence = face_service.compare_embeddings(embedding, db_embedding)
            if confidence > best_confidence:
                best_confidence = confidence
                best_name = row["name"]

        if best_confidence >= _RECOGNIZE_THRESHOLD:
            return {"name": best_name, "confidence": round(best_confidence, 3)}
        else:
            return {"name": None, "confidence": round(best_confidence, 3)}


@router.get("/list/{device_id}")
async def face_list(device_id: str):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"faces": []}

    with get_conn() as conn:
        rows = conn.execute(
            "SELECT face_id, name, relation, created_at FROM face_registry WHERE device_id = ? ORDER BY created_at DESC",
            (device_id,)
        ).fetchall()
        faces = []
        for r in rows:
            faces.append({
                "face_id": r["face_id"],
                "name": r["name"],
                "relation": r["relation"],
                "created_at": r["created_at"],
            })
        return {"faces": faces}


@router.post("/delete")
async def face_delete(device_id: str = Form(...), name: str = Form(...)):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"success": False, "error": "Invalid device_id"}

    with get_conn() as conn:
        conn.execute(
            "DELETE FROM face_registry WHERE device_id = ? AND name = ?",
            (device_id, name)
        )
        conn.commit()
    return {"success": True}

import io
import os
import time

import numpy as np
from PIL import Image

from app.config import FACE_MODEL_PATH

_session = None


def _get_session():
    global _session
    if _session is None:
        if not os.path.exists(FACE_MODEL_PATH):
            raise FileNotFoundError(f"Face recognition model not found: {FACE_MODEL_PATH}")
        import onnxruntime as ort
        _session = ort.InferenceSession(FACE_MODEL_PATH, providers=["CPUExecutionProvider"])
    return _session


def extract_embedding(image_bytes: bytes) -> np.ndarray:
    """从人脸 crop 图提取 512 维 embedding 向量"""
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img = img.resize((112, 112), Image.BILINEAR)

    arr = np.array(img).astype(np.float32)
    arr = (arr - 127.5) / 128.0
    arr = np.transpose(arr, (2, 0, 1))
    arr = np.expand_dims(arr, axis=0)

    session = _get_session()
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: arr})
    embedding = outputs[0][0]

    # L2 归一化
    norm = np.linalg.norm(embedding)
    if norm > 0:
        embedding = embedding / norm
    return embedding


def compare_embeddings(a: np.ndarray, b: np.ndarray) -> float:
    """余弦相似度（输入已 L2 归一化，直接点积）"""
    return float(np.dot(a, b))

"""
讯飞语音听写 WebSocket API (流式版)
文档: https://www.xfyun.cn/doc/asr/voicedictation/API.html
"""

import base64
import hashlib
import hmac
import json
import os
import ssl
import subprocess
from datetime import datetime
from urllib.parse import urlencode

import websocket

APPID = os.getenv("XFYUN_APPID", "")
APISecret = os.getenv("XFYUN_APISECRET", "")
APIKey = os.getenv("XFYUN_APIKEY", "")


def _create_url() -> str:
    url = "wss://iat-api.xfyun.cn/v2/iat"
    date = datetime.utcnow().strftime("%a, %d %b %Y %H:%M:%S GMT")
    host = "iat-api.xfyun.cn"
    signature_origin = f"host: {host}\ndate: {date}\nGET /v2/iat HTTP/1.1"
    signature_sha = hmac.new(
        APISecret.encode("utf-8"),
        signature_origin.encode("utf-8"),
        digestmod=hashlib.sha256,
    ).digest()
    signature_sha = base64.b64encode(signature_sha).decode("utf-8")
    authorization_origin = (
        f'api_key="{APIKey}", algorithm="hmac-sha256", '
        f'headers="host date request-line", signature="{signature_sha}"'
    )
    authorization = base64.b64encode(authorization_origin.encode("utf-8")).decode("utf-8")
    params = {"authorization": authorization, "date": date, "host": host}
    return url + "?" + urlencode(params)


def _read_pcm(pcm_path: str) -> bytes:
    with open(pcm_path, "rb") as f:
        return f.read()


def _send_frames(ws, pcm_data: bytes):
    frame_size = 1280
    total = len(pcm_data)

    first = {
        "common": {"app_id": APPID},
        "business": {
            "language": "zh_cn",
            "domain": "iat",
            "accent": "mandarin",
            "vad_eos": 3000,
            "dwa": "wpgs",
        },
        "data": {
            "status": 0,
            "format": "audio/L16;rate=16000",
            "encoding": "raw",
            "audio": base64.b64encode(pcm_data[:frame_size]).decode("utf-8"),
        },
    }
    ws.send(json.dumps(first))

    idx = frame_size
    while idx < total:
        chunk = pcm_data[idx : idx + frame_size]
        status = 2 if idx + frame_size >= total else 1
        frame = {
            "data": {
                "status": status,
                "format": "audio/L16;rate=16000",
                "encoding": "raw",
                "audio": base64.b64encode(chunk).decode("utf-8"),
            }
        }
        ws.send(json.dumps(frame))
        idx += frame_size

    if total % frame_size == 0:
        ws.send(
            json.dumps(
                {
                    "data": {
                        "status": 2,
                        "format": "audio/L16;rate=16000",
                        "encoding": "raw",
                        "audio": "",
                    }
                }
            )
        )


def _recv_result(ws) -> str:
    result_text = ""
    while True:
        try:
            msg = ws.recv()
        except Exception:
            break

        try:
            msg_obj = json.loads(msg)
        except Exception:
            continue

        code = msg_obj.get("code", -1)
        if code != 0:
            raise RuntimeError(f"xfyun error {code}: {msg_obj.get('message')}")

        data = msg_obj.get("data", {})
        if "result" in data:
            for w in data["result"]["ws"]:
                for cw in w["cw"]:
                    result_text += cw.get("w", "")

        if data.get("status") == 2:
            break

    return result_text


def m4a_to_pcm(m4a_path: str, pcm_path: str):
    cmd = [
        "ffmpeg",
        "-y",
        "-i", m4a_path,
        "-acodec", "pcm_s16le",
        "-ar", "16000",
        "-ac", "1",
        "-f", "s16le",
        pcm_path,
    ]
    subprocess.run(cmd, check=True, capture_output=True)


def transcribe(audio_path: str) -> str:
    pcm_path = audio_path + ".pcm"
    m4a_to_pcm(audio_path, pcm_path)

    ws_url = _create_url()
    ws = websocket.create_connection(ws_url, sslopt={"cert_reqs": ssl.CERT_NONE})

    pcm_data = _read_pcm(pcm_path)
    _send_frames(ws, pcm_data)
    text = _recv_result(ws)

    ws.close()

    try:
        os.remove(pcm_path)
    except Exception:
        pass

    return text

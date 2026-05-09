import asyncio
import os
import re
import time

import httpx

from app.config import MINIMAX_API_KEY, PUBLIC_HOST, TTS_OUTPUT_DIR

EDGE_TTS_VOICE = "zh-CN-XiaoyiNeural"
# 超过此长度启用并行分段合成
PARALLEL_THRESHOLD = 60

# 复用 AsyncClient 避免每次创建连接池开销
_minimax_client = httpx.AsyncClient(timeout=30)


async def _close_client():
    await _minimax_client.aclose()


def _split_text(text: str, max_len: int = 80) -> list:
    """按句子切分文本，每段不超过 max_len 字符。"""
    # 优先按句子结束符切分
    parts = re.split(r"(?<=[。！？；])", text)
    parts = [p.strip() for p in parts if p.strip()]

    result = []
    current = ""
    for p in parts:
        if len(current) + len(p) <= max_len:
            current += p
        else:
            if current:
                result.append(current)
            # 单段过长时尝试按逗号切分
            if len(p) > max_len:
                subparts = re.split(r"(?<=[，,])", p)
                sub = ""
                for sp in subparts:
                    if len(sub) + len(sp) <= max_len:
                        sub += sp
                    else:
                        if sub:
                            result.append(sub)
                        sub = sp
                if sub:
                    result.append(sub)
                current = ""
            else:
                current = p
    if current:
        result.append(current)
    return result if result else [text]


async def synthesize(text: str, device_id: str, output_filename: str = None) -> str:
    filename = output_filename or f"tts_{device_id}_{int(time.time())}.mp3"
    out_path = os.path.join(TTS_OUTPUT_DIR, filename)

    # 1. 尝试 MiniMax（长文本并行合成）
    if MINIMAX_API_KEY:
        try:
            if len(text) > PARALLEL_THRESHOLD:
                audio_url = await _synthesize_parallel(text, out_path)
            else:
                audio_url = await _synthesize_minimax(text, out_path)
            if audio_url:
                return audio_url
        except Exception as e:
            print(f"[TTS] MiniMax failed: {e}")

    # 2. Fallback 到 Edge TTS
    try:
        audio_url = await _synthesize_edge(text, out_path)
        if audio_url:
            return audio_url
    except Exception as e:
        print(f"[TTS] Edge TTS failed: {e}")

    return None


async def _synthesize_minimax_bytes(text: str) -> bytes:
    """调用 MiniMax TTS 返回音频 bytes。"""
    url = "https://api.minimax.chat/v1/t2a_v2"
    headers = {
        "Authorization": f"Bearer {MINIMAX_API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": "speech-2.8-hd",
        "text": text,
        "voice_setting": {
            "voice_id": "female-shaonv",
            "speed": 1.0,
            "vol": 1.0,
            "pitch": 0,
        },
        "audio_setting": {
            "sample_rate": 24000,
            "bitrate": 128000,
            "format": "mp3",
            "channel": 1,
        },
    }

    resp = await _minimax_client.post(url, headers=headers, json=payload)
    resp.raise_for_status()
    data = resp.json()

    hex_audio = data.get("data", {}).get("audio")
    base_resp = data.get("base_resp", {})
    print(f"[TTS_DEBUG] text='{text[:30]}' status_code={base_resp.get('status_code')} status_msg={base_resp.get('status_msg')} hex_len={len(hex_audio) if hex_audio else 0}")
    if not hex_audio:
        raise Exception("No audio data from MiniMax")

    return bytes.fromhex(hex_audio)


async def _synthesize_minimax(text: str, out_path: str) -> str:
    audio_bytes = await _synthesize_minimax_bytes(text)
    with open(out_path, "wb") as f:
        f.write(audio_bytes)

    filename = os.path.basename(out_path)
    return f"{PUBLIC_HOST}/api/tts_audio/{filename}"


async def _synthesize_parallel(text: str, out_path: str) -> str:
    """将长文本分段并行合成，再按顺序拼接为单个 MP3 文件。"""
    segments = _split_text(text)
    if len(segments) <= 1:
        return await _synthesize_minimax(text, out_path)

    t0 = time.time()
    tasks = [_synthesize_minimax_bytes(seg) for seg in segments]
    results = await asyncio.gather(*tasks, return_exceptions=True)

    combined = bytearray()
    for i, res in enumerate(results):
        if isinstance(res, Exception):
            raise Exception(f"Segment {i} ({segments[i][:20]}) failed: {res}")
        combined.extend(res)

    with open(out_path, "wb") as f:
        f.write(combined)

    elapsed = int((time.time() - t0) * 1000)
    print(f"[TTS] Parallel synthesis: {len(segments)} segments, {len(combined)} bytes, {elapsed}ms")
    filename = os.path.basename(out_path)
    return f"{PUBLIC_HOST}/api/tts_audio/{filename}"


async def _synthesize_edge(text: str, out_path: str) -> str:
    import edge_tts

    t0 = time.time()
    communicate = edge_tts.Communicate(text, voice=EDGE_TTS_VOICE)
    await communicate.save(out_path)
    elapsed = int((time.time() - t0) * 1000)
    print(f"[TTS] Edge TTS saved to {out_path}, elapsed={elapsed}ms, text_len={len(text)}")

    filename = os.path.basename(out_path)
    return f"{PUBLIC_HOST}/api/tts_audio/{filename}"

import asyncio
import os
import random
import time
from concurrent.futures import ThreadPoolExecutor

from fastapi import APIRouter, File, Form, UploadFile

from app.config import PUBLIC_HOST
from app.db import get_conn
from app.services import asr_service, llm_service, tts_service, memory_service, vision_service
import re

MAX_AUDIO_BYTES = 10 * 1024 * 1024
MAX_TEXT_LEN = 2000
MAX_PROMPT_LEN = 500
MAX_LOG_LIMIT = 500
DEVICE_ID_RE = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")


def _sanitize_device_id(device_id: str) -> str:
    if not device_id or not DEVICE_ID_RE.match(device_id):
        raise ValueError("Invalid device_id")
    return device_id


def _safe_error():
    return {"reply": "服务暂时出错了，请稍后再试", "audio_url": None, "actions": []}


def _save_chat_log(device_id, chat_type, total_ms, upload_ms=None, asr_ms=None, llm_ms=None,
                   tts_ms=None, vision_ms=None, user_text=None, reply_text=None,
                   should_continue=None, error=None):
    try:
        with get_conn() as conn:
            conn.execute(
                """INSERT INTO chat_logs
                (device_id, chat_type, upload_ms, asr_ms, llm_ms, tts_ms, vision_ms,
                 total_ms, user_text, reply_text, should_continue, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (device_id, chat_type, upload_ms, asr_ms, llm_ms, tts_ms, vision_ms,
                 total_ms,
                 user_text[:200] if user_text else None,
                 reply_text[:200] if reply_text else None,
                 1 if should_continue else 0 if should_continue is not None else None,
                 error[:500] if error else None)
            )
            conn.commit()
    except Exception as e:
        print(f"[chat_log] save failed: {e}")

router = APIRouter(prefix="/api", tags=["Voice Chat"])
executor = ThreadPoolExecutor(max_workers=4)

LONG_GREETING = "你好呀，我是小萝卜，一个可爱的智能机器人。我可以陪你聊天、讲故事，还能控制轮子到处跑呢。有什么我可以帮你的吗？"
SHORT_GREETINGS = [
    "嗨，有什么想和我聊的吗？",
    "你好呀，想让我做什么呢？",
    "小萝卜来啦，有什么吩咐？",
    "嘿，在呢！",
    "有什么我可以帮你的吗？",
]


@router.post("/voice_chat")
async def voice_chat(audio: UploadFile = File(...), device_id: str = Form(...)):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return _safe_error()
    t_start = time.time()
    upload_path = f"/tmp/xlb_voice_{device_id}_{int(time.time())}.m4a"

    try:
        audio_data = await audio.read()
        if len(audio_data) > MAX_AUDIO_BYTES:
            return {"reply": "音频文件太大，请缩短后重试", "audio_url": None, "actions": []}
        with open(upload_path, "wb") as f:
            f.write(audio_data)
        t_upload = time.time()

        loop = asyncio.get_event_loop()
        # ASR 和 history 并行执行（history 不依赖 ASR 结果）
        asr_future = loop.run_in_executor(executor, asr_service.transcribe, upload_path)
        history_future = loop.run_in_executor(executor, memory_service.get_recent_conversations, device_id, 5)
        user_text = await asr_future
        t_asr = time.time()

        if not user_text:
            return {"reply": "我没听清，请再试一次", "audio_url": None, "actions": []}

        history = await history_future
        memories = memory_service.retrieve_memories(device_id, user_text, top_k=1)

        reply_text, actions, should_continue = await llm_service.chat(user_text, device_id, history=history, memories=memories)
        t_llm = time.time()

        memory_service.save_conversation(device_id, "user", user_text)
        memory_service.save_conversation(device_id, "assistant", reply_text)

        audio_url = await tts_service.synthesize(reply_text, device_id)
        t_tts = time.time()

        upload_ms_v = int((t_upload - t_start) * 1000)
        asr_ms_v = int((t_asr - t_upload) * 1000)
        llm_ms_v = int((t_llm - t_asr) * 1000)
        tts_ms_v = int((t_tts - t_llm) * 1000)
        total_ms = int((t_tts - t_start) * 1000)
        print(f"[voice_chat] {device_id} | "
              f"upload={upload_ms_v}ms "
              f"asr={asr_ms_v}ms "
              f"llm={llm_ms_v}ms "
              f"tts={tts_ms_v}ms "
              f"total={total_ms}ms "
              f"text='{user_text[:30]}' reply='{reply_text[:30]}' continue={should_continue}")
        _save_chat_log(device_id, "voice_chat", total_ms,
                       upload_ms=upload_ms_v, asr_ms=asr_ms_v, llm_ms=llm_ms_v, tts_ms=tts_ms_v,
                       user_text=user_text, reply_text=reply_text, should_continue=should_continue)

        return {"reply": reply_text, "audio_url": audio_url, "actions": actions, "should_continue": should_continue}
    except Exception as e:
        import traceback
        print(f"[voice_chat] ERROR: {e}")
        traceback.print_exc()
        total_ms = int((time.time() - t_start) * 1000)
        _save_chat_log(device_id, "voice_chat", total_ms, error=str(e))
        return _safe_error()
    finally:
        try:
            os.remove(upload_path)
        except Exception:
            pass


@router.get("/greeting/{device_id}")
async def greeting(device_id: str):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"type": "short", "text": "你好呀", "audio_url": None}
    with get_conn() as conn:
        row = conn.execute(
            "SELECT COUNT(*) as cnt FROM conversations WHERE device_id = ?",
            (device_id,)
        ).fetchone()
        is_first = row["cnt"] == 0

    if is_first:
        text = LONG_GREETING
        filename = "greeting_long.mp3"
    else:
        text = random.choice(SHORT_GREETINGS)
        idx = SHORT_GREETINGS.index(text)
        filename = f"greeting_short_{idx}.mp3"

    out_path = os.path.join("/tmp/xlb_tts", filename)
    if os.path.exists(out_path):
        audio_url = f"{PUBLIC_HOST}/api/tts_audio/{filename}"
    else:
        audio_url = await tts_service.synthesize(text, device_id, output_filename=filename)

    return {"type": "long" if is_first else "short", "text": text, "audio_url": audio_url}


@router.post("/text_chat")
async def text_chat(device_id: str = Form(...), text: str = Form(...)):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return _safe_error()
    if not text or len(text) > MAX_TEXT_LEN:
        return {"reply": "输入太长或为空，请调整后重试", "audio_url": None, "actions": []}
    t_start = time.time()

    try:
        loop = asyncio.get_event_loop()
        history_future = loop.run_in_executor(executor, memory_service.get_recent_conversations, device_id, 5)
        memories_future = loop.run_in_executor(executor, memory_service.retrieve_memories, device_id, text, 1)
        history = await history_future
        memories = await memories_future

        reply_text, actions, should_continue = await llm_service.chat(text, device_id, history=history, memories=memories)
        t_llm = time.time()

        memory_service.save_conversation(device_id, "user", text)
        memory_service.save_conversation(device_id, "assistant", reply_text)

        audio_url = await tts_service.synthesize(reply_text, device_id)
        t_tts = time.time()

        llm_ms_v = int((t_llm - t_start) * 1000)
        tts_ms_v = int((t_tts - t_llm) * 1000)
        total_ms = int((t_tts - t_start) * 1000)
        print(f"[text_chat] {device_id} | "
              f"llm={llm_ms_v}ms "
              f"tts={tts_ms_v}ms "
              f"total={total_ms}ms "
              f"text='{text[:30]}' reply='{reply_text[:30]}' continue={should_continue}")
        _save_chat_log(device_id, "text_chat", total_ms,
                       llm_ms=llm_ms_v, tts_ms=tts_ms_v,
                       user_text=text, reply_text=reply_text, should_continue=should_continue)

        return {"reply": reply_text, "audio_url": audio_url, "actions": actions, "should_continue": should_continue}
    except Exception as e:
        import traceback
        print(f"[text_chat] ERROR: {e}")
        traceback.print_exc()
        total_ms = int((time.time() - t_start) * 1000)
        _save_chat_log(device_id, "text_chat", total_ms, user_text=text, error=str(e))
        return _safe_error()


@router.post("/vision_chat")
async def vision_chat(image: UploadFile = File(...), device_id: str = Form(...), prompt: str = Form("你看到了什么？请用简短有趣的语言描述。")):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return _safe_error()
    if len(prompt) > MAX_PROMPT_LEN:
        return {"reply": "提示词太长，请缩短后重试", "audio_url": None}
    t_start = time.time()
    upload_path = f"/tmp/xlb_vision_{device_id}_{int(time.time())}.jpg"

    try:
        with open(upload_path, "wb") as f:
            f.write(await image.read())

        reply_text = await vision_service.describe_image(upload_path, prompt)
        t_vision = time.time()

        audio_url = await tts_service.synthesize(reply_text, device_id)
        t_tts = time.time()

        memory_service.save_conversation(device_id, "user", f"[看图] {prompt}")
        memory_service.save_conversation(device_id, "assistant", reply_text)

        vision_ms_v = int((t_vision - t_start) * 1000)
        tts_ms_v = int((t_tts - t_vision) * 1000)
        total_ms = int((t_tts - t_start) * 1000)
        print(f"[vision_chat] {device_id} | vision={vision_ms_v}ms tts={tts_ms_v}ms total={total_ms}ms reply='{reply_text[:30]}'")
        _save_chat_log(device_id, "vision_chat", total_ms,
                       vision_ms=vision_ms_v, tts_ms=tts_ms_v,
                       user_text=prompt, reply_text=reply_text)

        return {"reply": reply_text, "audio_url": audio_url}
    except Exception as e:
        import traceback
        print(f"[vision_chat] ERROR: {e}")
        traceback.print_exc()
        total_ms = int((time.time() - t_start) * 1000)
        _save_chat_log(device_id, "vision_chat", total_ms, user_text=prompt, error=str(e))
        return _safe_error()
    finally:
        try:
            os.remove(upload_path)
        except Exception:
            pass


@router.post("/clear_session")
async def clear_session(device_id: str = Form(...)):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"message": "参数错误"}
    memory_service.clear_conversations(device_id)
    return {"message": "会话已清空"}


@router.get("/chat_logs/{device_id}")
async def get_chat_logs(device_id: str, limit: int = 50, chat_type: str = None):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"logs": []}
    if limit < 1 or limit > MAX_LOG_LIMIT:
        limit = 50
    with get_conn() as conn:
        if chat_type:
            rows = conn.execute(
                """SELECT * FROM chat_logs
                   WHERE device_id = ? AND chat_type = ?
                   ORDER BY created_at DESC LIMIT ?""",
                (device_id, chat_type, limit)
            ).fetchall()
        else:
            rows = conn.execute(
                """SELECT * FROM chat_logs
                   WHERE device_id = ?
                   ORDER BY created_at DESC LIMIT ?""",
                (device_id, limit)
            ).fetchall()
        logs = []
        for r in rows:
            logs.append({
                "id": r["id"],
                "chat_type": r["chat_type"],
                "upload_ms": r["upload_ms"],
                "asr_ms": r["asr_ms"],
                "llm_ms": r["llm_ms"],
                "tts_ms": r["tts_ms"],
                "vision_ms": r["vision_ms"],
                "total_ms": r["total_ms"],
                "user_text": r["user_text"],
                "reply_text": r["reply_text"],
                "should_continue": bool(r["should_continue"]) if r["should_continue"] is not None else None,
                "error": r["error"],
                "created_at": r["created_at"],
            })
        return {"logs": logs}


@router.post("/tts_synthesize")
async def tts_synthesize(device_id: str = Form(...), text: str = Form(...)):
    try:
        device_id = _sanitize_device_id(device_id)
    except ValueError:
        return {"text": text, "audio_url": None, "error": "Invalid device_id"}
    if not text or len(text) > MAX_TEXT_LEN:
        return {"text": text, "audio_url": None, "error": "Text too long or empty"}
    try:
        audio_url = await tts_service.synthesize(text, device_id)
        if audio_url:
            return {"text": text, "audio_url": audio_url}
        return {"text": text, "audio_url": None, "error": "TTS synthesis failed"}
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {"text": text, "audio_url": None, "error": str(e)}

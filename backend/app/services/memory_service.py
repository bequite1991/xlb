import os
from datetime import datetime, timedelta

import chromadb
from chromadb.config import Settings
from chromadb.utils.embedding_functions import DefaultEmbeddingFunction

from app.db import get_conn
from app.services import llm_service

CHROMA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "chroma_db")
os.makedirs(CHROMA_DIR, exist_ok=True)

_chroma_client = None
_ef = DefaultEmbeddingFunction()


def get_chroma():
    global _chroma_client
    if _chroma_client is None:
        _chroma_client = chromadb.Client(
            Settings(persist_directory=CHROMA_DIR, anonymized_telemetry=False)
        )
    return _chroma_client


def _collection_name(device_id: str) -> str:
    return f"memories_{device_id}"


def save_conversation(device_id: str, role: str, content: str):
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO conversations (device_id, role, content) VALUES (?, ?, ?)",
            (device_id, role, content),
        )
        conn.commit()


def clear_conversations(device_id: str):
    with get_conn() as conn:
        conn.execute(
            "DELETE FROM conversations WHERE device_id = ?",
            (device_id,),
        )
        conn.commit()


def get_recent_conversations(device_id: str, limit: int = 10):
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT role, content FROM conversations WHERE device_id = ? ORDER BY created_at DESC LIMIT ?",
            (device_id, limit),
        ).fetchall()
        return [{"role": r["role"], "content": r["content"]} for r in reversed(rows)]


def get_conversations_for_date(device_id: str, date: datetime.date):
    start = datetime.combine(date, datetime.min.time())
    end = start + timedelta(days=1)
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT role, content FROM conversations WHERE device_id = ? AND created_at >= ? AND created_at < ? ORDER BY created_at",
            (device_id, start.isoformat(), end.isoformat()),
        ).fetchall()
        return [{"role": r["role"], "content": r["content"]} for r in rows]


def retrieve_memories(device_id: str, query: str, top_k: int = 3):
    if not query or not query.strip():
        return []
    chroma = get_chroma()
    collection = chroma.get_or_create_collection(
        _collection_name(device_id), embedding_function=_ef
    )
    if collection.count() == 0:
        return []
    results = collection.query(query_texts=[query], n_results=top_k)
    docs = results.get("documents", [[]])[0]
    return [d for d in docs if d]


def add_memories(device_id: str, texts: list):
    if not texts:
        return
    chroma = get_chroma()
    collection = chroma.get_or_create_collection(
        _collection_name(device_id), embedding_function=_ef
    )
    ids = [f"{device_id}_{datetime.now().timestamp()}_{i}" for i in range(len(texts))]
    collection.add(ids=ids, documents=texts)


def compress_and_store(device_id: str, date: datetime.date):
    convs = get_conversations_for_date(device_id, date)
    if not convs:
        return

    lines = []
    for c in convs:
        prefix = "用户" if c["role"] == "user" else "机器人"
        lines.append(f"{prefix}: {c['content']}")
    dialog_text = "\n".join(lines)

    prompt = (
        "请将以下对话记录提炼成 3-5 条关键记忆，每条用一句话概括。"
        "只输出记忆列表，每行一条，不要添加编号或额外说明。\n\n"
        f"对话记录：\n{dialog_text}"
    )

    import asyncio
    summary, _ = asyncio.run(llm_service.chat(prompt, device_id=device_id))

    memories = [line.strip() for line in summary.split("\n") if line.strip()]
    if memories:
        add_memories(device_id, memories)
        with get_conn() as conn:
            conn.execute(
                "INSERT INTO memory_compress_log (device_id, date, summary) VALUES (?, ?, ?)",
                (device_id, date.isoformat(), summary),
            )
            conn.commit()


def compress_all_devices():
    yesterday = datetime.now().date() - timedelta(days=1)
    with get_conn() as conn:
        rows = conn.execute(
            "SELECT DISTINCT device_id FROM conversations WHERE DATE(created_at) = ?",
            (yesterday.isoformat(),),
        ).fetchall()
    for row in rows:
        compress_and_store(row["device_id"], yesterday)

import sqlite3
from contextlib import contextmanager
import os

DB_PATH = os.path.join(os.path.dirname(__file__), "..", "xlb_memory.db")


def init_db():
    with get_conn() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_conv_device_time
            ON conversations(device_id, created_at)
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS memory_compress_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                date TEXT NOT NULL,
                summary TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS chat_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                chat_type TEXT NOT NULL,
                upload_ms INTEGER,
                asr_ms INTEGER,
                llm_ms INTEGER,
                tts_ms INTEGER,
                vision_ms INTEGER,
                total_ms INTEGER NOT NULL,
                user_text TEXT,
                reply_text TEXT,
                should_continue INTEGER,
                error TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_chat_logs_device_time
            ON chat_logs(device_id, created_at)
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS face_registry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                face_id TEXT NOT NULL,
                name TEXT NOT NULL,
                relation TEXT,
                embedding BLOB NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(device_id, name)
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_face_registry_device
            ON face_registry(device_id)
        """)
        conn.commit()


@contextmanager
def get_conn():
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def cleanup_chat_logs(days=30):
    """清理超过 N 天的 chat_logs，防止数据库无限增长。"""
    try:
        from datetime import datetime, timedelta
        cutoff = (datetime.now() - timedelta(days=days)).isoformat()
        with get_conn() as conn:
            result = conn.execute(
                "DELETE FROM chat_logs WHERE created_at < ?", (cutoff,)
            )
            conn.commit()
            print(f"[db] cleanup_chat_logs: removed {result.rowcount} old records")
    except Exception as e:
        print(f"[db] cleanup_chat_logs failed: {e}")

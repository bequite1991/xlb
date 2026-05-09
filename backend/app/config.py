import os
from dotenv import load_dotenv

load_dotenv()

MINIMAX_API_KEY = os.getenv("MINIMAX_API_KEY", "")
QWEN_VL_API_KEY = os.getenv("QWEN_VL_API_KEY", "")
PUBLIC_HOST = os.getenv("PUBLIC_HOST", "http://localhost:8000")
TTS_OUTPUT_DIR = "/tmp/xlb_tts"
os.makedirs(TTS_OUTPUT_DIR, exist_ok=True)
FACE_MODEL_PATH = os.getenv("FACE_MODEL_PATH", os.path.join(os.path.dirname(__file__), "..", "models", "w600k_mbf.onnx"))

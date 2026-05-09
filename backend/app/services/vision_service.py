import base64

import httpx

from app.config import QWEN_VL_API_KEY

VISION_MODEL = "qwen-vl-plus"
VISION_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

# 复用 AsyncClient 避免每次创建连接池开销
_vision_client = httpx.AsyncClient(timeout=60)


async def close_vision_client():
    await _vision_client.aclose()


async def describe_image(image_path: str, prompt: str = "你看到了什么？请用简短有趣的语言描述。") -> str:
    if not QWEN_VL_API_KEY:
        return "我还没配置好视觉模型呢"

    with open(image_path, "rb") as f:
        image_bytes = f.read()
    b64 = base64.b64encode(image_bytes).decode("utf-8")
    data_url = f"data:image/jpeg;base64,{b64}"

    payload = {
        "model": VISION_MODEL,
        "messages": [
            {
                "role": "system",
                "content": "你是小萝卜机器人，回答时请直接以'我看到了'开头，说出你看到的内容。不要加'这张图片'、'图中'等前缀。如果用户问颜色、文字等具体问题，请直接回答。"
            },
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": data_url}},
                    {"type": "text", "text": prompt},
                ],
            }
        ],
    }

    resp = await _vision_client.post(
        VISION_URL,
        headers={"Authorization": f"Bearer {QWEN_VL_API_KEY}", "Content-Type": "application/json"},
        json=payload,
    )
    resp.raise_for_status()
    data = resp.json()

    choice = data.get("choices", [{}])[0]
    message = choice.get("message", {})
    content = message.get("content", "")
    if not content:
        content = "我没看懂这张图片"
    return content

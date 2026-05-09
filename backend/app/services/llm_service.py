import json

import httpx

from app.config import MINIMAX_API_KEY
from app.utils import mqtt_client

# 复用 AsyncClient 避免每次创建连接池开销
_llm_client = httpx.AsyncClient(timeout=30)


async def close_llm_client():
    await _llm_client.aclose()

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "move_wheels",
            "description": "控制机器人的轮子移动，包括前进、后退、左转、右转或停止",
            "parameters": {
                "type": "object",
                "properties": {
                    "direction": {
                        "type": "string",
                        "enum": ["forward", "backward", "left", "right", "circle_left", "circle_right", "stop", "dance"],
                        "description": "移动方向：forward前进 backward后退 left原地左转 right原地右转 circle_left大半径左转 circle_right大半径右转 stop停止 dance跳舞（执行一段预设舞蹈动作序列）",
                    },
                    "speed": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 10,
                        "description": "移动速度，1-10，默认6",
                    },
                    "duration": {
                        "type": "number",
                        "minimum": 0.5,
                        "maximum": 10,
                        "description": "持续时间（秒），默认2秒",
                    },
                },
                "required": ["direction"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "show_emotion",
            "description": "控制机器人眼睛屏幕（LED表情屏）显示图案。仅在用户明确要求展示表情时调用（如用户说'笑一个'、'做个开心的表情'、'哭一个'），平时对话不要主动调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "emotion": {
                        "type": "string",
                        "enum": [
                            "happy", "smile", "idle", "sad", "cry", "embarrassed", "angry", "surprised", "blink",
                            "daze", "fun", "tease", "sorry", "sleep", "standby", "listen",
                            "right", "look_right", "look_left", "black_screen", "error", "music", "glasses", "ignore",
                            "wifi", "bluetooth", "nlu", "connecting", "connected", "light_test",
                            "batt_full", "batt_half", "batt_low", "batt_empty", "charge", "charge_error"
                        ],
                        "description": "控制机器人眼睛屏幕显示表情。严格限制：只能使用上方enum列表中的名称，禁止使用列表外的任何名称（如'cute''cool''shock''thinking''laugh''kiss''heart'等都不支持）。常用情绪：happy开心 smile微笑 sad难过 cry哭泣 embarrassed尴尬/囧 angry生气 surprised吃惊 blink眨眼 daze发呆 fun欢乐 tease逗趣 sorry抱歉 sleep睡觉。系统状态：idle平静 standby待机 listen聆听 right向右看 look_right看右 look_left看左 black_screen黑屏 error错误 music音乐 glasses眼镜 ignore忽略 wifiWiFi bluetooth蓝牙 nlu电波 connecting连接中 connected已连接 light_test灯光测试。电池充电：batt_full满电 batt_half半电 batt_low低电 batt_empty空电 charge充电 charge_error充电故障。",
                    }
                },
                "required": ["emotion"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_volume",
            "description": "调节机器人播放语音的音量大小",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["up", "down", "max", "min", "mute"],
                        "description": "音量调节动作：up增大 down减小 max最大 min最小 mute静音",
                    }
                },
                "required": ["action"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "visual_follow",
            "description": "控制机器人开启或关闭视觉跟随模式。开启后机器人会用摄像头检测人脸并自动转动轮子跟着人走，遇障碍会自动绕开。用于响应'过来'、'来找我'、'跟我走'、'跟着我'、'开启跟随'、'关闭跟随'、'别跟了'等指令。注意：这个功能只是让机器人跟着人移动，不会拍照，也不能用来回答'你看到了什么'之类的问题",
            "parameters": {
                "type": "object",
                "properties": {
                    "enabled": {
                        "type": "boolean",
                        "description": "true 开启跟随，false 关闭跟随",
                    }
                },
                "required": ["enabled"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "take_picture",
            "description": "控制机器人用摄像头拍一张照片，然后用视觉大模型分析图片内容并回答用户问题。专门用于回答'你看到了什么'、'看看这是什么'、'拍张照'、'这是什么颜色'、'图里有几个人'等需要'看'的问题。调用后机器人会拍照并分析图片内容",
            "parameters": {
                "type": "object",
                "properties": {
                    "prompt": {
                        "type": "string",
                        "description": "让视觉模型回答的问题，比如'你看到了什么？'、'这是什么颜色？'、'图中有几个人？'",
                    }
                },
                "required": ["prompt"],
            },
        },
    },
]

SYSTEM_PROMPT = (
    "你是小萝卜机器人，一个可爱的智能硬件，有两个轮子和一块LED眼睛屏幕。"
    "你会用轮子移动、调节音量、显示表情、跟随人或拍照。想执行这些动作时必须调用对应工具函数，不要只在文字里说。"
    "眼睛屏幕可用表情：happy smile sad cry embarrassed angry surprised blink daze fun tease sorry sleep idle standby listen right look_right look_left black_screen error music glasses ignore wifi bluetooth nlu connecting connected light_test batt_full batt_half batt_low batt_empty charge charge_error。"
    "严禁使用列表外的表情名称。只有用户明确要求时才调用show_emotion，平时不要主动调用。"
    "轮子移动：支持前进、后退、左转、右转、大半径转圈、停止，以及跳舞（dance）动作序列。"
    "回答要求："
    "1. 简短有趣，适合小朋友；"
    "2. 严禁在回复中描述自己的表情或情绪状态（禁止说'我开心地笑了'、'我很生气'、'我有点难过'等）；"
    "3. 严禁使用任何emoji、颜文字、符号（如😊🤔😢❤️✨等），只能用纯中文字符和标点；"
    "4. 表达情绪时直接调用show_emotion工具让屏幕显示，文字就当什么都没发生。"
    "5. 对话控制：每次回答完用户后，判断是否需要继续聆听。如果你向用户提问、或明显期待用户继续说话，请在回复末尾添加标记 [CONTINUE]；如果对话已自然结束，请添加标记 [DONE]。标记必须放在回复最后，只添加其中一个。"
    "摄像头功能："
    "- 视觉跟随：用户说'过来/跟我走'时开启跟随，说'别跟了/停下'时关闭。跟随模式不拍照；"
    "- 拍照分析：回答'你看到了什么'、'这是什么颜色'、'图里有几个人'等问题时调用take_picture。"
)


async def chat(user_text: str, device_id: str = None, history=None, memories=None) -> tuple:
    if not MINIMAX_API_KEY:
        return "后端还没配置 minimax key，这是占位回复。", []

    # 前置关键词路由：用户明显在问"看到了什么"或让拍照时，直接调用 take_picture
    vision_keywords = ["看到", "看见", "拍照", "拍张", "这是什么", "看看", "图里", "图片", "照片", "什么颜色", "几个人", "在哪", "长什么样"]
    force_vision = any(kw in user_text for kw in vision_keywords)
    if force_vision:
        prompt = user_text
        if device_id:
            mqtt_client.publish_cmd(device_id, {
                "action": "vision",
                "prompt": prompt,
            })
        return "让我看看！", [{"type": "vision", "prompt": prompt}]

    url = "https://api.minimax.chat/v1/text/chatcompletion_v2"
    headers = {
        "Authorization": f"Bearer {MINIMAX_API_KEY}",
        "Content-Type": "application/json",
    }

    system_content = SYSTEM_PROMPT
    if memories:
        memory_text = "\n".join(f"- {m}" for m in memories)
        system_content += f"\n\n以下是与你相关的记忆，请在回答时参考：\n{memory_text}"

    messages = [{"role": "system", "content": system_content}]
    if history:
        messages.extend(history)
    messages.append({"role": "user", "content": user_text})

    actions = []

    resp = await _llm_client.post(url, headers=headers, json={
        "model": "MiniMax-M2.1",
        "messages": messages,
        "tools": TOOLS,
        "tool_choice": "auto",
    })
    resp.raise_for_status()
    data = resp.json()

    choice = data.get("choices", [{}])[0]
    message = choice.get("message", {})
    tool_calls = message.get("tool_calls", [])

    if tool_calls:
        messages.append(message)

        for tc in tool_calls:
            if tc.get("type") == "function":
                func = tc.get("function", {})
                name = func.get("name", "")
                try:
                    args = json.loads(func.get("arguments", "{}"))
                except Exception:
                    args = {}

                result = ""
                if name == "move_wheels":
                    direction = args.get("direction", "stop")
                    if direction == "dance":
                        if device_id:
                            mqtt_client.publish_cmd(device_id, {
                                "action": "dance",
                            })
                        result = "已执行：跳舞"
                        actions.append({"type": "dance"})
                    else:
                        speed = args.get("speed", 6)
                        duration_sec = args.get("duration", 2)
                        duration = max(1, min(255, int(duration_sec * 10)))
                        if device_id:
                            mqtt_client.publish_cmd(device_id, {
                                "action": "move",
                                "direction": direction,
                                "speed": speed,
                                "duration": duration,
                            })
                        result = f"已执行：轮子{direction}，速度{speed}，持续{duration_sec}秒"
                        actions.append({"type": "move", "direction": direction, "speed": speed, "duration": duration})

                elif name == "show_emotion":
                    emotion_map = {
                        "happy": 0x08, "smile": 0x0A, "idle": 0x09, "charge": 0x26,
                        "listen": 0x0B, "wait": 0x0F, "angry": 0x1B,
                        "sad": 0x01, "cry": 0x0B, "embarrassed": 0x17, "surprised": 0x0C,
                        "blink": 0x1D, "daze": 0x06, "fun": 0x1C, "tease": 0x20,
                        "sorry": 0x21, "sleep": 0x25, "standby": 0x1A,
                        "right": 0x02, "look_right": 0x04, "look_left": 0x05,
                        "black_screen": 0x00, "error": 0x03, "music": 0x07,
                        "glasses": 0x1E, "ignore": 0x24,
                        "wifi": 0x0D, "bluetooth": 0x0E, "nlu": 0x0F,
                        "connecting": 0x11, "connected": 0x12, "light_test": 0x28,
                        "batt_full": 0x13, "batt_half": 0x14, "batt_low": 0x15,
                        "batt_empty": 0x16, "charge_error": 0x27,
                    }
                    emo = args.get("emotion", "happy")
                    if emo not in emotion_map:
                        # LLM 传了无效表情，回退到 idle 并明确告知
                        fallback = "idle"
                        emo_code = emotion_map[fallback]
                        result = f"错误：'{emo}'不是支持的表情，已回退到 {fallback}。请只使用系统定义的表情名称。"
                    else:
                        emo_code = emotion_map[emo]
                        result = f"已切换表情：{emo}"
                    if device_id:
                        mqtt_client.publish_cmd(device_id, {
                            "action": "emotion",
                            "emotion": emo_code,
                        })
                    actions.append({"type": "emotion", "emotion": emo_code})

                elif name == "set_volume":
                    vol_action = args.get("action", "up")
                    if device_id:
                        mqtt_client.publish_cmd(device_id, {
                            "action": "volume",
                            "volume_action": vol_action,
                        })
                    result = f"已调节音量：{vol_action}"
                    actions.append({"type": "volume", "volume_action": vol_action})

                elif name == "visual_follow":
                    enabled = args.get("enabled", False)
                    if device_id:
                        mqtt_client.publish_cmd(device_id, {
                            "action": "follow",
                            "enabled": enabled,
                        })
                    result = "已" + ("开启" if enabled else "关闭") + "视觉跟随"
                    actions.append({"type": "follow", "enabled": enabled})

                elif name == "take_picture":
                    prompt = args.get("prompt", "你看到了什么？")
                    if device_id:
                        mqtt_client.publish_cmd(device_id, {
                            "action": "vision",
                            "prompt": prompt,
                        })
                    result = "正在拍照分析..."
                    actions.append({"type": "vision", "prompt": prompt})

                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.get("id", ""),
                    "content": result,
                })

        # 执行工具后，再调一次 LLM 生成自然确认回复（禁用工具调用）
        resp2 = await _llm_client.post(url, headers=headers, json={
            "model": "MiniMax-M2.1",
            "messages": messages,
            "tool_choice": "none",
        })
        resp2.raise_for_status()
        data2 = resp2.json()

        choice2 = data2.get("choices", [{}])[0]
        message2 = choice2.get("message", {})
        content = message2.get("content", "")

        # 清理 MiniMax 可能输出的 tool_call XML 标签
        if content:
            import re
            content = re.sub(r"<minimax:tool_call>.*?</minimax:tool_call>", "", content, flags=re.DOTALL).strip()
            content = re.sub(r"<invoke[^>]*>.*?</invoke>", "", content, flags=re.DOTALL).strip()

        # 兜底：如果二次调用无内容或只剩余 XML 残渣，使用更自然的 fallback 文案
        if not content:
            confirm_parts = []
            for act in actions:
                t = act.get("type")
                if t == "move":
                    confirm_parts.append(f"好的，我要{act['direction']}啦！")
                elif t == "emotion":
                    confirm_parts.append("我来表演个表情给你看~")
                elif t == "volume":
                    confirm_parts.append("音量已调好！")
                elif t == "follow":
                    confirm_parts.append("我来跟着你！" if act.get("enabled") else "不跟啦！")
                elif t == "vision":
                    confirm_parts.append("让我看看！")
            content = " ".join(confirm_parts)
    else:
        content = message.get("content", "")

    # 解析对话控制标记
    should_continue = True
    if "[DONE]" in content:
        should_continue = False
        content = content.replace("[DONE]", "").strip()
    elif "[CONTINUE]" in content:
        should_continue = True
        content = content.replace("[CONTINUE]", "").strip()

    return content, actions, should_continue

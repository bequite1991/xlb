import json
import os
import time
import threading

import paho.mqtt.client as mqtt

MQTT_BROKER = os.getenv("MQTT_BROKER", "127.0.0.1")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))

robot_status = {}
_robot_status_lock = threading.Lock()


def _cleanup_old_devices():
    """Remove device entries not seen for 7 days to prevent unbounded memory growth."""
    now = time.time()
    cutoff = now - 7 * 24 * 3600
    with _robot_status_lock:
        stale = [did for did, st in robot_status.items() if st.get("last_seen", 0) < cutoff]
        for did in stale:
            del robot_status[did]
        if stale:
            print(f"[mqtt] cleaned up {len(stale)} stale device(s)")


def get_status(device_id: str):
    with _robot_status_lock:
        status = robot_status.get(device_id, {"online": False, "battery": -1, "speaking": False}).copy()
    now = time.time()
    last_seen = status.get("last_seen", 0)
    # If robot hasn't been seen for 30s, consider offline and reset voice_state
    if now - last_seen > 30:
        status["online"] = False
        status["voice_state"] = "idle"
        return status
    # If voice_state hasn't been updated for 60s, reset to idle
    last_voice_state = status.get("last_voice_state", 0)
    if now - last_voice_state > 60:
        status["voice_state"] = "idle"
    # If ota_state hasn't been updated for 120s, clear it
    last_ota_state = status.get("last_ota_state", 0)
    if now - last_ota_state > 120:
        status.pop("ota_state", None)
    return status


def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        print("MQTT connected")
        client.subscribe("robot/+/status")
        client.subscribe("robot/+/heartbeat")
        client.subscribe("robot/+/event")
    else:
        print(f"MQTT connect failed: {rc}")


def on_message(client, userdata, msg):
    try:
        topic = msg.topic
        payload = json.loads(msg.payload.decode())
        parts = topic.split("/")
        if len(parts) >= 3:
            device_id = parts[1]
            subtopic = parts[2]
            with _robot_status_lock:
                if device_id not in robot_status:
                    robot_status[device_id] = {"online": False, "battery": -1, "speaking": False, "last_seen": 0}
                robot_status[device_id]["last_seen"] = time.time()
                if subtopic in ("status", "heartbeat"):
                    robot_status[device_id]["online"] = True
                    if "battery" in payload:
                        robot_status[device_id]["battery"] = payload["battery"]
                    if "speaking" in payload:
                        robot_status[device_id]["speaking"] = payload["speaking"]
                    if "charging" in payload:
                        robot_status[device_id]["charging"] = payload["charging"]
                    if "version_code" in payload:
                        robot_status[device_id]["version_code"] = payload["version_code"]
                elif subtopic == "event":
                    if payload.get("type") == "voice_state":
                        robot_status[device_id]["voice_state"] = payload.get("state", "idle")
                        robot_status[device_id]["last_voice_state"] = time.time()
                    elif payload.get("type") == "setup_mode":
                        if payload.get("enabled", True):
                            robot_status[device_id]["setup_mode"] = True
                            robot_status[device_id]["ap_ssid"] = payload.get("ap_ssid", "")
                            robot_status[device_id]["ap_password"] = payload.get("ap_password", "")
                        else:
                            robot_status[device_id]["setup_mode"] = False
                    elif payload.get("type") == "follow":
                        robot_status[device_id]["follow"] = payload.get("enabled", False)
                    elif payload.get("type") == "volume":
                        robot_status[device_id]["volume"] = payload.get("percent", 0)
                    elif payload.get("type") == "touch":
                        robot_status[device_id]["last_touch"] = payload.get("key", 0)
                    elif payload.get("type") == "ota":
                        robot_status[device_id]["ota_state"] = payload.get("state", "")
                        robot_status[device_id]["last_ota_state"] = time.time()
                    elif payload.get("type") == "wifi_scan":
                        robot_status[device_id]["wifi_networks"] = payload.get("networks", [])
                        robot_status[device_id]["last_wifi_scan"] = time.time()
                    # 存储最近事件用于前端事件流展示
                    recent = robot_status[device_id].get("recent_events", [])
                    event_entry = {"type": payload.get("type"), "time": time.time()}
                    if payload.get("type") == "alarm":
                        event_entry["alarm_type"] = payload.get("alarm_type")
                    elif payload.get("type") == "touch":
                        event_entry["key"] = payload.get("key")
                    elif payload.get("type") == "ota":
                        event_entry["state"] = payload.get("state")
                    recent.append(event_entry)
                    if len(recent) > 20:
                        recent = recent[-20:]
                    robot_status[device_id]["recent_events"] = recent
    except Exception as e:
        print(f"MQTT message error: {e}")


mqttc = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
mqttc.on_connect = on_connect
mqttc.on_message = on_message


def start():
    try:
        mqttc.connect(MQTT_BROKER, MQTT_PORT, 60)
        mqttc.loop_start()
        # 每小时清理一次长期离线的设备状态，防止内存无限增长
        import threading
        def _periodic_cleanup():
            _cleanup_old_devices()
            threading.Timer(3600, _periodic_cleanup).start()
        threading.Timer(3600, _periodic_cleanup).start()
    except Exception as e:
        print(f"MQTT start error: {e}")


def publish_cmd(device_id: str, cmd: dict):
    topic = f"robot/{device_id}/cmd"
    payload = json.dumps(cmd)
    mqttc.publish(topic, payload)
    print(f"MQTT publish {topic}: {payload}")

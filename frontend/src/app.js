import 'mdui/mdui.css';
import 'mdui/components/select.js';
import 'mdui/components/menu-item.js';

const API_BASE = import.meta.env.VITE_API_BASE || window.location.origin;
const DEVICE_ID = localStorage.getItem('device_id') || 'a4a38f538c58bfa2';

async function apiPost(path, body) {
    try {
        const res = await fetch(`${API_BASE}${path}`, {
            method: 'POST',
            headers: { 'Content-Type': typeof body === 'string' ? 'application/x-www-form-urlencoded' : 'application/json' },
            body: typeof body === 'string' ? body : JSON.stringify(body)
        });
        if (!res.ok) {
            console.error('API error status:', res.status);
            return {};
        }
        return await res.json().catch(() => ({}));
    } catch (e) {
        console.error('API error:', e);
        return {};
    }
}

function move(dir) {
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'move', direction: dir, speed: 6, duration: 20 });
}

function stop() {
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'stop' });
}

function sendEmotion(emo) {
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'emotion', emotion: emo });
}

function triggerOta() {
    apiPost(`/api/robot/${DEVICE_ID}/ota`, {});
}

function rebootRobot() {
    if (!confirm('确定要重启机器人吗？')) return;
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'reboot' });
}

function triggerChat() {
    const chatStates = ['prompt', 'listen', 'wait', 'speak'];
    if (chatStates.includes(currentVoiceState)) {
        apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'stop_chat' });
    } else {
        apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'start_chat' });
    }
}

function updateChatButton() {
    const btn = document.getElementById('btn-chat');
    if (!btn) return;
    const chatStates = ['prompt', 'listen', 'wait', 'speak'];
    if (chatStates.includes(currentVoiceState)) {
        btn.textContent = '结束对话';
        btn.classList.add('active');
    } else {
        btn.textContent = '语音对话';
        btn.classList.remove('active');
    }
}

let followEnabled = false;
function toggleFollow() {
    followEnabled = !followEnabled;
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'follow', enabled: followEnabled });
    const btn = document.getElementById('btn-follow');
    if (btn) {
        btn.textContent = followEnabled ? '停止跟随' : '跟随我';
        btn.classList.toggle('active', followEnabled);
    }
}

function triggerVision() {
    const prompt = document.getElementById('vision-prompt').value.trim() || '你看到了什么？';
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'vision', prompt: prompt });
    alert('已触发视觉识别：' + prompt);
}

function setVolume(action) {
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'volume', volume_action: action });
}

function setVolumeValue(value) {
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'volume', volume_value: parseInt(value, 10) });
}

let wifiScanTimer = null;
let lastWifiNetworksStr = '';
let currentVoiceState = 'idle';
let pollStatusPending = false;
let pollVersionPending = false;

function showWifi() {
    if (wifiScanTimer) return;
    document.getElementById('wifi-modal').style.display = 'flex';
    refreshWifiScan();
    wifiScanTimer = setInterval(refreshWifiScan, 5000);
}

function hideWifi() {
    document.getElementById('wifi-modal').style.display = 'none';
    if (wifiScanTimer) {
        clearInterval(wifiScanTimer);
        wifiScanTimer = null;
    }
}

function hideWifiIfOutside(e) {
    if (e.target.id === 'wifi-modal') hideWifi();
}

function refreshWifiScan() {
    apiPost(`/api/robot/${DEVICE_ID}/cmd`, { action: 'scan_wifi' });
}

function onWifiSelectChange() {
    const select = document.getElementById('wifi-select');
    const custom = document.getElementById('wifi-ssid-custom');
    if (!select || !custom) return;
    if (select.value === '__custom__') {
        custom.style.display = 'block';
        custom.focus();
    } else {
        custom.style.display = 'none';
    }
}

function populateWifiDropdown(networks) {
    const select = document.getElementById('wifi-select');
    if (!select) return;
    const newStr = JSON.stringify(networks || []);
    if (newStr === lastWifiNetworksStr) return;
    lastWifiNetworksStr = newStr;
    const currentVal = select.value;
    select.innerHTML = '';
    if (!networks || networks.length === 0) {
        const item = document.createElement('mdui-menu-item');
        item.value = '';
        item.textContent = '未扫描到 Wi-Fi';
        select.appendChild(item);
    } else {
        networks.forEach(net => {
            const item = document.createElement('mdui-menu-item');
            item.value = net.ssid;
            item.textContent = net.ssid + (net.level != null ? ` (${net.level}dBm)` : '');
            select.appendChild(item);
        });
    }
    const customItem = document.createElement('mdui-menu-item');
    customItem.value = '__custom__';
    customItem.textContent = '其他（自定义）...';
    select.appendChild(customItem);

    if (currentVal) {
        select.value = currentVal;
    }
    onWifiSelectChange();
}

async function saveWifi() {
    const select = document.getElementById('wifi-select');
    const custom = document.getElementById('wifi-ssid-custom');
    let ssid = '';
    if (select && select.value === '__custom__' && custom) {
        ssid = custom.value.trim();
    } else if (select) {
        ssid = select.value.trim();
    }
    const password = document.getElementById('wifi-password').value;
    if (!ssid) return alert('请选择或输入 Wi-Fi 名称');
    const btn = document.getElementById('wifi-save-btn');
    const loading = document.getElementById('wifi-loading');
    if (btn) btn.disabled = true;
    if (loading) loading.style.display = 'flex';
    try {
        await apiPost(`/api/robot/${DEVICE_ID}/wifi`, `ssid=${encodeURIComponent(ssid)}&password=${encodeURIComponent(password)}`);
        hideWifi();
        alert('Wi-Fi 配置已下发');
    } catch (e) {
        alert('下发失败，请重试');
    } finally {
        if (btn) btn.disabled = false;
        if (loading) loading.style.display = 'none';
    }
}

const VOICE_STATE_MAP = {
    idle: { text: '待机中', icon: '&#127908;', class: 'state-idle' },
    prompt: { text: '准备中', icon: '&#128226;', class: 'state-prompt' },
    listen: { text: '聆听中', icon: '&#128066;', class: 'state-listen' },
    wait: { text: '思考中', icon: '&#9203;', class: 'state-wait' },
    speak: { text: '回答中', icon: '&#128483;', class: 'state-speak' },
    setup: { text: '配网中', icon: '&#128246;', class: 'state-setup' },
    ota_downloading: { text: '正在下载更新...', icon: '&#128229;', class: 'state-wait' },
    ota_downloaded: { text: '下载完成，等待确认', icon: '&#128268;', class: 'state-prompt' },
    ota_await_confirm: { text: '请按机身按钮确认升级', icon: '&#128268;', class: 'state-prompt' },
};

function updateVoiceState(state) {
    const bar = document.getElementById('voice-state-bar');
    const icon = document.getElementById('voice-icon');
    const text = document.getElementById('voice-text');
    const info = VOICE_STATE_MAP[state] || VOICE_STATE_MAP.idle;
    icon.innerHTML = info.icon;
    text.textContent = info.text;
    bar.className = 'voice-state-bar ' + info.class;
}

async function pollStatus() {
    if (pollStatusPending) return;
    pollStatusPending = true;
    try {
        const res = await fetch(`${API_BASE}/api/robot/${DEVICE_ID}/status`);
        const data = await res.json();
        const dot = document.getElementById('online-dot');
        const text = document.getElementById('online-text');
        const battery = document.getElementById('battery');
        if (data.online) {
            dot.className = 'dot online';
            text.textContent = '在线';
        } else {
            dot.className = 'dot offline';
            text.textContent = '离线';
        }
        battery.textContent = (data.battery != null && data.battery >= 0) ? data.battery + '%' : '--%';
        const chargingEl = document.getElementById('charging');
        if (chargingEl) {
            chargingEl.textContent = data.charging ? '⚡' : '';
        }
        const otaProgress = document.getElementById('ota-progress');
        const otaProgressText = document.getElementById('ota-progress-text');
        // OTA progress display (separate from voice state bar)
        if (data.ota_state) {
            if (otaProgress) otaProgress.style.display = 'flex';
            if (otaProgressText) {
                const map = {
                    downloading: '正在下载更新...',
                    downloaded: '下载完成，准备安装...',
                    installing: '正在安装更新...',
                    installed: '安装完成，正在重启...',
                };
                otaProgressText.textContent = map[data.ota_state] || '更新中...';
            }
        } else {
            if (otaProgress) otaProgress.style.display = 'none';
        }

        // Voice state bar: only voice/setup states, never OTA
        if (data.setup_mode) {
            currentVoiceState = 'setup';
            updateVoiceState('setup');
        } else {
            currentVoiceState = data.voice_state || 'idle';
            updateVoiceState(currentVoiceState);
        }
        updateChatButton();
        // Sync follow button state from backend
        if (typeof data.follow === 'boolean') {
            followEnabled = data.follow;
            const btn = document.getElementById('btn-follow');
            if (btn) {
                btn.textContent = followEnabled ? '停止跟随' : '跟随我';
                btn.classList.toggle('active', followEnabled);
            }
        }
        // Update volume display
        updateVolumeDisplay(data.volume);
        // Update Wi-Fi scan results
        if (data.wifi_networks && Array.isArray(data.wifi_networks)) {
            populateWifiDropdown(data.wifi_networks);
        }
        // Update recent events
        if (data.recent_events && Array.isArray(data.recent_events)) {
            renderEventList(data.recent_events);
        }
    } catch (e) {
        document.getElementById('online-dot').className = 'dot offline';
        document.getElementById('online-text').textContent = '离线';
        updateVoiceState('idle');
        updateVolumeDisplay(null);
    } finally {
        pollStatusPending = false;
    }
}

function escapeHtml(str) {
    if (str == null) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function renderEventList(events) {
    const container = document.getElementById('event-list');
    if (!container) return;
    if (!events || events.length === 0) {
        container.innerHTML = '<div class="event-empty">暂无事件</div>';
        return;
    }
    const typeMap = {
        alarm: '报警',
        touch: '触摸',
        ota: 'OTA',
        voice_state: '语音',
        follow: '跟随',
        volume: '音量',
        speak_done: '说完',
        speak_error: '错误',
    };
    const alarmMap = { 0x11: '低电', 0x22: '空电', 0x44: '障碍' };
    let html = '';
    // show newest first
    for (let i = events.length - 1; i >= 0; i--) {
        const ev = events[i];
        const dt = new Date((ev.time || 0) * 1000);
        const timeStr = dt.getHours().toString().padStart(2, '0') + ':' + dt.getMinutes().toString().padStart(2, '0') + ':' + dt.getSeconds().toString().padStart(2, '0');
        let label = typeMap[ev.type] || ev.type;
        let detail = '';
        if (ev.type === 'alarm' && ev.alarm_type != null) {
            detail = alarmMap[ev.alarm_type] || '0x' + ev.alarm_type.toString(16);
        } else if (ev.type === 'ota' && ev.state) {
            detail = ev.state;
        }
        html += '<div class="event-item"><span class="event-time">' + timeStr + '</span><span class="event-type">' + escapeHtml(label) + '</span>' + (detail ? '<span class="event-detail">' + escapeHtml(detail) + '</span>' : '') + '</div>';
    }
    container.innerHTML = html;
}

function updateVolumeDisplay(volumePercent) {
    const text = document.getElementById('volume-text');
    const icon = document.getElementById('volume-icon');
    const header = document.getElementById('volume-header');
    if (!text) return;
    if (volumePercent == null || volumePercent < 0) {
        text.textContent = '--%';
        if (icon) icon.innerHTML = '&#128266;';
        if (header) header.innerHTML = '&#128266;--%';
        return;
    }
    text.textContent = volumePercent + '%';
    let iconCode = '&#128266;';
    if (volumePercent === 0) {
        iconCode = '&#128263;';
    } else if (volumePercent < 50) {
        iconCode = '&#128264;';
    }
    if (icon) icon.innerHTML = iconCode;
    if (header) header.innerHTML = iconCode + volumePercent + '%';
}

let pollStatusId = setInterval(pollStatus, 2000);
pollStatus();
let pollVersionId = setInterval(pollVersion, 30000);
pollVersion();

document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        clearInterval(pollStatusId);
        clearInterval(pollVersionId);
    } else {
        pollStatusId = setInterval(pollStatus, 2000);
        pollVersionId = setInterval(pollVersion, 30000);
        pollStatus();
        pollVersion();
    }
});

async function pollVersion() {
    if (pollVersionPending) return;
    pollVersionPending = true;
    try {
        const statusRes = await fetch(`${API_BASE}/api/robot/${DEVICE_ID}/status`);
        const statusData = await statusRes.json();
        const currentVersion = statusData.version_code || null;

        const otaRes = await fetch(`${API_BASE}/api/ota/check?version_code=${currentVersion || 0}&device_id=${DEVICE_ID}`);
        const otaData = await otaRes.json();
        const latestVersion = otaData.latest_version || currentVersion;

        const versionInfo = document.getElementById('version-info');
        const currentSpan = document.getElementById('version-current');
        const latestSpan = document.getElementById('version-latest');
        if (!versionInfo || !currentSpan || !latestSpan) return;

        currentSpan.textContent = 'v' + (currentVersion || '--');
        latestSpan.textContent = 'v' + latestVersion;
        versionInfo.style.display = 'block';

        if (latestVersion > currentVersion) {
            versionInfo.classList.add('has-update');
        } else {
            versionInfo.classList.remove('has-update');
        }
    } catch (e) {
        // Silently ignore version check errors
    } finally {
        pollVersionPending = false;
    }
}

function toggleEmotions() {
    const content = document.getElementById('emotion-content');
    const arrow = document.getElementById('emotion-arrow');
    if (!content || !arrow) return;
    const isOpen = content.style.display === 'block';
    content.style.display = isOpen ? 'none' : 'block';
    arrow.classList.toggle('open', !isOpen);
}

function toggleEvents() {
    const content = document.getElementById('event-content');
    const arrow = document.getElementById('event-arrow');
    if (!content || !arrow) return;
    const isOpen = content.style.display !== 'none';
    content.style.display = isOpen ? 'none' : 'block';
    arrow.classList.toggle('open', !isOpen);
}

window.move = move;
window.stop = stop;
window.sendEmotion = sendEmotion;
window.triggerOta = triggerOta;
window.rebootRobot = rebootRobot;
window.triggerChat = triggerChat;
window.toggleFollow = toggleFollow;
window.triggerVision = triggerVision;
window.setVolume = setVolume;
window.setVolumeValue = setVolumeValue;
window.showWifi = showWifi;
window.hideWifi = hideWifi;
window.hideWifiIfOutside = hideWifiIfOutside;
window.saveWifi = saveWifi;
window.refreshWifiScan = refreshWifiScan;
window.onWifiSelectChange = onWifiSelectChange;
window.toggleEmotions = toggleEmotions;
window.toggleEvents = toggleEvents;

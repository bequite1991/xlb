const API_BASE = import.meta.env.VITE_API_BASE || window.location.origin;
const DEVICE_ID = localStorage.getItem('device_id') || 'a4a38f538c58bfa2';

const messagesEl = document.getElementById('chat-messages');
const inputEl = document.getElementById('chat-input');
const statusEl = document.getElementById('chat-status');

let pendingVisionFile = null;
let pendingVisionUrl = null;
let isSending = false;

function appendMessage(role, text, extra) {
    const wrapper = document.createElement('div');
    wrapper.className = 'msg ' + (role === 'user' ? 'msg-user' : 'msg-bot');

    const bubble = document.createElement('div');
    bubble.className = 'bubble';

    if (extra && extra.image) {
        const img = document.createElement('img');
        img.src = extra.image;
        img.className = 'chat-image';
        bubble.appendChild(img);
    }

    const p = document.createElement('p');
    p.textContent = text;
    bubble.appendChild(p);
    wrapper.appendChild(bubble);

    const time = document.createElement('span');
    time.className = 'msg-time';
    const now = new Date();
    time.textContent = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0');
    wrapper.appendChild(time);

    messagesEl.appendChild(wrapper);
    messagesEl.scrollTop = messagesEl.scrollHeight;
}

function appendTyping() {
    const wrapper = document.createElement('div');
    wrapper.className = 'msg msg-bot';
    wrapper.id = 'typing-indicator';
    const bubble = document.createElement('div');
    bubble.className = 'bubble typing';
    bubble.innerHTML = '<span></span><span></span><span></span>';
    wrapper.appendChild(bubble);
    messagesEl.appendChild(wrapper);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return wrapper;
}

function removeTyping() {
    const el = document.getElementById('typing-indicator');
    if (el) el.remove();
}

async function sendChatText() {
    const text = inputEl.value.trim();
    if (!text || isSending) return;
    isSending = true;
    inputEl.value = '';
    appendMessage('user', text);
    const typing = appendTyping();

    try {
        const form = new FormData();
        form.append('device_id', DEVICE_ID);
        form.append('text', text);
        const res = await fetch(`${API_BASE}/api/text_chat`, { method: 'POST', body: form });
        const data = await res.json().catch(() => ({}));
        removeTyping();
        if (data.reply) {
            appendMessage('bot', data.reply);
        } else {
            appendMessage('bot', '机器人没回复，请再试一次');
        }
    } catch (e) {
        removeTyping();
        appendMessage('bot', '网络错误，请检查连接');
    } finally {
        isSending = false;
    }
}

function handleVisionUpload(input) {
    const file = input.files[0];
    if (!file) return;
    if (pendingVisionUrl) {
        URL.revokeObjectURL(pendingVisionUrl);
        pendingVisionUrl = null;
    }
    pendingVisionFile = file;
    pendingVisionUrl = URL.createObjectURL(file);
    document.getElementById('vision-preview-img').src = pendingVisionUrl;
    document.getElementById('vision-preview-modal').style.display = 'flex';
    input.value = '';
}

function hideVisionPreview() {
    document.getElementById('vision-preview-modal').style.display = 'none';
    if (pendingVisionUrl) {
        URL.revokeObjectURL(pendingVisionUrl);
        pendingVisionUrl = null;
    }
    pendingVisionFile = null;
}

function hideVisionPreviewIfOutside(e) {
    if (e.target.id === 'vision-preview-modal') hideVisionPreview();
}

async function confirmVisionUpload() {
    if (!pendingVisionFile || isSending) return;
    isSending = true;
    const prompt = document.getElementById('vision-preview-prompt').value.trim() || '你看到了什么？';
    hideVisionPreview();

    const url = URL.createObjectURL(pendingVisionFile);
    appendMessage('user', '[拍照] ' + prompt, { image: url });
    const typing = appendTyping();

    try {
        const form = new FormData();
        form.append('image', pendingVisionFile);
        form.append('device_id', DEVICE_ID);
        form.append('prompt', prompt);
        const res = await fetch(`${API_BASE}/api/vision_chat`, { method: 'POST', body: form });
        const data = await res.json().catch(() => ({}));
        removeTyping();
        if (data.reply) {
            appendMessage('bot', data.reply);
        } else {
            appendMessage('bot', '识别失败，请再试一次');
        }
    } catch (e) {
        removeTyping();
        appendMessage('bot', '网络错误，请检查连接');
    } finally {
        URL.revokeObjectURL(url);
        pendingVisionFile = null;
        isSending = false;
    }
}

async function pollRobotStatus() {
    try {
        const res = await fetch(`${API_BASE}/api/robot/${DEVICE_ID}/status`, { cache: 'no-store' });
        const data = await res.json();
        statusEl.textContent = data.online ? '在线' : '离线';
        statusEl.style.color = data.online ? '#2ecc71' : '#e74c3c';
    } catch (e) {
        statusEl.textContent = '离线';
        statusEl.style.color = '#e74c3c';
    }
}

let robotStatusTimer = setInterval(pollRobotStatus, 3000);
pollRobotStatus();

document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        clearInterval(robotStatusTimer);
    } else {
        robotStatusTimer = setInterval(pollRobotStatus, 3000);
        pollRobotStatus();
    }
});

window.sendChatText = sendChatText;
window.handleVisionUpload = handleVisionUpload;
window.hideVisionPreview = hideVisionPreview;
window.hideVisionPreviewIfOutside = hideVisionPreviewIfOutside;
window.confirmVisionUpload = confirmVisionUpload;

import 'mdui/mdui.css';
import 'mdui/components/select.js';
import 'mdui/components/menu-item.js';

const API_BASE = import.meta.env.VITE_API_BASE || window.location.origin;
const DEVICE_ID = localStorage.getItem('device_id') || 'a4a38f538c58bfa2';

let allLogs = [];
let logsLoading = false;

async function loadLogs(chatType = '') {
    if (logsLoading) return;
    logsLoading = true;
    const url = `${API_BASE}/api/chat_logs/${DEVICE_ID}?limit=50${chatType ? '&chat_type=' + chatType : ''}`;
    document.getElementById('log-tbody').innerHTML = '<tr><td colspan="4" style="text-align:center;color:rgba(255,255,255,0.4)">加载中...</td></tr>';
    try {
        const res = await fetch(url, { cache: 'no-store' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        allLogs = data.logs || [];
        render();
    } catch (e) {
        console.error('Load logs failed:', e);
        document.getElementById('log-tbody').innerHTML = '<tr><td colspan="4" style="text-align:center;color:rgba(255,255,255,0.4)">加载失败</td></tr>';
    } finally {
        logsLoading = false;
    }
}

function render() {
    renderSummary();
    renderTable();
}

function renderSummary() {
    const container = document.getElementById('stats-summary');
    if (!allLogs.length) {
        container.innerHTML = '<div class="stat-card"><div class="stat-value">-</div><div class="stat-label">暂无数据</div></div>';
        return;
    }
    const totalAvg = Math.round(allLogs.reduce((s, r) => s + (r.total_ms || 0), 0) / allLogs.length);
    const voiceLogs = allLogs.filter(r => r.chat_type === 'voice_chat');
    const asrAvg = voiceLogs.length ? Math.round(voiceLogs.reduce((s, r) => s + (r.asr_ms || 0), 0) / voiceLogs.length) : 0;
    const llmAvg = Math.round(allLogs.reduce((s, r) => s + (r.llm_ms || 0), 0) / allLogs.length);
    const ttsAvg = Math.round(allLogs.reduce((s, r) => s + (r.tts_ms || 0), 0) / allLogs.length);

    container.innerHTML = `
        <div class="stat-card"><div class="stat-value">${totalAvg}ms</div><div class="stat-label">平均总耗时</div></div>
        <div class="stat-card"><div class="stat-value">${asrAvg}ms</div><div class="stat-label">平均 ASR</div></div>
        <div class="stat-card"><div class="stat-value">${llmAvg}ms</div><div class="stat-label">平均 LLM</div></div>
        <div class="stat-card"><div class="stat-value">${ttsAvg}ms</div><div class="stat-label">平均 TTS</div></div>
    `;
}

function escapeHtml(str) {
    if (str == null) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function renderTable() {
    const tbody = document.getElementById('log-tbody');
    if (!allLogs.length) {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:rgba(255,255,255,0.4)">暂无数据</td></tr>';
        return;
    }
    let html = '';
    for (const r of allLogs) {
        const dt = new Date(r.created_at);
        const timeStr = `${dt.getMonth()+1}/${dt.getDate()} ${dt.getHours().toString().padStart(2,'0')}:${dt.getMinutes().toString().padStart(2,'0')}`;
        const typeLabel = { voice_chat: '语音', text_chat: '文字', vision_chat: '视觉' }[r.chat_type] || r.chat_type;
        const total = r.total_ms || 0;
        const hasError = !!r.error;

        let barHtml = '';
        if (!hasError && total > 0) {
            const upload = r.upload_ms || 0;
            const asr = r.asr_ms || 0;
            const llm = r.llm_ms || 0;
            const tts = r.tts_ms || 0;
            const vision = r.vision_ms || 0;
            const max = Math.max(total, 1);
            barHtml = `<div class="timing-bar">
                ${upload > 0 ? `<div class="timing-seg seg-upload" style="width:${(upload/max*100).toFixed(1)}%"></div>` : ''}
                ${asr > 0 ? `<div class="timing-seg seg-asr" style="width:${(asr/max*100).toFixed(1)}%"></div>` : ''}
                ${llm > 0 ? `<div class="timing-seg seg-llm" style="width:${(llm/max*100).toFixed(1)}%"></div>` : ''}
                ${tts > 0 ? `<div class="timing-seg seg-tts" style="width:${(tts/max*100).toFixed(1)}%"></div>` : ''}
                ${vision > 0 ? `<div class="timing-seg seg-vision" style="width:${(vision/max*100).toFixed(1)}%"></div>` : ''}
            </div>`;
        }

        let content = '';
        if (hasError) {
            content = `<span style="color:#e74c3c">${escapeHtml(r.error.substring(0, 60))}</span>`;
        } else if (r.reply_text) {
            content = escapeHtml(r.reply_text.substring(0, 40));
        } else if (r.user_text) {
            content = escapeHtml(r.user_text.substring(0, 40));
        }

        html += `<tr class="${hasError ? 'error' : ''}">
            <td class="log-time">${timeStr}</td>
            <td><span class="log-type">${escapeHtml(typeLabel)}</span></td>
            <td>${total}ms${barHtml}</td>
            <td>${content}</td>
        </tr>`;
    }
    tbody.innerHTML = html;
}

document.getElementById('filter-type').addEventListener('change', (e) => {
    loadLogs(e.target.value);
});

let refreshTimer = setInterval(() => loadLogs(document.getElementById('filter-type').value), 30000);
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        clearInterval(refreshTimer);
    } else {
        refreshTimer = setInterval(() => loadLogs(document.getElementById('filter-type').value), 30000);
        loadLogs(document.getElementById('filter-type').value);
    }
});

loadLogs();

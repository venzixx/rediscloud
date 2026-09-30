// Redis in Java - Web Console Client
let currentKeys = [];
let pollInterval = null;
let commandHistory = [];
let historyIndex = -1;
let activeInspectKey = null;

document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    initStatsPolling();
    initExplorer();
    initTerminal();
    initModals();
    loadKeys();
    loadStats();
});

// ==========================================
// Tabs Management
// ==========================================
function initTabs() {
    const tabs = document.querySelectorAll('.nav-tab');
    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');

            const target = tab.dataset.tab;
            document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
            document.getElementById('pane-' + target).classList.add('active');

            if (target === 'telemetry') {
                loadServerInfo();
            } else if (target === 'terminal') {
                document.getElementById('terminalInput').focus();
            }
        });
    });
}

// ==========================================
// Stats & Telemetry Polling
// ==========================================
function initStatsPolling() {
    const toggle = document.getElementById('autoRefreshToggle');
    const refreshBtn = document.getElementById('refreshBtn');

    function updatePolling() {
        if (toggle.checked) {
            if (!pollInterval) {
                pollInterval = setInterval(() => {
                    loadStats();
                    if (document.getElementById('pane-explorer').classList.contains('active')) {
                        loadKeys(true);
                    }
                }, 2500);
            }
        } else {
            if (pollInterval) {
                clearInterval(pollInterval);
                pollInterval = null;
            }
        }
    }

    toggle.addEventListener('change', updatePolling);
    refreshBtn.addEventListener('click', () => {
        loadStats();
        loadKeys();
        showToast('Refreshed data from Redis');
    });

    updatePolling();
}

async function loadStats() {
    try {
        const res = await fetch('/api/stats');
        if (!res.ok) throw new Error('Failed to fetch stats');
        const stats = await res.json();

        document.getElementById('statTotalKeys').textContent = stats.total_keys ?? 0;
        document.getElementById('statMemory').textContent = stats.used_memory_human ?? '0 B';
        document.getElementById('statMemorySub').textContent = `of ${formatBytes(stats.max_memory_bytes)} max`;
        document.getElementById('statCommands').textContent = Number(stats.total_commands_processed).toLocaleString();
        document.getElementById('statClients').textContent = stats.connected_clients ?? 0;

        // Hit rate
        const hits = stats.keyspace_hits || 0;
        const misses = stats.keyspace_misses || 0;
        const totalOps = hits + misses;
        const hitRate = totalOps > 0 ? ((hits / totalOps) * 100).toFixed(1) + '%' : '100%';
        document.getElementById('statHitRate').textContent = hitRate;
        document.getElementById('statHitsSub').textContent = `${hits} hits / ${misses} miss`;

        // Uptime
        document.getElementById('statUptime').textContent = formatUptime(stats.uptime_in_seconds);

    } catch (e) {
        document.getElementById('serverStatus').textContent = 'Disconnected';
        document.querySelector('.status-dot').style.backgroundColor = '#ff4757';
    }
}

async function loadServerInfo() {
    try {
        const res = await fetch('/api/exec', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: 'INFO' })
        });
        const data = await res.json();
        document.getElementById('rawInfoOutput').textContent = data.output || 'No info returned';
    } catch (e) {
        document.getElementById('rawInfoOutput').textContent = 'Error loading INFO: ' + e.message;
    }
}

document.getElementById('copyInfoBtn').addEventListener('click', () => {
    const text = document.getElementById('rawInfoOutput').textContent;
    navigator.clipboard.writeText(text).then(() => showToast('INFO copied to clipboard'));
});

// ==========================================
// Data Explorer (Keys)
// ==========================================
function initExplorer() {
    const searchInput = document.getElementById('keySearchInput');
    const typeSelect = document.getElementById('typeFilterSelect');
    const flushBtn = document.getElementById('flushDbBtn');

    let debounceTimer;
    searchInput.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => loadKeys(), 250);
    });

    typeSelect.addEventListener('change', () => loadKeys());

    flushBtn.addEventListener('click', async () => {
        if (!confirm('⚠️ Are you sure you want to FLUSH all keys from this Redis database? This cannot be undone.')) {
            return;
        }
        try {
            const res = await fetch('/api/flush', { method: 'POST' });
            if (res.ok) {
                showToast('Database flushed successfully');
                loadKeys();
                loadStats();
            }
        } catch (e) {
            showToast('Error flushing database: ' + e.message, true);
        }
    });
}

async function loadKeys(isSilent = false) {
    const searchInput = document.getElementById('keySearchInput');
    const typeSelect = document.getElementById('typeFilterSelect');
    const tbody = document.getElementById('keysTableBody');

    const pattern = searchInput.value.trim() || '*';
    const type = typeSelect.value;

    try {
        const url = `/api/keys?pattern=${encodeURIComponent(pattern)}&type=${encodeURIComponent(type)}`;
        const res = await fetch(url);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        currentKeys = data.keys || [];

        renderKeysTable(currentKeys);
    } catch (e) {
        if (!isSilent) {
            tbody.innerHTML = `<tr><td colspan="5" class="empty-state"><p style="color: #ff6b81;">Error loading keys: ${e.message}</p></td></tr>`;
        }
    }
}

function renderKeysTable(keys) {
    const tbody = document.getElementById('keysTableBody');
    if (!keys || keys.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="5" class="empty-state">
                    <div class="empty-content">
                        <span style="font-size: 2rem;">🔍</span>
                        <p>No keys found matching your criteria</p>
                        <button class="btn btn-primary btn-sm" onclick="openNewKeyModal()">Create a Key</button>
                    </div>
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = keys.map(k => {
        const typeClass = `badge-${k.type.toLowerCase()}`;
        const ttlLabel = k.ttl === -1 ? 'None (Persist)' : (k.ttl <= 0 ? 'Expired' : `${k.ttl}s`);
        const ttlClass = k.ttl > 0 ? 'has-expiry' : '';

        return `
            <tr>
                <td>
                    <span class="key-code" onclick="inspectKey('${escapeHtml(k.key)}')">${escapeHtml(k.key)}</span>
                </td>
                <td>
                    <span class="type-badge ${typeClass}">${escapeHtml(k.type)}</span>
                </td>
                <td style="color: var(--text-secondary); font-family: var(--font-mono); font-size: 0.85rem;">
                    ${k.size} ${k.type === 'string' ? 'chars' : 'items'}
                </td>
                <td>
                    <span class="ttl-pill ${ttlClass}">⏱️ ${ttlLabel}</span>
                </td>
                <td style="text-align: right;">
                    <button class="btn btn-secondary btn-sm" onclick="inspectKey('${escapeHtml(k.key)}')" title="View / Edit">
                        Inspect
                    </button>
                    <button class="btn btn-outline-danger btn-sm" onclick="deleteKey('${escapeHtml(k.key)}')" title="Delete" style="margin-left: 0.35rem;">
                        &times;
                    </button>
                </td>
            </tr>
        `;
    }).join('');
}

// ==========================================
// Key Inspection & Modification
// ==========================================
async function inspectKey(key) {
    activeInspectKey = key;
    try {
        const res = await fetch(`/api/key?key=${encodeURIComponent(key)}`);
        if (!res.ok) throw new Error('Key not found or expired');
        const details = await res.json();

        document.getElementById('modalKeyName').textContent = details.key;
        const typeBadge = document.getElementById('modalKeyType');
        typeBadge.textContent = details.type.toUpperCase();
        typeBadge.className = `type-badge badge-${details.type.toLowerCase()}`;

        const ttlText = details.ttl === -1 ? 'No Expiry (-1)' : `${details.ttl} seconds`;
        document.getElementById('modalKeyTtl').textContent = ttlText;
        document.getElementById('modalKeySize').textContent = `${details.size} ${details.type === 'string' ? 'bytes' : 'elements'}`;
        document.getElementById('modalTtlInput').value = details.ttl > 0 ? details.ttl : '';

        const container = document.getElementById('modalValueContainer');
        container.innerHTML = '';

        if (details.type === 'string') {
            container.innerHTML = `<textarea id="inspectValueString" class="input-field text-area" rows="8" style="width: 100%; font-family: var(--font-mono);">${escapeHtml(details.value)}</textarea>`;
        } else if (details.type === 'hash') {
            let html = '<table class="keys-table" style="background: var(--bg-base); border-radius: 8px;"><thead><tr><th>Field</th><th>Value</th></tr></thead><tbody>';
            for (const [f, v] of Object.entries(details.value || {})) {
                html += `<tr><td style="font-family: var(--font-mono); color: #58a6ff;">${escapeHtml(f)}</td><td><input type="text" class="input-field hash-field-val" data-field="${escapeHtml(f)}" value="${escapeHtml(v)}" style="width: 100%;"></td></tr>`;
            }
            html += '</tbody></table>';
            container.innerHTML = html;
        } else if (details.type === 'list' || details.type === 'set') {
            const items = details.value || [];
            let html = '<div style="display: flex; flex-direction: column; gap: 0.4rem; max-height: 250px; overflow-y: auto;">';
            items.forEach((item, idx) => {
                html += `<div style="display: flex; align-items: center; gap: 0.5rem; background: var(--bg-base); padding: 0.4rem 0.75rem; border-radius: 4px; font-family: var(--font-mono); font-size: 0.85rem;"><span style="color: var(--text-muted);">${idx + 1}.</span><span>${escapeHtml(item)}</span></div>`;
            });
            html += '</div>';
            container.innerHTML = html;
        }

        document.getElementById('keyDetailModal').classList.add('open');
    } catch (e) {
        showToast('Error inspecting key: ' + e.message, true);
    }
}

function closeKeyModal() {
    document.getElementById('keyDetailModal').classList.remove('open');
    activeInspectKey = null;
}

document.getElementById('modalDeleteKeyBtn').addEventListener('click', () => {
    if (activeInspectKey) {
        deleteKey(activeInspectKey);
        closeKeyModal();
    }
});

document.getElementById('modalSaveKeyBtn').addEventListener('click', async () => {
    if (!activeInspectKey) return;

    const ttlVal = parseInt(document.getElementById('modalTtlInput').value, 10);
    const ttl = !isNaN(ttlVal) && ttlVal > 0 ? ttlVal : null;

    const strArea = document.getElementById('inspectValueString');
    if (strArea) {
        // String update
        const newVal = strArea.value;
        await saveKeyRequest(activeInspectKey, 'string', newVal, ttl);
    } else {
        // Update TTL via EXPIRE command if only TTL changed
        if (ttl !== null) {
            await fetch('/api/exec', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ command: `EXPIRE "${activeInspectKey}" ${ttl}` })
            });
        }
        showToast('Key saved');
        closeKeyModal();
        loadKeys();
    }
});

async function deleteKey(key) {
    if (!confirm(`Are you sure you want to delete key "${key}"?`)) return;
    try {
        const res = await fetch(`/api/key?key=${encodeURIComponent(key)}`, { method: 'DELETE' });
        if (res.ok) {
            showToast(`Key "${key}" deleted`);
            loadKeys();
            loadStats();
        }
    } catch (e) {
        showToast('Error deleting key: ' + e.message, true);
    }
}

// ==========================================
// New Key Modal
// ==========================================
function initModals() {
    document.getElementById('openNewKeyModalBtn').addEventListener('click', openNewKeyModal);

    const typeSelect = document.getElementById('newKeyType');
    typeSelect.addEventListener('change', () => {
        const type = typeSelect.value;
        const label = document.getElementById('newValueLabel');
        const helper = document.getElementById('newKeyHelper');
        const textarea = document.getElementById('newKeyValue');

        if (type === 'string') {
            label.textContent = 'Value (String)';
            helper.textContent = 'Enter text or JSON string';
            textarea.placeholder = 'e.g. Hello Redis!';
        } else if (type === 'hash') {
            label.textContent = 'Field-Value Pairs (JSON)';
            helper.textContent = 'JSON object format: {"field1": "value1", "field2": "value2"}';
            textarea.placeholder = '{\n  "name": "Alice",\n  "role": "admin"\n}';
        } else if (type === 'list') {
            label.textContent = 'List Elements';
            helper.textContent = 'Comma-separated values or JSON array';
            textarea.placeholder = 'item1, item2, item3';
        } else if (type === 'set') {
            label.textContent = 'Set Elements (Unique)';
            helper.textContent = 'Comma-separated unique items';
            textarea.placeholder = 'apple, banana, orange';
        }
    });

    document.getElementById('saveNewKeySubmitBtn').addEventListener('click', async () => {
        const key = document.getElementById('newKeyName').value.trim();
        const type = document.getElementById('newKeyType').value;
        const rawVal = document.getElementById('newKeyValue').value.trim();
        const ttlVal = parseInt(document.getElementById('newKeyTtl').value, 10);
        const ttl = !isNaN(ttlVal) && ttlVal > 0 ? ttlVal : null;

        if (!key) {
            showToast('Key name is required', true);
            return;
        }

        let parsedVal = rawVal;
        if (type === 'hash') {
            try {
                parsedVal = JSON.parse(rawVal);
            } catch (e) {
                showToast('Invalid JSON for hash fields: ' + e.message, true);
                return;
            }
        }

        await saveKeyRequest(key, type, parsedVal, ttl);
        closeNewKeyModal();
    });
}

function openNewKeyModal() {
    document.getElementById('newKeyName').value = '';
    document.getElementById('newKeyValue').value = '';
    document.getElementById('newKeyTtl').value = '';
    document.getElementById('newKeyModal').classList.add('open');
}

function closeNewKeyModal() {
    document.getElementById('newKeyModal').classList.remove('open');
}

async function saveKeyRequest(key, type, value, ttl) {
    try {
        const res = await fetch('/api/key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key, type, value, ttl })
        });
        const data = await res.json();
        if (data.success) {
            showToast(`Key "${key}" saved successfully`);
            loadKeys();
            loadStats();
        } else {
            showToast(data.error || 'Failed to save key', true);
        }
    } catch (e) {
        showToast('Error saving key: ' + e.message, true);
    }
}

// ==========================================
// Web CLI Terminal
// ==========================================
function initTerminal() {
    const form = document.getElementById('terminalForm');
    const input = document.getElementById('terminalInput');

    form.addEventListener('submit', (e) => {
        e.preventDefault();
        const cmd = input.value.trim();
        if (!cmd) return;
        sendTerminalCommand(cmd);
        input.value = '';
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowUp') {
            if (commandHistory.length > 0 && historyIndex > 0) {
                historyIndex--;
                input.value = commandHistory[historyIndex];
            } else if (historyIndex === 0 && commandHistory.length > 0) {
                input.value = commandHistory[0];
            }
            e.preventDefault();
        } else if (e.key === 'ArrowDown') {
            if (historyIndex < commandHistory.length - 1) {
                historyIndex++;
                input.value = commandHistory[historyIndex];
            } else {
                historyIndex = commandHistory.length;
                input.value = '';
            }
            e.preventDefault();
        }
    });
}

async function sendTerminalCommand(cmdStr) {
    commandHistory.push(cmdStr);
    historyIndex = commandHistory.length;

    const output = document.getElementById('terminalOutput');

    // Echo command
    const cmdLine = document.createElement('div');
    cmdLine.className = 'terminal-line cmd';
    cmdLine.textContent = `redis-cli> ${cmdStr}`;
    output.appendChild(cmdLine);

    try {
        const res = await fetch('/api/exec', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: cmdStr })
        });
        const data = await res.json();

        const respLine = document.createElement('div');
        respLine.className = 'terminal-line ' + (data.success ? 'response' : 'error');
        respLine.textContent = data.output ?? data.error ?? '(nil)';
        output.appendChild(respLine);

        // Auto reload keys and stats
        loadStats();
        if (document.getElementById('pane-explorer').classList.contains('active')) {
            loadKeys(true);
        }
    } catch (e) {
        const errLine = document.createElement('div');
        errLine.className = 'terminal-line error';
        errLine.textContent = 'Network error: ' + e.message;
        output.appendChild(errLine);
    }

    output.scrollTop = output.scrollHeight;
}

function clearTerminal() {
    document.getElementById('terminalOutput').innerHTML = '<div class="terminal-line banner">Terminal output cleared.</div>';
}

// ==========================================
// Utilities
// ==========================================
function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function formatUptime(seconds) {
    if (!seconds || seconds <= 0) return '0s';
    const d = Math.floor(seconds / (3600 * 24));
    const h = Math.floor((seconds % (3600 * 24)) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;

    const parts = [];
    if (d > 0) parts.push(d + 'd');
    if (h > 0) parts.push(h + 'h');
    if (m > 0) parts.push(m + 'm');
    parts.push(s + 's');
    return parts.join(' ');
}

function escapeHtml(str) {
    if (typeof str !== 'string') return String(str ?? '');
    return str.replace(/[&<>"']/g, m => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    })[m]);
}

function showToast(message, isError = false) {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.style.borderColor = isError ? 'var(--brand-red)' : 'var(--accent-green)';
    toast.innerHTML = `<span>${isError ? '⚠️' : '✅'}</span> <span>${escapeHtml(message)}</span>`;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

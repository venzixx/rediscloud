// Redis Studio - Database Console Client Engine
let allKeys = [];
let filteredKeys = [];
let selectedKeys = new Set();
let activeTypeFilter = 'ALL';
let activeNamespace = null;
let currentSort = 'key_asc';
let pollTimer = null;
let activeInspectKey = null;
let inspectCache = null;

document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initDatasheetToolbar();
    initInspector();
    initTerminalDrawer();
    initInsertModal();
    initExportImport();
    initLiveSync();

    fetchKeys();
    fetchStats();
});

// ==========================================
// Navigation & Views
// ==========================================
function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item[data-type-filter]');
    navItems.forEach(item => {
        item.addEventListener('click', () => {
            navItems.forEach(i => i.classList.remove('active'));
            item.classList.add('active');

            activeTypeFilter = item.dataset.typeFilter;
            activeNamespace = null; // Clear namespace filter when switching types

            // Clear active namespace highlights
            document.querySelectorAll('.tree-folder').forEach(f => f.classList.remove('active'));

            updateBreadcrumb();
            applyFilterAndRender();
        });
    });

    document.getElementById('refreshNamespacesBtn').addEventListener('click', () => {
        buildNamespaceTree(allKeys);
        showToast('Refreshed namespaces');
    });

    document.getElementById('flushDbBtn').addEventListener('click', async () => {
        if (!confirm('⚠️ Are you sure you want to FLUSH the entire database? All records will be erased.')) {
            return;
        }
        try {
            const res = await fetch('/api/flush', { method: 'POST' });
            if (res.ok) {
                showToast('Database flushed');
                selectedKeys.clear();
                updateBatchBar();
                fetchKeys();
                fetchStats();
            }
        } catch (e) {
            showToast('Flush failed: ' + e.message, true);
        }
    });
}

function updateBreadcrumb() {
    const crumb = document.getElementById('currentViewBreadcrumb');
    if (activeNamespace) {
        crumb.textContent = `Namespace: ${activeNamespace}`;
    } else if (activeTypeFilter === 'ALL') {
        crumb.textContent = 'All Records';
    } else {
        crumb.textContent = activeTypeFilter.toUpperCase() + 's';
    }
}

// ==========================================
// Datasheet Filtering & Sorting
// ==========================================
function initDatasheetToolbar() {
    const searchInput = document.getElementById('filterSearchInput');
    const sortSelect = document.getElementById('sortSelect');
    const selectAllCheck = document.getElementById('selectAllCheckbox');

    let debounce;
    searchInput.addEventListener('input', () => {
        clearTimeout(debounce);
        debounce = setTimeout(() => applyFilterAndRender(), 200);
    });

    sortSelect.addEventListener('change', (e) => {
        currentSort = e.target.value;
        applyFilterAndRender();
    });

    selectAllCheck.addEventListener('change', (e) => {
        if (e.target.checked) {
            filteredKeys.forEach(k => selectedKeys.add(k.key));
        } else {
            selectedKeys.clear();
        }
        updateBatchBar();
        renderTableRows();
    });

    document.getElementById('batchCancelBtn').addEventListener('click', () => {
        selectedKeys.clear();
        selectAllCheck.checked = false;
        updateBatchBar();
        renderTableRows();
    });

    document.getElementById('batchDeleteBtn').addEventListener('click', async () => {
        const count = selectedKeys.size;
        if (count === 0) return;
        if (!confirm(`Are you sure you want to delete ${count} selected records?`)) return;

        try {
            const res = await fetch('/api/batch-delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ keys: Array.from(selectedKeys) })
            });
            const data = await res.json();
            if (data.success) {
                showToast(`Deleted ${data.deletedCount} records`);
                selectedKeys.clear();
                updateBatchBar();
                fetchKeys();
                fetchStats();
            }
        } catch (e) {
            showToast('Batch delete failed: ' + e.message, true);
        }
    });

    document.getElementById('manualRefreshBtn').addEventListener('click', () => {
        fetchKeys();
        fetchStats();
        showToast('Database refreshed');
    });
}

function updateBatchBar() {
    const bar = document.getElementById('batchActionBar');
    const countText = document.getElementById('batchCountText');
    const selectAllCheck = document.getElementById('selectAllCheckbox');

    if (selectedKeys.size > 0) {
        bar.classList.add('show');
        countText.textContent = `${selectedKeys.size} record${selectedKeys.size > 1 ? 's' : ''} selected`;
    } else {
        bar.classList.remove('show');
        selectAllCheck.checked = false;
    }
}

// ==========================================
// Data Fetching & Table Rendering
// ==========================================
async function fetchKeys(isSilent = false) {
    try {
        const res = await fetch('/api/keys');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        allKeys = data.keys || [];

        updateSidebarCounts(allKeys);
        buildNamespaceTree(allKeys);
        applyFilterAndRender();
    } catch (e) {
        if (!isSilent) {
            const tbody = document.getElementById('datasheetBody');
            tbody.innerHTML = `<tr><td colspan="8" class="table-empty"><div class="empty-state"><span style="color:#ff6b81;">Error connecting to Redis: ${escapeHtml(e.message)}</span></div></td></tr>`;
        }
    }
}

function updateSidebarCounts(keys) {
    let strCount = 0, hshCount = 0, lstCount = 0, setCount = 0;
    keys.forEach(k => {
        switch (k.type) {
            case 'string': strCount++; break;
            case 'hash': hshCount++; break;
            case 'list': lstCount++; break;
            case 'set': setCount++; break;
        }
    });

    document.getElementById('countAll').textContent = keys.length;
    document.getElementById('countStrings').textContent = strCount;
    document.getElementById('countHashes').textContent = hshCount;
    document.getElementById('countLists').textContent = lstCount;
    document.getElementById('countSets').textContent = setCount;
}

function buildNamespaceTree(keys) {
    const host = document.getElementById('namespaceTreeList');
    const namespaces = {};

    keys.forEach(k => {
        const idx = k.key.indexOf(':');
        if (idx > 0) {
            const ns = k.key.substring(0, idx + 1);
            namespaces[ns] = (namespaces[ns] || 0) + 1;
        }
    });

    const entries = Object.entries(namespaces);
    if (entries.length === 0) {
        host.innerHTML = '<div class="tree-item empty">No namespaces (: delimiter)</div>';
        return;
    }

    entries.sort((a, b) => a[0].localeCompare(b[0]));
    host.innerHTML = entries.map(([ns, count]) => `
        <div class="tree-folder ${activeNamespace === ns ? 'active' : ''}" data-ns="${escapeHtml(ns)}">
            <iconify-icon icon="lucide:folder" class="f-icon" width="13"></iconify-icon>
            <span class="f-name">${escapeHtml(ns)}</span>
            <span class="f-count">${count}</span>
        </div>
    `).join('');

    host.querySelectorAll('.tree-folder').forEach(el => {
        el.addEventListener('click', () => {
            const ns = el.dataset.ns;
            if (activeNamespace === ns) {
                activeNamespace = null;
                el.classList.remove('active');
            } else {
                host.querySelectorAll('.tree-folder').forEach(f => f.classList.remove('active'));
                el.classList.add('active');
                activeNamespace = ns;
            }
            updateBreadcrumb();
            applyFilterAndRender();
        });
    });
}

function applyFilterAndRender() {
    const searchVal = document.getElementById('filterSearchInput').value.trim().toLowerCase();

    filteredKeys = allKeys.filter(k => {
        // Type filter
        if (activeTypeFilter !== 'ALL' && k.type.toLowerCase() !== activeTypeFilter.toLowerCase()) {
            return false;
        }

        // Namespace filter
        if (activeNamespace && !k.key.startsWith(activeNamespace)) {
            return false;
        }

        // Search text / pattern
        if (searchVal) {
            const cleanPattern = searchVal.replace(/\*/g, '');
            if (!k.key.toLowerCase().includes(cleanPattern)) {
                return false;
            }
        }

        return true;
    });

    // Sort
    filteredKeys.sort((a, b) => {
        switch (currentSort) {
            case 'key_desc': return b.key.localeCompare(a.key);
            case 'size_desc': return b.size - a.size;
            case 'ttl_asc': return (a.ttl === -1 ? 999999999 : a.ttl) - (b.ttl === -1 ? 999999999 : b.ttl);
            case 'created_desc': return b.createdAt - a.createdAt;
            case 'key_asc':
            default:
                return a.key.localeCompare(b.key);
        }
    });

    document.getElementById('recordCounter').textContent = `Showing ${filteredKeys.length} of ${allKeys.length} records`;
    renderTableRows();
}

function renderTableRows() {
    const tbody = document.getElementById('datasheetBody');

    if (filteredKeys.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="8" class="table-empty">
                    <div class="empty-state">
                        <iconify-icon icon="lucide:database-zap" width="36" height="36" style="color: var(--text-dim);"></iconify-icon>
                        <p>No records matching query.</p>
                        <button class="btn btn-primary btn-sm" onclick="openInsertModal()">
                            <iconify-icon icon="lucide:plus" width="13"></iconify-icon>
                            Insert Record
                        </button>
                    </div>
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = filteredKeys.map((k, index) => {
        const isChecked = selectedKeys.has(k.key);
        const typeClass = getTypeBadgeClass(k.type);
        const ttlLabel = k.ttl === -1 ? 'Persistent' : (k.ttl <= 0 ? 'Expired' : `${k.ttl}s remaining`);
        const ttlClass = k.ttl > 0 ? 'expiring' : '';
        const sizeLabel = k.type === 'string' ? `${k.size} B` : `${k.size} items`;

        return `
            <tr class="${isChecked ? 'selected' : ''}" data-key="${escapeHtml(k.key)}">
                <td class="col-checkbox">
                    <input type="checkbox" class="row-checkbox" data-key="${escapeHtml(k.key)}" ${isChecked ? 'checked' : ''}>
                </td>
                <td class="col-num">${index + 1}</td>
                <td class="col-key">
                    <span class="key-clickable" onclick="openInspector('${escapeHtml(k.key)}')">
                        ${escapeHtml(k.key)}
                    </span>
                </td>
                <td class="col-type">
                    <span class="badge-type ${typeClass}">${escapeHtml(k.type)}</span>
                </td>
                <td class="col-preview">
                    <span class="preview-trunc" title="${escapeHtml(k.preview || '')}">${escapeHtml(k.preview || '(empty)')}</span>
                </td>
                <td class="col-size">${sizeLabel}</td>
                <td class="col-ttl">
                    <span class="ttl-text ${ttlClass}">
                        <iconify-icon icon="lucide:clock" width="12" style="vertical-align: middle; margin-right: 3px;"></iconify-icon>${ttlLabel}
                    </span>
                </td>
                <td class="col-actions">
                    <button class="btn btn-secondary btn-sm" onclick="openInspector('${escapeHtml(k.key)}')" title="Inspect / Edit">
                        <iconify-icon icon="lucide:eye" width="12"></iconify-icon>
                    </button>
                    <button class="btn btn-outline-danger btn-sm" onclick="quickDeleteKey('${escapeHtml(k.key)}')" title="Delete" style="margin-left: 2px;">
                        <iconify-icon icon="lucide:trash-2" width="12"></iconify-icon>
                    </button>
                </td>
            </tr>
        `;
    }).join('');

    // Attach row checkbox handlers
    tbody.querySelectorAll('.row-checkbox').forEach(cb => {
        cb.addEventListener('change', (e) => {
            const key = e.target.dataset.key;
            if (e.target.checked) {
                selectedKeys.add(key);
            } else {
                selectedKeys.delete(key);
            }
            updateBatchBar();
            const tr = cb.closest('tr');
            if (tr) tr.classList.toggle('selected', e.target.checked);
        });
    });
}

function getTypeBadgeClass(type) {
    switch (type.toLowerCase()) {
        case 'string': return 'str';
        case 'hash': return 'hsh';
        case 'list': return 'lst';
        case 'set': return 'st';
        default: return '';
    }
}

async function quickDeleteKey(key) {
    if (!confirm(`Delete key "${key}"?`)) return;
    try {
        const res = await fetch(`/api/key?key=${encodeURIComponent(key)}`, { method: 'DELETE' });
        if (res.ok) {
            showToast(`Deleted "${key}"`);
            selectedKeys.delete(key);
            updateBatchBar();
            fetchKeys();
            fetchStats();
        }
    } catch (e) {
        showToast('Error deleting: ' + e.message, true);
    }
}

// ==========================================
// Right Side Record Inspector & Editor
// ==========================================
function initInspector() {
    const drawer = document.getElementById('recordInspector');
    const closeBtn = document.getElementById('closeInspectorBtn');
    const cancelBtn = document.getElementById('inspCancelBtn');

    closeBtn.addEventListener('click', closeInspector);
    cancelBtn.addEventListener('click', closeInspector);

    document.getElementById('inspDeleteRecordBtn').addEventListener('click', () => {
        if (activeInspectKey) {
            quickDeleteKey(activeInspectKey);
            closeInspector();
        }
    });

    document.getElementById('inspSetTtlBtn').addEventListener('click', async () => {
        if (!activeInspectKey) return;
        const val = parseInt(document.getElementById('inspTtlSeconds').value, 10);
        if (isNaN(val) || val <= 0) {
            showToast('Enter valid positive seconds', true);
            return;
        }
        await fetch('/api/exec', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: `EXPIRE "${activeInspectKey}" ${val}` })
        });
        showToast(`TTL updated to ${val}s`);
        openInspector(activeInspectKey);
        fetchKeys();
    });

    document.getElementById('inspPersistBtn').addEventListener('click', async () => {
        if (!activeInspectKey) return;
        await fetch('/api/exec', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: `PERSIST "${activeInspectKey}"` })
        });
        showToast(`Key is now persistent`);
        document.getElementById('inspTtlSeconds').value = '';
        openInspector(activeInspectKey);
        fetchKeys();
    });

    document.getElementById('inspSaveRecordBtn').addEventListener('click', async () => {
        if (!activeInspectKey || !inspectCache) return;

        let payloadValue;
        if (inspectCache.type === 'string') {
            payloadValue = document.getElementById('inspTextValue').value;
        } else if (inspectCache.type === 'hash') {
            const hashObj = {};
            document.querySelectorAll('.hash-table-editor tbody tr').forEach(tr => {
                const f = tr.querySelector('.hash-f-name').value.trim();
                const v = tr.querySelector('.hash-f-val').value;
                if (f) hashObj[f] = v;
            });
            payloadValue = hashObj;
        } else if (inspectCache.type === 'list' || inspectCache.type === 'set') {
            const arr = [];
            document.querySelectorAll('.list-val-input').forEach(inp => {
                if (inp.value.trim()) arr.push(inp.value.trim());
            });
            payloadValue = arr;
        }

        const ttlNum = parseInt(document.getElementById('inspTtlSeconds').value, 10);
        const ttl = !isNaN(ttlNum) && ttlNum > 0 ? ttlNum : null;

        try {
            const res = await fetch('/api/key', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    key: activeInspectKey,
                    type: inspectCache.type,
                    value: payloadValue,
                    ttl: ttl
                })
            });
            const d = await res.json();
            if (d.success) {
                showToast(`Record "${activeInspectKey}" saved successfully`);
                fetchKeys();
                fetchStats();
            } else {
                showToast(d.error || 'Failed to save', true);
            }
        } catch (e) {
            showToast('Save error: ' + e.message, true);
        }
    });
}

async function openInspector(key) {
    activeInspectKey = key;
    try {
        const res = await fetch(`/api/key?key=${encodeURIComponent(key)}`);
        if (!res.ok) throw new Error('Key not found or expired');
        const details = await res.json();
        inspectCache = details;

        document.getElementById('inspKeyTitle').textContent = details.key;
        const typeBadge = document.getElementById('inspTypeBadge');
        typeBadge.textContent = details.type.toUpperCase();
        typeBadge.className = `badge-type ${getTypeBadgeClass(details.type)}`;

        document.getElementById('inspMetaType').textContent = details.type.toUpperCase();
        document.getElementById('inspMetaSize').textContent = details.type === 'string' ? `${details.size} bytes` : `${details.size} items`;
        document.getElementById('inspMetaTtl').textContent = details.ttl === -1 ? 'Persistent (-1)' : `${details.ttl}s`;
        document.getElementById('inspTtlSeconds').value = details.ttl > 0 ? details.ttl : '';

        // Render type-specific editor
        renderInspectorContent(details);

        document.getElementById('recordInspector').classList.add('open');
    } catch (e) {
        showToast('Error loading record: ' + e.message, true);
    }
}

function closeInspector() {
    document.getElementById('recordInspector').classList.remove('open');
    activeInspectKey = null;
    inspectCache = null;
}

function renderInspectorContent(details) {
    const host = document.getElementById('inspectorContentHost');
    const tools = document.getElementById('contentTools');
    tools.innerHTML = '';

    if (details.type === 'string') {
        tools.innerHTML = `<button class="chip" id="fmtJsonBtn"><iconify-icon icon="lucide:code-2" width="12" style="vertical-align: middle;"></iconify-icon> Format JSON</button>`;
        host.innerHTML = `<textarea id="inspTextValue" class="editor-textarea">${escapeHtml(details.value)}</textarea>`;

        document.getElementById('fmtJsonBtn')?.addEventListener('click', () => {
            const ta = document.getElementById('inspTextValue');
            try {
                const parsed = JSON.parse(ta.value);
                ta.value = JSON.stringify(parsed, null, 2);
                showToast('JSON Formatted');
            } catch (e) {
                showToast('Not valid JSON', true);
            }
        });

    } else if (details.type === 'hash') {
        tools.innerHTML = `<button class="chip" id="addHashFieldBtn"><iconify-icon icon="lucide:plus" width="12" style="vertical-align: middle;"></iconify-icon> Add Field</button>`;
        const entries = Object.entries(details.value || {});
        let html = '<table class="hash-table-editor"><thead><tr><th>Field</th><th>Value</th><th style="width:30px;"></th></tr></thead><tbody>';
        entries.forEach(([f, v]) => {
            html += `
                <tr>
                    <td><input type="text" class="hash-input hash-f-name" value="${escapeHtml(f)}"></td>
                    <td><input type="text" class="hash-input hash-f-val" value="${escapeHtml(v)}"></td>
                    <td><button class="drawer-btn" onclick="this.closest('tr').remove()"><iconify-icon icon="lucide:trash-2" width="13"></iconify-icon></button></td>
                </tr>
            `;
        });
        html += '</tbody></table>';
        host.innerHTML = html;

        document.getElementById('addHashFieldBtn')?.addEventListener('click', () => {
            const tbody = host.querySelector('tbody');
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td><input type="text" class="hash-input hash-f-name" placeholder="new_field"></td>
                <td><input type="text" class="hash-input hash-f-val" placeholder="value"></td>
                <td><button class="drawer-btn" onclick="this.closest('tr').remove()"><iconify-icon icon="lucide:trash-2" width="13"></iconify-icon></button></td>
            `;
            tbody.appendChild(tr);
        });

    } else if (details.type === 'list' || details.type === 'set') {
        const isList = details.type === 'list';
        tools.innerHTML = `<button class="chip" id="addListItemBtn"><iconify-icon icon="lucide:plus" width="12" style="vertical-align: middle;"></iconify-icon> Append ${isList ? 'Item' : 'Member'}</button>`;
        const items = details.value || [];
        let html = '<div class="list-editor-container" style="max-height: 280px; overflow-y: auto;">';
        items.forEach((item, idx) => {
            html += `
                <div class="list-item-row">
                    <span class="list-idx">${idx + 1}.</span>
                    <input type="text" class="list-val-input" value="${escapeHtml(item)}">
                    <button class="drawer-btn" onclick="this.closest('.list-item-row').remove()"><iconify-icon icon="lucide:trash-2" width="13"></iconify-icon></button>
                </div>
            `;
        });
        html += '</div>';
        host.innerHTML = html;

        document.getElementById('addListItemBtn')?.addEventListener('click', () => {
            const container = host.querySelector('.list-editor-container');
            const row = document.createElement('div');
            row.className = 'list-item-row';
            const count = container.children.length + 1;
            row.innerHTML = `
                <span class="list-idx">${count}.</span>
                <input type="text" class="list-val-input" placeholder="New ${isList ? 'element' : 'member'}">
                <button class="drawer-btn" onclick="this.closest('.list-item-row').remove()"><iconify-icon icon="lucide:trash-2" width="13"></iconify-icon></button>
            `;
            container.appendChild(row);
        });
    }
}

// ==========================================
// Bottom Dockable Web CLI Drawer
// ==========================================
function initTerminalDrawer() {
    const drawer = document.getElementById('terminalDrawer');
    const toggleBtn = document.getElementById('toggleTerminalBtn');
    const closeBtn = document.getElementById('drawerCloseBtn');
    const expandBtn = document.getElementById('drawerExpandBtn');
    const form = document.getElementById('drawerForm');
    const input = document.getElementById('drawerInput');

    toggleBtn.addEventListener('click', () => {
        drawer.classList.toggle('collapsed');
        if (!drawer.classList.contains('collapsed')) {
            input.focus();
        }
    });

    closeBtn.addEventListener('click', () => drawer.classList.add('collapsed'));

    expandBtn.addEventListener('click', () => {
        drawer.classList.toggle('expanded');
    });

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const cmd = input.value.trim();
        if (!cmd) return;
        await execCliCommand(cmd);
        input.value = '';
    });
}

async function execCliShortcut(cmd) {
    const drawer = document.getElementById('terminalDrawer');
    drawer.classList.remove('collapsed');
    await execCliCommand(cmd);
}

async function execCliCommand(cmdStr) {
    const out = document.getElementById('drawerOutput');

    const cmdLine = document.createElement('div');
    cmdLine.className = 'term-line cmd';
    cmdLine.textContent = `redis-cli> ${cmdStr}`;
    out.appendChild(cmdLine);

    try {
        const res = await fetch('/api/exec', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: cmdStr })
        });
        const data = await res.json();

        const respLine = document.createElement('div');
        respLine.className = 'term-line ' + (data.success ? 'resp' : 'err');
        respLine.textContent = data.output ?? data.error ?? '(nil)';
        out.appendChild(respLine);

        // Auto reload table and stats so user sees immediate results
        fetchKeys(true);
        fetchStats();
    } catch (e) {
        const errLine = document.createElement('div');
        errLine.className = 'term-line err';
        errLine.textContent = 'Network error: ' + e.message;
        out.appendChild(errLine);
    }

    out.scrollTop = out.scrollHeight;
}

function clearCliTerminal() {
    document.getElementById('drawerOutput').innerHTML = '<div class="term-line welcome">Terminal output cleared.</div>';
}

// ==========================================
// Export & Import
// ==========================================
function initExportImport() {
    const exportBtn = document.getElementById('exportDataBtn');
    const importBtn = document.getElementById('importDataBtn');
    const importFileInput = document.getElementById('importFileInput');

    exportBtn.addEventListener('click', async () => {
        try {
            const res = await fetch('/api/export');
            const data = await res.json();
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `redis_dump_${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
            a.click();
            URL.revokeObjectURL(url);
            showToast('Database exported to JSON');
        } catch (e) {
            showToast('Export failed: ' + e.message, true);
        }
    });

    importBtn.addEventListener('click', () => importFileInput.click());

    importFileInput.addEventListener('change', async (e) => {
        const file = e.target.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = async (evt) => {
            try {
                const json = JSON.parse(evt.target.result);
                const res = await fetch('/api/import', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(json)
                });
                const d = await res.json();
                if (d.success) {
                    showToast(`Imported ${d.importedCount} records successfully`);
                    fetchKeys();
                    fetchStats();
                } else {
                    showToast(d.error || 'Import failed', true);
                }
            } catch (err) {
                showToast('Invalid JSON file: ' + err.message, true);
            }
        };
        reader.readAsText(file);
        e.target.value = '';
    });
}

// ==========================================
// Insert Record Modal
// ==========================================
function initInsertModal() {
    document.getElementById('openInsertModalBtn').addEventListener('click', openInsertModal);

    const typeSel = document.getElementById('insType');
    typeSel.addEventListener('change', () => {
        const t = typeSel.value;
        const lbl = document.getElementById('insValueLabel');
        const hint = document.getElementById('insValueHint');
        const area = document.getElementById('insValue');

        if (t === 'string') {
            lbl.textContent = 'Value (String) *';
            hint.textContent = 'Plain text or JSON payload';
            area.placeholder = 'e.g. Hello Redis!';
        } else if (t === 'hash') {
            lbl.textContent = 'Fields (JSON Object) *';
            hint.textContent = 'JSON object format: {"field": "value"}';
            area.placeholder = '{\n  "name": "Alice",\n  "role": "admin"\n}';
        } else if (t === 'list') {
            lbl.textContent = 'List Items (Comma separated) *';
            hint.textContent = 'Comma-separated string elements';
            area.placeholder = 'task_1, task_2, task_3';
        } else if (t === 'set') {
            lbl.textContent = 'Set Members (Comma separated) *';
            hint.textContent = 'Unique elements separated by commas';
            area.placeholder = 'member_a, member_b, member_c';
        }
    });

    document.getElementById('insSubmitBtn').addEventListener('click', async () => {
        const key = document.getElementById('insKey').value.trim();
        const type = document.getElementById('insType').value;
        const rawVal = document.getElementById('insValue').value.trim();
        const ttlNum = parseInt(document.getElementById('insTtl').value, 10);
        const ttl = !isNaN(ttlNum) && ttlNum > 0 ? ttlNum : null;

        if (!key) {
            showToast('Key is required', true);
            return;
        }

        let parsed = rawVal;
        if (type === 'hash') {
            try {
                parsed = JSON.parse(rawVal);
            } catch (e) {
                showToast('Invalid JSON for hash: ' + e.message, true);
                return;
            }
        }

        try {
            const res = await fetch('/api/key', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ key, type, value: parsed, ttl })
            });
            const data = await res.json();
            if (data.success) {
                showToast(`Record "${key}" inserted`);
                closeInsertModal();
                fetchKeys();
                fetchStats();
            } else {
                showToast(data.error || 'Failed to insert', true);
            }
        } catch (e) {
            showToast('Insert error: ' + e.message, true);
        }
    });
}

function openInsertModal() {
    document.getElementById('insKey').value = '';
    document.getElementById('insValue').value = '';
    document.getElementById('insTtl').value = '';
    document.getElementById('insertModal').classList.add('open');
}

function closeInsertModal() {
    document.getElementById('insertModal').classList.remove('open');
}

// ==========================================
// Telemetry & Live Polling
// ==========================================
function initLiveSync() {
    const chk = document.getElementById('autoPollCheck');
    function syncTimer() {
        if (chk.checked) {
            if (!pollTimer) {
                pollTimer = setInterval(() => {
                    fetchStats();
                    fetchKeys(true);
                }, 3000);
            }
        } else {
            if (pollTimer) {
                clearInterval(pollTimer);
                pollTimer = null;
            }
        }
    }
    chk.addEventListener('change', syncTimer);
    syncTimer();
}

async function fetchStats() {
    try {
        const res = await fetch('/api/stats');
        if (!res.ok) return;
        const stats = await res.json();

        document.getElementById('sbMemory').textContent = stats.used_memory_human || '0 B';
        document.getElementById('sbCommands').textContent = stats.total_commands_processed || 0;
        document.getElementById('sbUptime').textContent = formatUptimeShort(stats.uptime_in_seconds);
    } catch (ignored) {}
}

function formatUptimeShort(sec) {
    if (!sec || sec <= 0) return '0s';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    if (m > 0) return `${m}m ${s}s`;
    return `${s}s`;
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

function showToast(msg, isErr = false) {
    const shelf = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = 'toast-item';
    toast.style.borderColor = isErr ? 'var(--brand-red)' : 'var(--accent-green)';
    toast.innerHTML = `<span><iconify-icon icon="${isErr ? 'lucide:alert-circle' : 'lucide:check-circle-2'}" width="16" style="color: ${isErr ? 'var(--brand-red)' : 'var(--accent-green)'}; vertical-align: middle;"></iconify-icon></span> <span>${escapeHtml(msg)}</span>`;
    shelf.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 250);
    }, 2800);
}

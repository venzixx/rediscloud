// Redis Studio - Database Console Client Engine
let currentPage = 1;
let pageSize = 50;
let totalRecords = 0;
let totalPages = 1;
let currentPageKeys = [];
let selectedKeys = new Set();
let activeTypeFilter = 'ALL';
let activeNamespace = null;
let currentSort = 'key_asc';
let currentSearch = '';
let pollTimer = null;
let activeInspectKey = null;
let inspectCache = null;

let activeVirtualDb = 'default';
let databasesCache = [];
let tenantsCache = [];
let activeConnectTenantUsername = 'admin';
let activeConnectTab = 'prisma';

// User & Database Hub State
let currentUser = null;
let currentSessionToken = localStorage.getItem('redis_studio_session') || '';
let userDatabases = { myDatabases: [], sharedWithMe: [] };
let activeDbInstance = null;
let activeDatabaseRole = 'OWNER';
let activeDbTab = 'my';
let activeShareDbId = null;

document.addEventListener('DOMContentLoaded', () => {
    initAuth();
    initDatabaseHub();
    initNavigation();
    initVirtualDatabaseSelector();
    initDatasheetToolbar();
    initPagination();
    initInspector();
    initTerminalDrawer();
    initInsertModal();
    initExportImport();
    initLiveSync();
    initCloudApiPlayground();
    initBenchmarkEngine();
    initTenantsPanel();
    initConnectModal();

    fetchKeys();
    fetchStats();
    loadTenants();
});

// ==========================================
// Navigation & Views
// ==========================================
function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item[data-view-pane]');
    navItems.forEach(item => {
        item.addEventListener('click', () => {
            document.querySelectorAll('.sidebar .nav-item').forEach(i => i.classList.remove('active'));
            item.classList.add('active');

            const targetPaneId = item.dataset.viewPane;
            document.querySelectorAll('.studio-pane').forEach(p => p.style.display = 'none');
            const targetPane = document.getElementById(targetPaneId);
            if (targetPane) targetPane.style.display = 'flex';

            if (targetPaneId === 'pane-datasheet') {
                if (item.dataset.typeFilter) {
                    activeTypeFilter = item.dataset.typeFilter;
                    activeNamespace = null;
                    currentPage = 1;
                    document.querySelectorAll('.tree-folder').forEach(f => f.classList.remove('active'));
                }
                updateBreadcrumb();
                fetchKeys();
            } else if (targetPaneId === 'pane-databases' || targetPaneId === 'pane-tenants') {
                updateBreadcrumb('Database Hub & Team Sharing');
                loadUserDatabases();
            } else if (targetPaneId === 'pane-cloud-api') {
                updateBreadcrumb('Cloud REST API & RLS');
                loadAclUsers();
            } else if (targetPaneId === 'pane-benchmark') {
                updateBreadcrumb('Stress Benchmark');
            }
        });
    });

    document.getElementById('refreshNamespacesBtn').addEventListener('click', () => {
        fetchKeys();
        showToast('Refreshed namespaces');
    });

    document.getElementById('flushDbBtn').addEventListener('click', async () => {
        const dbName = activeVirtualDb === 'default' ? 'db0' : activeVirtualDb;
        if (!confirm(`⚠️ Are you sure you want to FLUSH database [${dbName}]? All records in this keyspace will be erased.`)) {
            return;
        }
        try {
            const endpoint = `/api/flush${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`;
            const res = await fetch(endpoint, { method: 'POST' });
            if (res.ok) {
                showToast(`Database [${dbName}] flushed`);
                selectedKeys.clear();
                updateBatchBar();
                currentPage = 1;
                fetchKeys();
                fetchStats();
            }
        } catch (e) {
            showToast('Flush failed: ' + e.message, true);
        }
    });
}

function updateBreadcrumb(customTitle) {
    const crumb = document.getElementById('currentViewBreadcrumb');
    if (customTitle) {
        crumb.textContent = customTitle;
    } else if (activeNamespace) {
        crumb.textContent = `Namespace: ${activeNamespace}`;
    } else if (activeTypeFilter === 'ALL') {
        crumb.textContent = 'All Records';
    } else {
        crumb.textContent = activeTypeFilter.toUpperCase() + 's';
    }
}

// ==========================================
// Datasheet Filtering, Sorting & Toolbar
// ==========================================
function initDatasheetToolbar() {
    const searchInput = document.getElementById('filterSearchInput');
    const sortSelect = document.getElementById('sortSelect');
    const selectAllCheck = document.getElementById('selectAllCheckbox');

    let debounce;
    searchInput.addEventListener('input', () => {
        clearTimeout(debounce);
        debounce = setTimeout(() => {
            currentSearch = searchInput.value.trim();
            currentPage = 1;
            fetchKeys();
        }, 250);
    });

    sortSelect.addEventListener('change', (e) => {
        currentSort = e.target.value;
        currentPage = 1;
        fetchKeys();
    });

    selectAllCheck.addEventListener('change', (e) => {
        if (e.target.checked) {
            currentPageKeys.forEach(k => selectedKeys.add(k.key));
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
                body: JSON.stringify({ keys: Array.from(selectedKeys), db: activeVirtualDb })
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
        if (selectAllCheck) selectAllCheck.checked = false;
    }
}

// ==========================================
// Datasheet Pagination
// ==========================================
function initPagination() {
    const firstBtn = document.getElementById('pagFirstBtn');
    const prevBtn = document.getElementById('pagPrevBtn');
    const nextBtn = document.getElementById('pagNextBtn');
    const lastBtn = document.getElementById('pagLastBtn');
    const jumpInput = document.getElementById('pagJumpInput');
    const sizeSelect = document.getElementById('pagPageSizeSelect');

    firstBtn.addEventListener('click', () => {
        if (currentPage > 1) {
            currentPage = 1;
            fetchKeys();
        }
    });

    prevBtn.addEventListener('click', () => {
        if (currentPage > 1) {
            currentPage--;
            fetchKeys();
        }
    });

    nextBtn.addEventListener('click', () => {
        if (currentPage < totalPages) {
            currentPage++;
            fetchKeys();
        }
    });

    lastBtn.addEventListener('click', () => {
        if (currentPage < totalPages) {
            currentPage = totalPages;
            fetchKeys();
        }
    });

    jumpInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            let p = parseInt(jumpInput.value, 10);
            if (!isNaN(p) && p >= 1 && p <= totalPages && p !== currentPage) {
                currentPage = p;
                fetchKeys();
            } else {
                jumpInput.value = currentPage;
            }
        }
    });

    jumpInput.addEventListener('blur', () => {
        let p = parseInt(jumpInput.value, 10);
        if (!isNaN(p) && p >= 1 && p <= totalPages && p !== currentPage) {
            currentPage = p;
            fetchKeys();
        } else {
            jumpInput.value = currentPage;
        }
    });

    sizeSelect.addEventListener('change', (e) => {
        pageSize = parseInt(e.target.value, 10) || 50;
        currentPage = 1;
        fetchKeys();
    });
}

function updatePaginationBar() {
    const rangeInfo = document.getElementById('pagRangeInfo');
    const curPageNum = document.getElementById('pagCurrentPageNum');
    const totPageNum = document.getElementById('pagTotalPagesNum');
    const jumpInput = document.getElementById('pagJumpInput');
    const firstBtn = document.getElementById('pagFirstBtn');
    const prevBtn = document.getElementById('pagPrevBtn');
    const nextBtn = document.getElementById('pagNextBtn');
    const lastBtn = document.getElementById('pagLastBtn');

    const start = totalRecords === 0 ? 0 : (currentPage - 1) * pageSize + 1;
    const end = Math.min(currentPage * pageSize, totalRecords);

    if (rangeInfo) rangeInfo.textContent = `Showing ${start.toLocaleString()} – ${end.toLocaleString()} of ${totalRecords.toLocaleString()} records`;
    if (curPageNum) curPageNum.textContent = currentPage.toLocaleString();
    if (totPageNum) totPageNum.textContent = totalPages.toLocaleString();
    if (jumpInput) {
        jumpInput.value = currentPage;
        jumpInput.max = totalPages;
    }

    if (firstBtn) firstBtn.disabled = (currentPage <= 1);
    if (prevBtn) prevBtn.disabled = (currentPage <= 1);
    if (nextBtn) nextBtn.disabled = (currentPage >= totalPages);
    if (lastBtn) lastBtn.disabled = (currentPage >= totalPages);
}

// ==========================================
// Data Fetching & Table Rendering
// ==========================================
async function fetchKeys(isSilent = false) {
    try {
        const params = new URLSearchParams({
            page: currentPage,
            limit: pageSize,
            sort: currentSort
        });
        if (activeTypeFilter !== 'ALL') params.append('type', activeTypeFilter);
        if (activeNamespace) params.append('namespace', activeNamespace);
        if (currentSearch) params.append('pattern', currentSearch);
        if (activeVirtualDb && activeVirtualDb !== 'default' && activeVirtualDb !== 'db0') {
            params.append('db', activeVirtualDb);
        }

        const res = await fetch(`/api/keys?${params.toString()}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();

        currentPage = data.page || 1;
        pageSize = data.limit || 50;
        totalRecords = data.total || 0;
        totalPages = data.totalPages || 1;
        currentPageKeys = data.keys || [];

        updateSidebarCounts(data.typeCounts || {});
        buildNamespaceTree(data.namespaces || []);
        renderTableRows();
        updatePaginationBar();

        const counterEl = document.getElementById('recordCounter');
        if (counterEl) {
            counterEl.textContent = `Page ${currentPage} of ${totalPages} (${totalRecords.toLocaleString()} records)`;
        }
    } catch (e) {
        if (!isSilent) {
            const tbody = document.getElementById('datasheetBody');
            if (tbody) {
                tbody.innerHTML = `<tr><td colspan="8" class="table-empty"><div class="empty-state"><span style="color:#ff6b81;">Error connecting to Redis: ${escapeHtml(e.message)}</span></div></td></tr>`;
            }
        }
    }
}

function updateSidebarCounts(counts) {
    const elAll = document.getElementById('countAll');
    const elStr = document.getElementById('countStrings');
    const elHsh = document.getElementById('countHashes');
    const elLst = document.getElementById('countLists');
    const elSet = document.getElementById('countSets');

    if (elAll) elAll.textContent = (counts.ALL || 0).toLocaleString();
    if (elStr) elStr.textContent = (counts.string || 0).toLocaleString();
    if (elHsh) elHsh.textContent = (counts.hash || 0).toLocaleString();
    if (elLst) elLst.textContent = (counts.list || 0).toLocaleString();
    if (elSet) elSet.textContent = (counts.set || 0).toLocaleString();
}

function buildNamespaceTree(namespaces) {
    const host = document.getElementById('namespaceTreeList');
    if (!host) return;

    if (!namespaces || namespaces.length === 0) {
        host.innerHTML = '<div class="tree-item empty">No namespaces (: delimiter)</div>';
        return;
    }

    host.innerHTML = namespaces.map(item => `
        <div class="tree-folder ${activeNamespace === item.name ? 'active' : ''}" data-ns="${escapeHtml(item.name)}">
            <iconify-icon icon="lucide:folder" class="f-icon" width="13"></iconify-icon>
            <span class="f-name">${escapeHtml(item.name)}</span>
            <span class="f-count">${item.count.toLocaleString()}</span>
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
            currentPage = 1;
            updateBreadcrumb();
            fetchKeys();
        });
    });
}

function renderTableRows() {
    const tbody = document.getElementById('datasheetBody');
    if (!tbody) return;

    if (currentPageKeys.length === 0) {
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

    tbody.innerHTML = currentPageKeys.map((k, index) => {
        const isChecked = selectedKeys.has(k.key);
        const typeClass = getTypeBadgeClass(k.type);
        const ttlLabel = k.ttl === -1 ? 'Persistent' : (k.ttl <= 0 ? 'Expired' : `${k.ttl}s remaining`);
        const ttlClass = k.ttl > 0 ? 'expiring' : '';
        const sizeLabel = k.type === 'string' ? `${k.size} B` : `${k.size} items`;
        const globalIndex = (currentPage - 1) * pageSize + index + 1;

        return `
            <tr class="${isChecked ? 'selected' : ''}" data-key="${escapeHtml(k.key)}">
                <td class="col-checkbox">
                    <input type="checkbox" class="row-checkbox" data-key="${escapeHtml(k.key)}" ${isChecked ? 'checked' : ''}>
                </td>
                <td class="col-num">${globalIndex}</td>
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
        const endpoint = `/api/key?key=${encodeURIComponent(key)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`;
        const res = await fetch(endpoint, { method: 'DELETE' });
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
            body: JSON.stringify({ command: `EXPIRE "${activeInspectKey}" ${val}`, db: activeVirtualDb })
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
            body: JSON.stringify({ command: `PERSIST "${activeInspectKey}"`, db: activeVirtualDb })
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
                    ttl: ttl,
                    db: activeVirtualDb
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
        const endpoint = `/api/key?key=${encodeURIComponent(key)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`;
        const res = await fetch(endpoint);
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
            body: JSON.stringify({ command: cmdStr, db: activeVirtualDb })
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
            const endpoint = `/api/export${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`;
            const res = await fetch(endpoint);
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
                    body: JSON.stringify({ ...json, db: activeVirtualDb })
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
                body: JSON.stringify({ key, type, value: parsed, ttl, db: activeVirtualDb })
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
        const endpoint = `/api/stats${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`;
        const res = await fetch(endpoint);
        if (!res.ok) return;
        const stats = await res.json();

        document.getElementById('sbMemory').textContent = stats.used_memory_human || '0 B';
        document.getElementById('sbCommands').textContent = (stats.total_commands_processed || 0).toLocaleString();
        document.getElementById('sbUptime').textContent = formatUptimeShort(stats.uptime_in_seconds);

        if (stats.databases) {
            databasesCache = stats.databases;
            updateVirtualDatabaseSelector(databasesCache, stats.activeDb);
        }
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

// ==========================================
// Cloud REST API & RLS Playground
// ==========================================
let aclUsersCache = [];

function initCloudApiPlayground() {
    const endpointSelect = document.getElementById('apiEndpointSelect');
    const tokenSelect = document.getElementById('apiTokenSelect');
    const customTokenInput = document.getElementById('apiCustomTokenInput');
    const customTokenGroup = document.getElementById('apiCustomTokenGroup');
    const targetKeyInput = document.getElementById('apiTargetKey');
    const keyInputGroup = document.getElementById('apiKeyInputGroup');
    const requestBodyArea = document.getElementById('apiRequestBody');
    const bodyGroup = document.getElementById('apiBodyGroup');
    const executeBtn = document.getElementById('apiExecuteBtn');
    const refreshAclBtn = document.getElementById('refreshAclBtn');
    const copyCurlBtn = document.getElementById('copyCurlBtn');

    if (!endpointSelect) return;

    tokenSelect.addEventListener('change', () => {
        if (tokenSelect.value === 'custom') {
            customTokenGroup.style.display = 'flex';
        } else {
            customTokenGroup.style.display = 'none';
        }
        updateCurlSnippet();
    });

    if (customTokenInput) customTokenInput.addEventListener('input', updateCurlSnippet);
    if (targetKeyInput) targetKeyInput.addEventListener('input', updateCurlSnippet);
    if (requestBodyArea) requestBodyArea.addEventListener('input', updateCurlSnippet);

    endpointSelect.addEventListener('change', () => {
        const ep = endpointSelect.value;
        switch (ep) {
            case 'GET_KEY':
                keyInputGroup.style.display = 'flex';
                bodyGroup.style.display = 'none';
                break;
            case 'SET_KEY':
                keyInputGroup.style.display = 'none';
                bodyGroup.style.display = 'flex';
                requestBodyArea.value = JSON.stringify({
                    key: targetKeyInput.value || "app:user:101",
                    value: "{\"name\": \"Alice\", \"role\": \"developer\"}",
                    ttl: 3600
                }, null, 2);
                break;
            case 'LPUSH':
                keyInputGroup.style.display = 'none';
                bodyGroup.style.display = 'flex';
                requestBodyArea.value = JSON.stringify({
                    key: targetKeyInput.value || "app:queue:tasks",
                    values: ["task_build_image", "task_run_tests"]
                }, null, 2);
                break;
            case 'LRANGE':
                keyInputGroup.style.display = 'flex';
                bodyGroup.style.display = 'none';
                break;
            case 'HSET':
                keyInputGroup.style.display = 'none';
                bodyGroup.style.display = 'flex';
                requestBodyArea.value = JSON.stringify({
                    key: targetKeyInput.value || "app:settings:theme",
                    fields: { "mode": "dark", "fontSize": "14px" }
                }, null, 2);
                break;
            case 'HGETALL':
                keyInputGroup.style.display = 'flex';
                bodyGroup.style.display = 'none';
                break;
            case 'DEL_KEY':
                keyInputGroup.style.display = 'flex';
                bodyGroup.style.display = 'none';
                break;
            case 'PIPELINE':
                keyInputGroup.style.display = 'none';
                bodyGroup.style.display = 'flex';
                requestBodyArea.value = JSON.stringify({
                    commands: [
                        ["SET", "app:counter", "100"],
                        ["INCR", "app:counter"],
                        ["GET", "app:counter"]
                    ]
                }, null, 2);
                break;
            case 'RAW_CMD':
                keyInputGroup.style.display = 'none';
                bodyGroup.style.display = 'flex';
                requestBodyArea.value = JSON.stringify({
                    command: "SET",
                    args: ["app:test:key", "Hello Cloud!"]
                }, null, 2);
                break;
        }
        updateCurlSnippet();
    });

    if (executeBtn) executeBtn.addEventListener('click', executeApiRequest);
    if (refreshAclBtn) refreshAclBtn.addEventListener('click', loadAclUsers);
    if (copyCurlBtn) {
        copyCurlBtn.addEventListener('click', () => {
            const text = document.getElementById('apiCurlSnippet').textContent;
            navigator.clipboard.writeText(text).then(() => showToast('Copied cURL command!'));
        });
    }

    updateCurlSnippet();
}

function getSelectedApiToken() {
    const sel = document.getElementById('apiTokenSelect').value;
    if (sel === 'custom') {
        return document.getElementById('apiCustomTokenInput').value.trim() || 'custom_token';
    }
    return sel;
}

function updateCurlSnippet() {
    const ep = document.getElementById('apiEndpointSelect')?.value || 'GET_KEY';
    const token = getSelectedApiToken();
    const key = document.getElementById('apiTargetKey')?.value.trim() || 'app:user:101';
    const body = document.getElementById('apiRequestBody')?.value.trim() || '';
    const curlSnippet = document.getElementById('apiCurlSnippet');
    if (!curlSnippet) return;

    const origin = window.location.origin;

    switch (ep) {
        case 'GET_KEY':
            curlSnippet.textContent = `curl -H "Authorization: Bearer ${token}" ${origin}/v1/get/${encodeURIComponent(key)}`;
            break;
        case 'SET_KEY':
            curlSnippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '${body.replace(/\n\s*/g, ' ')}' ${origin}/v1/set`;
            break;
        case 'LPUSH':
            curlSnippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '${body.replace(/\n\s*/g, ' ')}' ${origin}/v1/lpush`;
            break;
        case 'LRANGE':
            curlSnippet.textContent = `curl -H "Authorization: Bearer ${token}" "${origin}/v1/lrange/${encodeURIComponent(key)}?start=0&stop=-1"`;
            break;
        case 'HSET':
            curlSnippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '${body.replace(/\n\s*/g, ' ')}' ${origin}/v1/hset`;
            break;
        case 'HGETALL':
            curlSnippet.textContent = `curl -H "Authorization: Bearer ${token}" ${origin}/v1/hgetall/${encodeURIComponent(key)}`;
            break;
        case 'DEL_KEY':
            curlSnippet.textContent = `curl -X DELETE -H "Authorization: Bearer ${token}" ${origin}/v1/del/${encodeURIComponent(key)}`;
            break;
        case 'PIPELINE':
            curlSnippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '${body.replace(/\n\s*/g, ' ')}' ${origin}/v1/pipeline`;
            break;
        case 'RAW_CMD':
            curlSnippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '${body.replace(/\n\s*/g, ' ')}' ${origin}/v1/command`;
            break;
    }
}

async function executeApiRequest() {
    const ep = document.getElementById('apiEndpointSelect').value;
    const token = getSelectedApiToken();
    const key = document.getElementById('apiTargetKey').value.trim() || 'app:user:101';
    const bodyStr = document.getElementById('apiRequestBody').value.trim();
    const statusEl = document.getElementById('apiResponseStatus');
    const boxEl = document.getElementById('apiResponseBox');
    const execBtn = document.getElementById('apiExecuteBtn');

    execBtn.disabled = true;
    statusEl.innerHTML = '<span style="color: var(--text-muted);">Executing...</span>';
    boxEl.textContent = 'Awaiting response...';

    let url = '/v1/';
    let method = 'GET';
    let headers = {
        'Authorization': `Bearer ${token}`
    };
    let payload = null;

    switch (ep) {
        case 'GET_KEY':
            url = `/v1/get/${encodeURIComponent(key)}`;
            method = 'GET';
            break;
        case 'SET_KEY':
            url = '/v1/set';
            method = 'POST';
            headers['Content-Type'] = 'application/json';
            payload = bodyStr;
            break;
        case 'LPUSH':
            url = '/v1/lpush';
            method = 'POST';
            headers['Content-Type'] = 'application/json';
            payload = bodyStr;
            break;
        case 'LRANGE':
            url = `/v1/lrange/${encodeURIComponent(key)}?start=0&stop=-1`;
            method = 'GET';
            break;
        case 'HSET':
            url = '/v1/hset';
            method = 'POST';
            headers['Content-Type'] = 'application/json';
            payload = bodyStr;
            break;
        case 'HGETALL':
            url = `/v1/hgetall/${encodeURIComponent(key)}`;
            method = 'GET';
            break;
        case 'DEL_KEY':
            url = `/v1/del/${encodeURIComponent(key)}`;
            method = 'DELETE';
            break;
        case 'PIPELINE':
            url = '/v1/pipeline';
            method = 'POST';
            headers['Content-Type'] = 'application/json';
            payload = bodyStr;
            break;
        case 'RAW_CMD':
            url = '/v1/command';
            method = 'POST';
            headers['Content-Type'] = 'application/json';
            payload = bodyStr;
            break;
    }

    const t0 = performance.now();
    try {
        const resp = await fetch(url, {
            method: method,
            headers: headers,
            body: payload
        });
        const elapsed = (performance.now() - t0).toFixed(1);
        const data = await resp.json().catch(() => ({ raw: 'Non-JSON response' }));

        const isSuccess = resp.ok;
        statusEl.innerHTML = `<span style="color: ${isSuccess ? '#34d399' : '#ff4757'};">${resp.status} ${resp.statusText} (${elapsed} ms)</span>`;
        boxEl.textContent = JSON.stringify(data, null, 2);

        if (isSuccess && ['SET_KEY', 'LPUSH', 'HSET', 'DEL_KEY', 'PIPELINE', 'RAW_CMD'].includes(ep)) {
            fetchKeys();
            fetchStats();
        }
    } catch (e) {
        statusEl.innerHTML = `<span style="color: #ff4757;">Network Error</span>`;
        boxEl.textContent = `Error: ${e.message}`;
    } finally {
        execBtn.disabled = false;
    }
}

async function loadAclUsers() {
    const container = document.getElementById('aclUserListContainer');
    if (!container) return;

    try {
        const res = await fetch('/api/acl');
        if (!res.ok) throw new Error('Failed to load ACL accounts');
        const data = await res.json();
        aclUsersCache = data.users || [];

        if (aclUsersCache.length === 0) {
            container.innerHTML = '<div class="empty">No ACL users registered</div>';
            return;
        }

        container.innerHTML = aclUsersCache.map(u => {
            const patterns = (u.keyPatterns || []).map(p => `<span class="pattern-tag">${escapeHtml(p)}</span>`).join('');
            return `
                <div class="user-card-item">
                    <div class="user-card-head">
                        <div class="user-name-group">
                            <iconify-icon icon="lucide:user-check" width="16" style="color: #38bdf8;"></iconify-icon>
                            <span>${escapeHtml(u.username)}</span>
                        </div>
                        <span class="role-badge ${u.role}">${escapeHtml(u.role)}</span>
                    </div>

                    <div class="token-row">
                        <span class="token-text" title="${escapeHtml(u.apiToken)}">${escapeHtml(u.apiToken)}</span>
                        <button class="btn-icon-copy" onclick="copyUserToken('${escapeHtml(u.apiToken)}')" title="Copy token">
                            <iconify-icon icon="lucide:copy" width="13"></iconify-icon>
                        </button>
                    </div>

                    <div class="patterns-row">
                        <span class="pattern-lbl">Allowed RLS Keys:</span>
                        ${patterns || '<span class="pattern-tag">*</span>'}
                    </div>
                </div>
            `;
        }).join('');

    } catch (e) {
        container.innerHTML = `<div class="error" style="color: #ff4757; font-size: 0.8rem;">Error loading users: ${escapeHtml(e.message)}</div>`;
    }
}

function copyUserToken(tok) {
    navigator.clipboard.writeText(tok).then(() => showToast('Copied token to clipboard!'));
}

// ==========================================
// Virtual Thread Benchmark Runner
// ==========================================
function initBenchmarkEngine() {
    const startBtn = document.getElementById('startBenchmarkBtn');
    if (!startBtn) return;

    startBtn.addEventListener('click', runBenchmarkTest);
}

async function runBenchmarkTest() {
    const totalOps = parseInt(document.getElementById('benchOpsSelect').value, 10) || 25000;
    const concurrency = parseInt(document.getElementById('benchConcurrencySelect').value, 10) || 50;
    const startBtn = document.getElementById('startBenchmarkBtn');

    startBtn.disabled = true;
    startBtn.innerHTML = '<div class="studio-spinner" style="width: 14px; height: 14px; border-width: 2px;"></div> Running stress test...';

    showToast(`Starting benchmark with ${totalOps.toLocaleString()} ops across ${concurrency} virtual threads...`);

    try {
        const res = await fetch('/api/benchmark', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                totalOperations: totalOps,
                concurrency: concurrency
            })
        });

        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.error || 'Benchmark failed');
        }

        const bench = await res.json();
        const ops = bench.opsPerSec ?? bench.operationsPerSecond ?? 0;
        const p50 = bench.p50Millis ?? bench.p50LatencyMs ?? 0;
        const p90 = bench.p90Millis ?? bench.p90LatencyMs ?? 0;
        const p99 = bench.p99Millis ?? bench.p99LatencyMs ?? 0;
        const min = bench.minMillis ?? bench.minLatencyMs ?? 0;
        const max = bench.maxMillis ?? bench.maxLatencyMs ?? 0;

        // Update hero metrics
        document.getElementById('benchOpsPerSec').textContent = Math.round(ops).toLocaleString();
        document.getElementById('benchDuration').textContent = `${bench.durationMillis} ms`;
        document.getElementById('benchCompletedOps').textContent = (bench.totalOperations || 0).toLocaleString();
        document.getElementById('benchConcurrencyLabel').textContent = `${bench.concurrency} Virtual Threads`;

        // Update latency metrics
        document.getElementById('benchP50').textContent = `${p50.toFixed(3)} ms`;
        document.getElementById('benchP90').textContent = `${p90.toFixed(3)} ms`;
        document.getElementById('benchP99').textContent = `${p99.toFixed(3)} ms`;
        document.getElementById('benchMin').textContent = `${min.toFixed(3)} ms`;
        document.getElementById('benchMax').textContent = `${max.toFixed(3)} ms`;

        showToast(`⚡ Stress test finished: ${Math.round(ops).toLocaleString()} ops/sec!`);
        fetchKeys();
        fetchStats();

    } catch (e) {
        showToast('Benchmark error: ' + e.message, true);
    } finally {
        startBtn.disabled = false;
        startBtn.innerHTML = '<iconify-icon icon="lucide:zap" width="16"></iconify-icon> Start Stress Test';
    }
}

// ==========================================
// Virtual Database Selector (Multi-Tenant Isolation)
// ==========================================
function initVirtualDatabaseSelector() {
    const sel = document.getElementById('vdbSelect');
    if (!sel) return;

    sel.addEventListener('change', (e) => {
        switchVirtualDatabase(e.target.value);
    });
}

function switchVirtualDatabase(dbName) {
    activeVirtualDb = dbName || 'default';
    const displayDb = activeVirtualDb === 'default' ? 'db0' : activeVirtualDb;

    const allDbs = [...(userDatabases.myDatabases || []), ...(userDatabases.sharedWithMe || [])];
    const found = allDbs.find(d => d.id === activeVirtualDb || d.name === activeVirtualDb);
    activeDbInstance = found || null;
    activeDatabaseRole = found ? (found.userRole || 'OWNER') : 'OWNER';

    // Update Top bar role badge
    const roleBadge = document.getElementById('activeDbRoleBadge');
    if (roleBadge) {
        roleBadge.textContent = activeDatabaseRole;
        roleBadge.className = `db-role-badge-pill ${activeDatabaseRole}`;
        roleBadge.style.display = 'inline-block';
    }

    // Role-based UI restriction: VIEWER is read-only
    const isViewer = (activeDatabaseRole === 'VIEWER');
    const insertBtn = document.getElementById('openInsertModalBtn');
    if (insertBtn) {
        insertBtn.disabled = isViewer;
        insertBtn.title = isViewer ? "Insert disabled (Viewer role)" : "Insert Record";
    }
    const flushBtn = document.getElementById('flushDbBtn');
    if (flushBtn) {
        flushBtn.style.display = isViewer ? 'none' : 'flex';
    }
    const importBtn = document.getElementById('importDataBtn');
    if (importBtn) {
        importBtn.disabled = isViewer;
    }
    const batchDelBtn = document.getElementById('batchDeleteBtn');
    if (batchDelBtn) {
        batchDelBtn.disabled = isViewer;
    }
    const inspDelBtn = document.getElementById('inspDeleteRecordBtn');
    if (inspDelBtn) {
        inspDelBtn.disabled = isViewer;
    }
    const inspSaveBtn = document.getElementById('inspSaveRecordBtn');
    if (inspSaveBtn) {
        inspSaveBtn.disabled = isViewer;
    }

    const pill = document.getElementById('sbActiveDbPill');
    if (pill) pill.textContent = found ? found.name : displayDb;

    const sel = document.getElementById('vdbSelect');
    if (sel && sel.value !== activeVirtualDb) {
        sel.value = activeVirtualDb;
    }

    const statActive = document.getElementById('statActiveDbName');
    if (statActive) {
        statActive.textContent = found ? found.name : displayDb;
    }
    const statActiveRole = document.getElementById('statActiveDbRoleSub');
    if (statActiveRole) {
        statActiveRole.textContent = `Role: ${activeDatabaseRole}`;
    }

    currentPage = 1;
    selectedKeys.clear();
    updateBatchBar();
    fetchKeys();
    fetchStats();
    showToast(`Active database: ${found ? found.name : displayDb} (${activeDatabaseRole})`);
}

function updateVirtualDatabaseSelector(myDbs = [], sharedDbs = []) {
    const sel = document.getElementById('vdbSelect');
    if (!sel) return;

    let optionsHtml = '';
    if (myDbs.length > 0) {
        optionsHtml += `<optgroup label="My Databases">`;
        myDbs.forEach(db => {
            optionsHtml += `<option value="${escapeHtml(db.id)}">${escapeHtml(db.name)} (${db.keyCount || 0} keys)</option>`;
        });
        optionsHtml += `</optgroup>`;
    }
    if (sharedDbs.length > 0) {
        optionsHtml += `<optgroup label="Shared With Me">`;
        sharedDbs.forEach(db => {
            optionsHtml += `<option value="${escapeHtml(db.id)}">${escapeHtml(db.name)} (${db.userRole})</option>`;
        });
        optionsHtml += `</optgroup>`;
    }
    if (!myDbs.length && !sharedDbs.length) {
        optionsHtml = `<option value="default">db0 (Default Root)</option>`;
    }

    sel.innerHTML = optionsHtml;
    const all = [...myDbs, ...sharedDbs];
    if (all.some(d => d.id === activeVirtualDb)) {
        sel.value = activeVirtualDb;
    } else if (all.length > 0) {
        activeVirtualDb = all[0].id;
        sel.value = activeVirtualDb;
    }
}

// ==========================================
// Accounts & Virtual DBs Studio Panel
// ==========================================
function initTenantsPanel() {
    const toggleFormBtn = document.getElementById('toggleCreateTenantFormBtn');
    const formCard = document.getElementById('tenantCreationCard');
    const closeFormBtn = document.getElementById('closeTenantFormBtn');
    const cancelFormBtn = document.getElementById('cancelTenantFormBtn');
    const createForm = document.getElementById('createTenantForm');
    const refreshBtn = document.getElementById('refreshTenantsBtn');
    const usernameInput = document.getElementById('tenantUsernameInput');
    const previewSpan = document.getElementById('vdbPreviewName');

    if (toggleFormBtn && formCard) {
        toggleFormBtn.addEventListener('click', () => {
            formCard.style.display = formCard.style.display === 'none' ? 'block' : 'none';
            if (formCard.style.display === 'block') {
                usernameInput.focus();
            }
        });
    }

    if (closeFormBtn && formCard) {
        closeFormBtn.addEventListener('click', () => formCard.style.display = 'none');
    }
    if (cancelFormBtn && formCard) {
        cancelFormBtn.addEventListener('click', () => formCard.style.display = 'none');
    }

    if (usernameInput && previewSpan) {
        usernameInput.addEventListener('input', () => {
            const u = usernameInput.value.trim().toLowerCase().replace(/[^a-z0-9_]/g, '');
            previewSpan.textContent = u ? `vdb_${u}` : 'vdb_...';
        });
    }

    if (createForm) {
        createForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const submitBtn = document.getElementById('submitTenantFormBtn');
            const username = usernameInput.value.trim();
            const password = document.getElementById('tenantPasswordInput').value.trim();
            const token = document.getElementById('tenantTokenInput').value.trim();

            if (!username) {
                showToast('Username is required', true);
                return;
            }

            submitBtn.disabled = true;
            submitBtn.innerHTML = '<div class="studio-spinner" style="width: 14px; height: 14px; border-width: 2px;"></div> Provisioning...';

            try {
                const res = await fetch('/api/tenants', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, password, token })
                });
                const data = await res.json();
                if (!res.ok || !data.success) {
                    throw new Error(data.error || 'Failed to create tenant');
                }

                showToast(`Provisioned account "${username}" with virtual database!`);
                formCard.style.display = 'none';
                createForm.reset();
                if (previewSpan) previewSpan.textContent = 'vdb_...';

                await loadTenants();
                await fetchStats();

                // Open Connect modal pre-selected for this new user!
                openConnectModal(username);
            } catch (err) {
                showToast(err.message, true);
            } finally {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<iconify-icon icon="lucide:plus" width="14"></iconify-icon> Provision Account & Virtual Database';
            }
        });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener('click', () => {
            loadTenants();
            showToast('Tenants list updated');
        });
    }
}

async function loadTenants() {
    const container = document.getElementById('tenantsCardsContainer');
    if (!container) return;

    try {
        const res = await fetch('/api/tenants');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        tenantsCache = data.tenants || [];
        databasesCache = data.databases || [];

        // Update stats
        const badge = document.getElementById('tenantCountBadge');
        if (badge) badge.textContent = tenantsCache.length;
        const statTenant = document.getElementById('statTenantCount');
        if (statTenant) statTenant.textContent = tenantsCache.length;
        const statVdb = document.getElementById('statVdbCount');
        if (statVdb) statVdb.textContent = databasesCache.length;

        updateVirtualDatabaseSelector(databasesCache, activeVirtualDb);

        if (tenantsCache.length === 0) {
            container.innerHTML = `<div class="empty-state">No tenant accounts found. Click "New Tenant Account" to provision your first virtual database.</div>`;
            return;
        }

        container.innerHTML = tenantsCache.map(u => {
            const urls = u.connectionUrls || {};
            const isCurrentActive = (activeVirtualDb === u.virtualDb) || (u.username === 'admin' && activeVirtualDb === 'default');
            const initial = (u.username[0] || 'U').toUpperCase();

            return `
                <div class="tenant-card ${isCurrentActive ? 'active-db' : ''}" data-username="${escapeHtml(u.username)}">
                    <div class="tenant-card-head">
                        <div class="tenant-user-info">
                            <div class="tenant-avatar">${initial}</div>
                            <div class="tenant-name-wrap">
                                <span class="tenant-name">
                                    ${escapeHtml(u.username)}
                                    ${isCurrentActive ? '<span class="badge-type" style="background:#2563eb; color:#fff; font-size:0.65rem;">Active in Studio</span>' : ''}
                                </span>
                                <span class="tenant-db-tag">${escapeHtml(u.virtualDb || 'db0')}</span>
                            </div>
                        </div>
                        <div class="tenant-badges-row">
                            <span class="role-badge ${u.role}">${escapeHtml(u.role)}</span>
                        </div>
                    </div>

                    <div class="tenant-stats-row">
                        <div class="tenant-stat-col">
                            <span class="lbl">Keys Count</span>
                            <span class="val">${(u.keyCount || 0).toLocaleString()}</span>
                        </div>
                        <div class="tenant-stat-col">
                            <span class="lbl">Memory Allocated</span>
                            <span class="val">${escapeHtml(u.memoryHuman || '0 B')}</span>
                        </div>
                        <div class="tenant-stat-col">
                            <span class="lbl">RLS Scope</span>
                            <span class="val" style="color: #34d399;">${(u.keyPatterns && u.keyPatterns[0]) || '*'}</span>
                        </div>
                    </div>

                    <div class="tenant-url-block">
                        <div class="url-copy-field">
                            <span class="prefix">Redis URL</span>
                            <code title="${escapeHtml(urls.redisUrl || '')}">${escapeHtml(urls.redisUrl || '')}</code>
                            <button class="btn-icon-copy" onclick="copyToClipboard('${escapeHtml(urls.redisUrl || '')}', 'Copied Redis URL!')" title="Copy Redis URL">
                                <iconify-icon icon="lucide:copy" width="13"></iconify-icon>
                            </button>
                        </div>
                        <div class="url-copy-field">
                            <span class="prefix">API Token</span>
                            <code title="${escapeHtml(u.apiToken || '')}">${escapeHtml(u.apiToken || '')}</code>
                            <button class="btn-icon-copy" onclick="copyToClipboard('${escapeHtml(u.apiToken || '')}', 'Copied API Token!')" title="Copy Token">
                                <iconify-icon icon="lucide:copy" width="13"></iconify-icon>
                            </button>
                        </div>
                    </div>

                    <div class="tenant-card-actions">
                        <button class="btn btn-secondary btn-sm" onclick="openConnectModal('${escapeHtml(u.username)}')">
                            <iconify-icon icon="lucide:link-2" width="13"></iconify-icon>
                            Connect Snippets
                        </button>
                        <button class="btn ${isCurrentActive ? 'btn-secondary' : 'btn-primary'} btn-sm" onclick="switchVirtualDatabase('${escapeHtml(u.virtualDb || 'default')}')">
                            <iconify-icon icon="lucide:database" width="13"></iconify-icon>
                            ${isCurrentActive ? 'Currently Browsing' : 'Switch Studio DB'}
                        </button>
                    </div>
                </div>
            `;
        }).join('');

    } catch (e) {
        container.innerHTML = `<div style="color: #ff4757; font-size: 0.85rem;">Failed to load accounts: ${escapeHtml(e.message)}</div>`;
    }
}

// ==========================================
// Connect to Redis Modal (Prisma, ioredis, CLI, REST)
// ==========================================
function initConnectModal() {
    const modal = document.getElementById('connectModal');
    const openBtn = document.getElementById('openConnectModalBtn');
    const closeBtn1 = document.getElementById('closeConnectModalBtn');
    const closeBtn2 = document.getElementById('closeConnectModalBtn2');
    const tenantSelect = document.getElementById('connectAccountSelect');
    const tabs = document.querySelectorAll('.connect-tab');
    const copySnippetBtn = document.getElementById('copySnippetBtn');
    const copyRedisUrlBtn = document.getElementById('copyConnRedisUrlBtn');
    const copyTokenUrlBtn = document.getElementById('copyConnTokenUrlBtn');

    if (openBtn) {
        openBtn.addEventListener('click', () => openConnectModal());
    }
    if (closeBtn1) closeBtn1.addEventListener('click', closeConnectModal);
    if (closeBtn2) closeBtn2.addEventListener('click', closeConnectModal);

    if (tenantSelect) {
        tenantSelect.addEventListener('change', (e) => {
            activeConnectTenantUsername = e.target.value;
            renderConnectDetails();
        });
    }

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            activeConnectTab = tab.dataset.tab;
            renderConnectSnippet();
        });
    });

    if (copySnippetBtn) {
        copySnippetBtn.addEventListener('click', () => {
            const code = document.getElementById('connectSnippetCode').textContent;
            copyToClipboard(code, 'Copied code snippet!');
        });
    }

    if (copyRedisUrlBtn) {
        copyRedisUrlBtn.addEventListener('click', () => {
            const url = document.getElementById('connRedisUrlInput').value;
            copyToClipboard(url, 'Copied Redis URL!');
        });
    }

    if (copyTokenUrlBtn) {
        copyTokenUrlBtn.addEventListener('click', () => {
            const url = document.getElementById('connTokenUrlInput').value;
            copyToClipboard(url, 'Copied Token URL!');
        });
    }
}

async function openConnectModal(preferredUsername) {
    const modal = document.getElementById('connectModal');
    if (!modal) return;

    if (tenantsCache.length === 0) {
        try {
            const res = await fetch('/api/tenants');
            if (res.ok) {
                const data = await res.json();
                tenantsCache = data.tenants || [];
            }
        } catch (ignored) {}
    }

    const select = document.getElementById('connectAccountSelect');
    if (select && tenantsCache.length > 0) {
        select.innerHTML = tenantsCache.map(u => `
            <option value="${escapeHtml(u.username)}">${escapeHtml(u.username)} (${escapeHtml(u.virtualDb || 'db0')}) - ${escapeHtml(u.role)}</option>
        `).join('');

        if (preferredUsername && tenantsCache.some(u => u.username === preferredUsername)) {
            activeConnectTenantUsername = preferredUsername;
            select.value = preferredUsername;
        } else if (activeVirtualDb && activeVirtualDb !== 'default') {
            const match = tenantsCache.find(u => u.virtualDb === activeVirtualDb);
            if (match) {
                activeConnectTenantUsername = match.username;
                select.value = match.username;
            } else {
                activeConnectTenantUsername = tenantsCache[0].username;
                select.value = activeConnectTenantUsername;
            }
        } else {
            activeConnectTenantUsername = select.value || 'admin';
        }
    }

    renderConnectDetails();
    modal.classList.add('open');
}

function closeConnectModal() {
    const modal = document.getElementById('connectModal');
    if (modal) modal.classList.remove('open');
}

function renderConnectDetails() {
    const user = tenantsCache.find(u => u.username === activeConnectTenantUsername) || (tenantsCache[0] || {
        username: 'admin',
        password: 'redis_admin_secret',
        apiToken: 'tok_admin_live_secret',
        virtualDb: 'db0',
        connectionUrls: {
            redisUrl: 'redis://admin:redis_admin_secret@localhost:6379',
            tokenUrl: 'redis://:tok_admin_live_secret@localhost:6379',
            restUrl: 'http://localhost:8080/v1'
        }
    });

    const host = window.location.hostname || 'localhost';
    const redisPort = 6379;
    const webPort = window.location.port || 8080;
    const stdUrl = `redis://${user.username}:${user.password}@${host}:${redisPort}`;
    const tokUrl = `redis://:${user.apiToken}@${host}:${redisPort}`;

    const redisInput = document.getElementById('connRedisUrlInput');
    const tokenInput = document.getElementById('connTokenUrlInput');
    if (redisInput) redisInput.value = stdUrl;
    if (tokenInput) tokenInput.value = tokUrl;

    renderConnectSnippet(user, stdUrl, tokUrl, host, redisPort, webPort);
}

function renderConnectSnippet(user, stdUrl, tokUrl, host, redisPort, webPort) {
    if (!user) {
        user = tenantsCache.find(u => u.username === activeConnectTenantUsername) || {};
    }
    if (!stdUrl) {
        const h = window.location.hostname || 'localhost';
        stdUrl = `redis://${user.username || 'admin'}:${user.password || 'password'}@${h}:6379`;
        tokUrl = `redis://:${user.apiToken || 'token'}@${h}:6379`;
        host = h;
        redisPort = 6379;
        webPort = window.location.port || 8080;
    }

    const titleEl = document.getElementById('snippetLanguageTitle');
    const codeEl = document.getElementById('connectSnippetCode');
    if (!codeEl) return;

    let code = '';
    let title = '';

    switch (activeConnectTab) {
        case 'prisma':
            title = 'schema.prisma & .env Integration';
            code = `// 1. In your .env file:
DATABASE_URL="${stdUrl}"

// 2. In your schema.prisma file:
datasource db {
  provider = "redis"
  url      = env("DATABASE_URL")
}

// In your application code:
import { PrismaClient } from '@prisma/client'
const prisma = new PrismaClient()

// Direct Redis caching / session commands run inside isolated database: ${user.virtualDb || 'vdb_' + user.username}`;
            break;

        case 'ioredis':
            title = 'Node.js (ioredis / Express / BullMQ)';
            code = `import Redis from 'ioredis';

// Connect using standard URL with auto-auth and tenant isolation:
const redis = new Redis("${stdUrl}");

// Or connect using Single-Token URL:
// const redis = new Redis("${tokUrl}");

await redis.set("user:session:token", "jwt_active_session_456", "EX", 3600);
const session = await redis.get("user:session:token");
console.log("Cached Session:", session);

// Disconnect on app shutdown
// await redis.quit();`;
            break;

        case 'python':
            title = 'Python (redis-py / FastAPI / Django)';
            code = `import redis

# Connect to isolated virtual database (${user.virtualDb || 'vdb_' + user.username})
r = redis.from_url("${stdUrl}")

# Test connection & write key
r.set("python:demo:key", "Hello from Python Redis Client", ex=600)
val = r.get("python:demo:key")
print("Retrieved:", val.decode("utf-8"))

# Hash operations
r.hset("user:101", mapping={"name": "Alice", "role": "engineer"})
print("Hash Data:", r.hgetall("user:101"))`;
            break;

        case 'cli':
            title = 'Official Redis CLI';
            code = `# 1. Connect directly using connection URL
redis-cli -u ${stdUrl}

# 2. Or connect and authenticate manually:
redis-cli -h ${host} -p ${redisPort} -a ${user.password || user.apiToken}

# Verify isolated database:
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> SET status "online"
OK`;
            break;

        case 'rest':
            title = 'Serverless Cloud Edge REST API (Upstash KV format)';
            code = `// .env.local
UPSTASH_REDIS_REST_URL="http://${host}:${webPort}/v1"
UPSTASH_REDIS_REST_TOKEN="${user.apiToken || 'red_api_...'}"

// cURL test:
curl -X POST http://${host}:${webPort}/v1/set \\
  -H "Authorization: Bearer ${user.apiToken || 'red_api_...'}" \\
  -H "Content-Type: application/json" \\
  -d '{"key": "cloud:item", "value": "Stored over HTTP REST at the Edge"}'

// cURL get:
curl http://${host}:${webPort}/v1/get/cloud%3Aitem \\
  -H "Authorization: Bearer ${user.apiToken || 'red_api_...'}"`;
            break;
    }

    if (titleEl) titleEl.textContent = title;
    codeEl.textContent = code;
}

function copyToClipboard(text, successMsg = 'Copied to clipboard!') {
    if (!text) return;
    navigator.clipboard.writeText(text).then(() => {
        showToast(successMsg);
    }).catch(() => {
        // Fallback
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        showToast(successMsg);
    });
}

// ==========================================
// Authentication Engine (Google, Email, Demo Switcher)
// ==========================================
function initAuth() {
    // 1. Intercept fetch to automatically attach currentSessionToken
    const _rawFetch = window.fetch;
    window.fetch = function(input, init) {
        init = init || {};
        init.headers = init.headers || {};
        const tok = currentSessionToken || localStorage.getItem('redis_studio_session');
        if (tok) {
            if (init.headers instanceof Headers) {
                if (!init.headers.has('Authorization')) {
                    init.headers.append('Authorization', `Bearer ${tok}`);
                }
            } else if (Array.isArray(init.headers)) {
                init.headers.push(['Authorization', `Bearer ${tok}`]);
            } else {
                if (!init.headers['Authorization']) {
                    init.headers['Authorization'] = `Bearer ${tok}`;
                }
            }
        }
        return _rawFetch(input, init);
    };

    // Google Sign-In button
    const googleBtn = document.getElementById('googleSignInBtn');
    if (googleBtn) {
        googleBtn.addEventListener('click', async () => {
            const email = prompt("Enter Google account email to sign in:", "alex@rediscloud.dev");
            if (!email) return;
            const name = email.split('@')[0];
            await loginWithGoogle(email, name);
        });
    }

    // Demo Accounts buttons
    const demoAlexBtn = document.getElementById('demoUserAlexBtn');
    if (demoAlexBtn) {
        demoAlexBtn.addEventListener('click', async () => {
            await loginWithEmail("alex@rediscloud.dev", "redis123");
        });
    }

    const demoSarahBtn = document.getElementById('demoUserSarahBtn');
    if (demoSarahBtn) {
        demoSarahBtn.addEventListener('click', async () => {
            await loginWithEmail("sarah@company.io", "redis123");
        });
    }

    // Tabs for Login / Register
    const tabLogin = document.getElementById('authTabLogin');
    const tabRegister = document.getElementById('authTabRegister');
    const formLogin = document.getElementById('authLoginForm');
    const formRegister = document.getElementById('authRegisterForm');

    if (tabLogin && tabRegister && formLogin && formRegister) {
        tabLogin.addEventListener('click', () => {
            tabLogin.classList.add('active');
            tabRegister.classList.remove('active');
            formLogin.style.display = 'flex';
            formRegister.style.display = 'none';
        });

        tabRegister.addEventListener('click', () => {
            tabRegister.classList.add('active');
            tabLogin.classList.remove('active');
            formRegister.style.display = 'flex';
            formLogin.style.display = 'none';
        });
    }

    if (formLogin) {
        formLogin.addEventListener('submit', async (e) => {
            e.preventDefault();
            const email = document.getElementById('loginEmailInput').value.trim();
            const pass = document.getElementById('loginPasswordInput').value;
            await loginWithEmail(email, pass);
        });
    }

    if (formRegister) {
        formRegister.addEventListener('submit', async (e) => {
            e.preventDefault();
            const name = document.getElementById('regNameInput').value.trim();
            const email = document.getElementById('regEmailInput').value.trim();
            const pass = document.getElementById('regPasswordInput').value;
            await registerWithEmail(name, email, pass);
        });
    }

    // User profile menu in top bar
    const profileBtn = document.getElementById('userProfileBtn');
    const dropdown = document.getElementById('userDropdownCard');
    if (profileBtn && dropdown) {
        profileBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            dropdown.style.display = dropdown.style.display === 'none' ? 'block' : 'none';
        });

        document.addEventListener('click', (e) => {
            if (!profileBtn.contains(e.target) && !dropdown.contains(e.target)) {
                dropdown.style.display = 'none';
            }
        });
    }

    const menuMyDbsBtn = document.getElementById('menuMyDatabasesBtn');
    if (menuMyDbsBtn) {
        menuMyDbsBtn.addEventListener('click', () => {
            if (dropdown) dropdown.style.display = 'none';
            document.querySelectorAll('.sidebar .nav-item').forEach(i => i.classList.remove('active'));
            const navBtn = document.getElementById('navDatabasesBtn');
            if (navBtn) navBtn.classList.add('active');

            document.querySelectorAll('.studio-pane').forEach(p => p.style.display = 'none');
            const pane = document.getElementById('pane-databases');
            if (pane) pane.style.display = 'flex';
            updateBreadcrumb('Database Hub & Team Sharing');
            loadUserDatabases();
        });
    }

    const menuSwitchAccBtn = document.getElementById('menuSwitchAccountBtn');
    if (menuSwitchAccBtn) {
        menuSwitchAccBtn.addEventListener('click', () => {
            if (dropdown) dropdown.style.display = 'none';
            openAuthModal();
        });
    }

    const menuSignOutBtn = document.getElementById('menuSignOutBtn');
    if (menuSignOutBtn) {
        menuSignOutBtn.addEventListener('click', async () => {
            if (dropdown) dropdown.style.display = 'none';
            await logout();
        });
    }

    // Check auth on load
    checkAuth();
}

async function checkAuth() {
    const tok = localStorage.getItem('redis_studio_session');
    if (!tok) {
        // Auto-login as Alex demo user for zero friction testing
        await loginWithEmail("alex@rediscloud.dev", "redis123", true);
        return;
    }

    try {
        const res = await fetch('/api/auth/me');
        if (res.ok) {
            const data = await res.json();
            currentUser = data.user;
            currentSessionToken = tok;
            updateUserProfileUI(currentUser);
            closeAuthModal();
            await checkJoinLink();
            await loadUserDatabases();
        } else {
            await loginWithEmail("alex@rediscloud.dev", "redis123", true);
        }
    } catch (e) {
        console.error('Auth verification failed:', e);
    }
}

async function checkJoinLink() {
    const hash = window.location.hash;
    if (hash && hash.startsWith('#join=')) {
        const token = hash.substring(6);
        try {
            const res = await fetch('/api/databases/join', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token })
            });
            const data = await res.json();
            if (data.success) {
                showToast(`Joined database "${data.database.name}"!`);
                window.location.hash = '';
            } else {
                showToast(data.error || 'Failed to join database', true);
            }
        } catch (e) {
            showToast('Join link error: ' + e.message, true);
        }
    }
}

async function loginWithEmail(email, password, isSilent = false) {
    try {
        const res = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
            throw new Error(data.error || 'Login failed');
        }

        currentSessionToken = data.token;
        localStorage.setItem('redis_studio_session', data.token);
        currentUser = data.user;
        updateUserProfileUI(currentUser);
        closeAuthModal();
        if (!isSilent) showToast(`Signed in as ${currentUser.name}`);
        await loadUserDatabases();
        fetchKeys();
        fetchStats();
    } catch (e) {
        if (!isSilent) showToast(e.message, true);
    }
}

async function loginWithGoogle(email, name) {
    try {
        const res = await fetch('/api/auth/google', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                email,
                name: name || email.split('@')[0],
                avatarUrl: `https://api.dicebear.com/7.x/identicon/svg?seed=${encodeURIComponent(name || email)}`
            })
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
            throw new Error(data.error || 'Google login failed');
        }

        currentSessionToken = data.token;
        localStorage.setItem('redis_studio_session', data.token);
        currentUser = data.user;
        updateUserProfileUI(currentUser);
        closeAuthModal();
        showToast(`Google Sign-In successful (${currentUser.email})`);
        await loadUserDatabases();
        fetchKeys();
        fetchStats();
    } catch (e) {
        showToast(e.message, true);
    }
}

async function registerWithEmail(name, email, password) {
    try {
        const res = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, email, password })
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
            throw new Error(data.error || 'Registration failed');
        }

        currentSessionToken = data.token;
        localStorage.setItem('redis_studio_session', data.token);
        currentUser = data.user;
        updateUserProfileUI(currentUser);
        closeAuthModal();
        showToast(`Account created! Welcome, ${currentUser.name}`);
        await loadUserDatabases();
        fetchKeys();
        fetchStats();
    } catch (e) {
        showToast(e.message, true);
    }
}

async function logout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (ignored) {}
    localStorage.removeItem('redis_studio_session');
    currentSessionToken = '';
    currentUser = null;
    openAuthModal();
    showToast('Signed out');
}

function updateUserProfileUI(user) {
    if (!user) return;
    const avatar = user.avatarUrl || `https://api.dicebear.com/7.x/identicon/svg?seed=${encodeURIComponent(user.name || user.email)}`;

    const topImg = document.getElementById('userAvatarImg');
    if (topImg) topImg.src = avatar;
    const topName = document.getElementById('userProfileName');
    if (topName) topName.textContent = user.name || user.email;

    const ddImg = document.getElementById('userDropdownAvatar');
    if (ddImg) ddImg.src = avatar;
    const ddName = document.getElementById('userDropdownName');
    if (ddName) ddName.textContent = user.name || user.email;
    const ddEmail = document.getElementById('userDropdownEmail');
    if (ddEmail) ddEmail.textContent = user.email;
}

function openAuthModal() {
    const modal = document.getElementById('authModal');
    if (modal) modal.classList.add('open');
}

function closeAuthModal() {
    const modal = document.getElementById('authModal');
    if (modal) modal.classList.remove('open');
}

// ==========================================
// Database Hub Management & Sharing
// ==========================================
function initDatabaseHub() {
    const tabMy = document.getElementById('tabMyDbsBtn');
    const tabShared = document.getElementById('tabSharedDbsBtn');
    const refreshBtn = document.getElementById('refreshDatabasesBtn');
    const openCreateBtn = document.getElementById('openCreateDbModalBtn');
    const createModal = document.getElementById('createDbModal');
    const closeCreateBtn = document.getElementById('closeCreateDbModalBtn');
    const cancelCreateBtn = document.getElementById('cancelCreateDbBtn');
    const createForm = document.getElementById('createDatabaseForm');

    if (tabMy && tabShared) {
        tabMy.addEventListener('click', () => {
            tabMy.classList.add('active');
            tabShared.classList.remove('active');
            activeDbTab = 'my';
            renderDatabaseCards();
        });

        tabShared.addEventListener('click', () => {
            tabShared.classList.add('active');
            tabMy.classList.remove('active');
            activeDbTab = 'shared';
            renderDatabaseCards();
        });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener('click', () => {
            loadUserDatabases();
            showToast('Databases refreshed');
        });
    }

    if (openCreateBtn && createModal) {
        openCreateBtn.addEventListener('click', () => {
            createModal.classList.add('open');
            const nameInput = document.getElementById('createDbNameInput');
            if (nameInput) nameInput.focus();
        });
    }

    const closeCreate = () => { if (createModal) createModal.classList.remove('open'); };
    if (closeCreateBtn) closeCreateBtn.addEventListener('click', closeCreate);
    if (cancelCreateBtn) cancelCreateBtn.addEventListener('click', closeCreate);

    if (createForm) {
        createForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const submitBtn = document.getElementById('submitCreateDbBtn');
            const name = document.getElementById('createDbNameInput').value.trim();
            const id = document.getElementById('createDbIdInput').value.trim();
            const password = document.getElementById('createDbPassInput').value.trim();

            if (!name) {
                showToast('Database name is required', true);
                return;
            }

            submitBtn.disabled = true;
            submitBtn.innerHTML = '<div class="studio-spinner" style="width: 14px; height: 14px; border-width: 2px;"></div> Creating...';

            try {
                const res = await fetch('/api/databases', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name, id, password })
                });
                const data = await res.json();
                if (!res.ok || !data.success) {
                    throw new Error(data.error || 'Failed to create database');
                }

                showToast(`Created database "${name}"!`);
                closeCreate();
                createForm.reset();
                await loadUserDatabases();

                // Switch to this database and open connect modal
                if (data.database && data.database.id) {
                    switchVirtualDatabase(data.database.id);
                    openConnectModalForDb(data.database.id);
                }
            } catch (err) {
                showToast(err.message, true);
            } finally {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<iconify-icon icon="lucide:plus" width="14"></iconify-icon> Create Database';
            }
        });
    }

    initShareModalEvents();
}

async function loadUserDatabases() {
    try {
        const res = await fetch('/api/databases');
        if (!res.ok) {
            if (res.status === 401) {
                openAuthModal();
                return;
            }
            throw new Error('HTTP ' + res.status);
        }
        const data = await res.json();
        userDatabases = {
            myDatabases: data.myDatabases || [],
            sharedWithMe: data.sharedWithMe || []
        };

        const myCount = userDatabases.myDatabases.length;
        const sharedCount = userDatabases.sharedWithMe.length;
        const totalCount = myCount + sharedCount;

        const sbBadge = document.getElementById('sbDbsCountBadge');
        if (sbBadge) sbBadge.textContent = totalCount;
        const statMy = document.getElementById('statMyDbsCount');
        if (statMy) statMy.textContent = myCount;
        const statShared = document.getElementById('statSharedDbsCount');
        if (statShared) statShared.textContent = sharedCount;
        const tabMyBadge = document.getElementById('tabMyDbsBadge');
        if (tabMyBadge) tabMyBadge.textContent = myCount;
        const tabSharedBadge = document.getElementById('tabSharedDbsBadge');
        if (tabSharedBadge) tabSharedBadge.textContent = sharedCount;

        updateVirtualDatabaseSelector(userDatabases.myDatabases, userDatabases.sharedWithMe);

        const all = [...userDatabases.myDatabases, ...userDatabases.sharedWithMe];
        if (all.length > 0 && (activeVirtualDb === 'default' || !all.some(d => d.id === activeVirtualDb))) {
            switchVirtualDatabase(all[0].id);
        }

        renderDatabaseCards();
    } catch (e) {
        console.error('Failed to load user databases:', e);
    }
}

function renderDatabaseCards() {
    const container = document.getElementById('databaseCardsContainer');
    if (!container) return;

    const list = (activeDbTab === 'my') ? userDatabases.myDatabases : userDatabases.sharedWithMe;

    if (!list || list.length === 0) {
        const emptyMsg = (activeDbTab === 'my')
            ? `You haven't created any databases yet. Click "New Database" above to provision your first Redis instance!`
            : `No databases have been shared with you yet. Teammates can share databases with your email (${escapeHtml(currentUser ? currentUser.email : '')}).`;
        container.innerHTML = `<div class="empty-state" style="grid-column: 1 / -1; padding: 3rem 1rem; text-align: center; color: var(--text-muted);">${emptyMsg}</div>`;
        return;
    }

    container.innerHTML = list.map(db => {
        const isCurrentActive = (activeVirtualDb === db.id);
        const role = db.userRole || 'OWNER';
        const urls = db.connectionUrls || {};
        const isOwner = (role === 'OWNER');

        return `
            <div class="db-card ${isCurrentActive ? 'active-db' : ''}" data-dbid="${escapeHtml(db.id)}">
                <div class="db-card-head">
                    <div class="db-card-title-wrap">
                        <div class="db-icon-box">
                            <iconify-icon icon="lucide:database" width="18"></iconify-icon>
                        </div>
                        <div>
                            <div class="db-card-name">${escapeHtml(db.name)}</div>
                            <div class="db-card-id-tag">${escapeHtml(db.id)}</div>
                        </div>
                    </div>
                    <span class="db-role-badge-pill ${role}">${escapeHtml(role)}</span>
                </div>

                ${!isOwner ? `
                <div style="font-size: 0.72rem; color: var(--text-muted); display: flex; align-items: center; gap: 0.35rem;">
                    <iconify-icon icon="lucide:user" width="12"></iconify-icon>
                    Owner: <span style="color: var(--text-main); font-weight: 500;">${escapeHtml(db.ownerEmail || '')}</span>
                </div>` : ''}

                <div class="db-card-stats">
                    <div class="db-stat-item">
                        <span class="stat-k">Keys</span>
                        <span class="stat-v">${(db.keyCount || 0).toLocaleString()}</span>
                    </div>
                    <div class="db-stat-item">
                        <span class="stat-k">Memory</span>
                        <span class="stat-v">${escapeHtml(db.memoryHuman || '0 B')}</span>
                    </div>
                    <div class="db-stat-item">
                        <span class="stat-k">Team</span>
                        <span class="stat-v">${db.collaboratorsCount || 1} users</span>
                    </div>
                </div>

                <div class="db-card-url-box">
                    <div class="url-copy-field">
                        <span class="prefix">Redis URL</span>
                        <code title="${escapeHtml(urls.redisUrl || '')}">${escapeHtml(urls.redisUrl || '')}</code>
                        <button class="btn-icon-copy" onclick="copyToClipboard('${escapeHtml(urls.redisUrl || '')}', 'Copied Redis URL!')" title="Copy URL">
                            <iconify-icon icon="lucide:copy" width="13"></iconify-icon>
                        </button>
                    </div>
                    <div class="url-copy-field">
                        <span class="prefix">API Token</span>
                        <code title="${escapeHtml(db.apiToken || '')}">${escapeHtml(db.apiToken || '')}</code>
                        <button class="btn-icon-copy" onclick="copyToClipboard('${escapeHtml(db.apiToken || '')}', 'Copied API Token!')" title="Copy Token">
                            <iconify-icon icon="lucide:copy" width="13"></iconify-icon>
                        </button>
                    </div>
                </div>

                <div class="db-card-actions">
                    <button class="btn ${isCurrentActive ? 'btn-secondary' : 'btn-primary'} btn-sm" onclick="openDatabaseInDatasheet('${escapeHtml(db.id)}')">
                        <iconify-icon icon="lucide:layout-grid" width="13"></iconify-icon>
                        ${isCurrentActive ? 'Currently In Datasheet' : 'Open in Datasheet'}
                    </button>
                    <button class="btn btn-secondary btn-sm" onclick="openConnectModalForDb('${escapeHtml(db.id)}')">
                        <iconify-icon icon="lucide:link-2" width="13"></iconify-icon>
                        Connect
                    </button>
                    ${isOwner ? `
                    <button class="btn btn-secondary btn-sm" onclick="openShareModal('${escapeHtml(db.id)}')">
                        <iconify-icon icon="lucide:share-2" width="13"></iconify-icon>
                        Share
                    </button>
                    <button class="btn btn-secondary btn-sm text-danger" onclick="deleteDatabase('${escapeHtml(db.id)}', '${escapeHtml(db.name)}')">
                        <iconify-icon icon="lucide:trash-2" width="13"></iconify-icon>
                    </button>` : ''}
                </div>
            </div>
        `;
    }).join('');
}

function openDatabaseInDatasheet(dbId) {
    switchVirtualDatabase(dbId);
    document.querySelectorAll('.sidebar .nav-item').forEach(i => i.classList.remove('active'));
    const datasheetNavItem = document.querySelector('.nav-item[data-type-filter="ALL"]');
    if (datasheetNavItem) datasheetNavItem.classList.add('active');

    document.querySelectorAll('.studio-pane').forEach(p => p.style.display = 'none');
    const pane = document.getElementById('pane-datasheet');
    if (pane) pane.style.display = 'flex';
    updateBreadcrumb('All Records');
}

function openConnectModalForDb(dbId) {
    const all = [...userDatabases.myDatabases, ...userDatabases.sharedWithMe];
    const db = all.find(d => d.id === dbId);
    if (!db) {
        openConnectModal();
        return;
    }

    const host = window.location.hostname || 'localhost';
    const redisPort = 6379;
    const webPort = window.location.port || 8080;
    const stdUrl = `redis://${db.id}:${db.password}@${host}:${redisPort}`;
    const tokUrl = `redis://:${db.apiToken}@${host}:${redisPort}`;

    const redisInput = document.getElementById('connRedisUrlInput');
    const tokenInput = document.getElementById('connTokenUrlInput');
    if (redisInput) redisInput.value = stdUrl;
    if (tokenInput) tokenInput.value = tokUrl;

    const select = document.getElementById('connectAccountSelect');
    if (select) {
        select.innerHTML = `<option value="${escapeHtml(db.id)}">${escapeHtml(db.name)} (${escapeHtml(db.id)}) - ${escapeHtml(db.userRole || 'OWNER')}</option>`;
        select.value = db.id;
    }

    const fakeUser = {
        username: db.id,
        password: db.password,
        apiToken: db.apiToken,
        virtualDb: db.id
    };
    renderConnectSnippet(fakeUser, stdUrl, tokUrl, host, redisPort, webPort);

    const modal = document.getElementById('connectModal');
    if (modal) modal.classList.add('open');
}

function openShareModal(dbId) {
    activeShareDbId = dbId;
    const all = [...userDatabases.myDatabases, ...userDatabases.sharedWithMe];
    const db = all.find(d => d.id === dbId);
    if (!db) return;

    const modal = document.getElementById('shareDbModal');
    const sub = document.getElementById('shareDbModalSubtitle');
    if (sub) sub.textContent = `Managing access for "${db.name}" (${db.id})`;

    renderShareModalCollaborators(db);

    const linkToggle = document.getElementById('shareLinkEnableCheck');
    const linkStatus = document.getElementById('shareLinkStatusLabel');
    const linkBody = document.getElementById('shareLinkBody');
    const linkInput = document.getElementById('shareLinkInput');
    const roleSelect = document.getElementById('shareLinkRoleSelect');

    const host = window.location.host;
    const shareLinkUrl = `${window.location.protocol}//${host}/#join=${db.shareLinkToken || ''}`;

    if (linkToggle) linkToggle.checked = !!db.shareLinkEnabled;
    if (linkStatus) linkStatus.textContent = db.shareLinkEnabled ? 'Active' : 'Disabled';
    if (linkBody) linkBody.style.display = db.shareLinkEnabled ? 'block' : 'none';
    if (linkInput) linkInput.value = shareLinkUrl;
    if (roleSelect && db.shareLinkRole) roleSelect.value = db.shareLinkRole;

    if (modal) modal.classList.add('open');
}

function renderShareModalCollaborators(db) {
    const listEl = document.getElementById('shareCollaboratorsList');
    if (!listEl) return;

    const collabs = db.collaborators || [
        { email: db.ownerEmail, role: 'OWNER' }
    ];

    listEl.innerHTML = collabs.map(c => {
        const isOwner = (c.role === 'OWNER');
        const initial = (c.email ? c.email[0] : 'U').toUpperCase();
        return `
            <div class="collaborator-item">
                <div class="collab-left">
                    <div class="collab-avatar">${initial}</div>
                    <div class="collab-email">${escapeHtml(c.email)}</div>
                </div>
                <div class="collab-right">
                    <span class="db-role-badge-pill ${c.role}">${escapeHtml(c.role)}</span>
                    ${!isOwner ? `
                    <button class="btn btn-secondary btn-sm" style="padding: 0.2rem 0.5rem; color: #f87171;" onclick="unshareCollaborator('${escapeHtml(db.id)}', '${escapeHtml(c.email)}')">
                        <iconify-icon icon="lucide:user-minus" width="13"></iconify-icon>
                        Remove
                    </button>` : ''}
                </div>
            </div>
        `;
    }).join('');
}

function initShareModalEvents() {
    const shareModal = document.getElementById('shareDbModal');
    const closeBtn1 = document.getElementById('closeShareDbModalBtn');
    const closeBtn2 = document.getElementById('closeShareDbModalBtn2');
    const inviteForm = document.getElementById('inviteCollaboratorForm');
    const linkToggle = document.getElementById('shareLinkEnableCheck');
    const roleSelect = document.getElementById('shareLinkRoleSelect');
    const copyLinkBtn = document.getElementById('copyShareLinkBtn');

    const closeShare = () => { if (shareModal) shareModal.classList.remove('open'); };
    if (closeBtn1) closeBtn1.addEventListener('click', closeShare);
    if (closeBtn2) closeBtn2.addEventListener('click', closeShare);

    if (inviteForm) {
        inviteForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!activeShareDbId) return;
            const email = document.getElementById('shareTargetEmailInput').value.trim();
            const role = document.getElementById('shareTargetRoleSelect').value;
            const sendBtn = document.getElementById('sendInviteBtn');

            sendBtn.disabled = true;
            try {
                const res = await fetch('/api/databases/share', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ dbId: activeShareDbId, targetEmail: email, role })
                });
                const data = await res.json();
                if (!res.ok || !data.success) {
                    throw new Error(data.error || 'Failed to share database');
                }

                showToast(`Shared with ${email} as ${role}!`);
                document.getElementById('shareTargetEmailInput').value = '';
                await loadUserDatabases();
                const all = [...userDatabases.myDatabases, ...userDatabases.sharedWithMe];
                const updatedDb = all.find(d => d.id === activeShareDbId);
                if (updatedDb) renderShareModalCollaborators(updatedDb);
            } catch (err) {
                showToast(err.message, true);
            } finally {
                sendBtn.disabled = false;
            }
        });
    }

    const updateShareLinkState = async () => {
        if (!activeShareDbId) return;
        const enabled = linkToggle.checked;
        const role = roleSelect.value;
        try {
            const res = await fetch('/api/databases/share-link', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ dbId: activeShareDbId, enabled, role })
            });
            const data = await res.json();
            if (data.success) {
                document.getElementById('shareLinkStatusLabel').textContent = enabled ? 'Active' : 'Disabled';
                document.getElementById('shareLinkBody').style.display = enabled ? 'block' : 'none';
                const host = window.location.host;
                const linkUrl = `${window.location.protocol}//${host}/#join=${data.token || ''}`;
                document.getElementById('shareLinkInput').value = linkUrl;
                showToast(enabled ? 'Invite link enabled' : 'Invite link disabled');
                await loadUserDatabases();
            }
        } catch (e) {
            showToast('Failed to update share link: ' + e.message, true);
        }
    };

    if (linkToggle) linkToggle.addEventListener('change', updateShareLinkState);
    if (roleSelect) roleSelect.addEventListener('change', updateShareLinkState);

    if (copyLinkBtn) {
        copyLinkBtn.addEventListener('click', () => {
            const url = document.getElementById('shareLinkInput').value;
            copyToClipboard(url, 'Invite link copied to clipboard!');
        });
    }
}

async function unshareCollaborator(dbId, targetEmail) {
    if (!confirm(`Remove collaborator ${targetEmail}?`)) return;
    try {
        const res = await fetch('/api/databases/unshare', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dbId, targetEmail })
        });
        const data = await res.json();
        if (data.success) {
            showToast(`Removed access for ${targetEmail}`);
            await loadUserDatabases();
            const all = [...userDatabases.myDatabases, ...userDatabases.sharedWithMe];
            const updatedDb = all.find(d => d.id === dbId);
            if (updatedDb) renderShareModalCollaborators(updatedDb);
        }
    } catch (e) {
        showToast('Error removing collaborator: ' + e.message, true);
    }
}

async function deleteDatabase(dbId, dbName) {
    if (!confirm(`⚠️ Are you sure you want to DELETE database "${dbName}" (${dbId})? All keys and data will be permanently erased.`)) {
        return;
    }
    try {
        const res = await fetch(`/api/databases?id=${encodeURIComponent(dbId)}`, { method: 'DELETE' });
        const data = await res.json();
        if (data.success) {
            showToast(`Database "${dbName}" deleted`);
            await loadUserDatabases();
        } else {
            showToast(data.error || 'Failed to delete database', true);
        }
    } catch (e) {
        showToast('Error deleting database: ' + e.message, true);
    }
}



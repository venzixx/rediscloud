// ==============================================================
// Redis Cloud - Enterprise SaaS Console Engine
// ==============================================================

// Datasheet & Pagination State
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

// Virtual Database & User State
let activeVirtualDb = 'default';
let databasesCache = [];
let tenantsCache = [];
let activeConnectTenantUsername = 'admin';
let activeConnectTab = 'prisma';

// User & Database Hub State
let currentUser = null;
let currentSessionToken = localStorage.getItem('redis_cloud_session') || localStorage.getItem('redis_studio_session') || '';
let userDatabases = { myDatabases: [], sharedWithMe: [] };
let activeDbInstance = null;
let activeDatabaseRole = 'OWNER';
let activeDbTab = 'my';
let activeShareDbId = null;

// Wrap window.fetch to automatically include Authorization token
const originalFetch = window.fetch;
window.fetch = async function(url, options = {}) {
    options = options || {};
    options.headers = options.headers || {};
    if (currentSessionToken) {
        if (options.headers instanceof Headers) {
            if (!options.headers.has('Authorization')) {
                options.headers.set('Authorization', `Bearer ${currentSessionToken}`);
            }
        } else {
            if (!options.headers['Authorization']) {
                options.headers['Authorization'] = `Bearer ${currentSessionToken}`;
            }
        }
    }
    const response = await originalFetch(url, options);
    if (response.status === 401 && typeof url === 'string' && !url.includes('/api/auth/login') && !url.includes('/api/auth/register') && !url.includes('/api/auth/me')) {
        handleSessionExpired();
    }
    return response;
};

function handleSessionExpired() {
    if (currentSessionToken) {
        currentSessionToken = null;
        currentUser = null;
        localStorage.removeItem('redis_cloud_session');
        localStorage.removeItem('redis_studio_session');
        showLandingPage();
        showToast('Session expired. Please sign in again.', true);
    }
}

// Charts References
let overviewChartInstance = null;
let analyticsTrendsChartInstance = null;
let analyticsSuccessChartInstance = null;
let analyticsDistributionChartInstance = null;
let activeOverviewChartMetric = 'throughput';

// Live Operations Stream Mock/Real Data
let recentOperationsList = [
    { id: 'RUN-6734', op: 'HSET', key: 'user:1001:profile', time: 'Just now', duration: '0.24ms', status: 'running' },
    { id: 'RUN-6733', op: 'GET', key: 'session:auth:token', time: '12s ago', duration: '0.18ms', status: 'success' },
    { id: 'RUN-6732', op: 'LPUSH', key: 'queue:events:incoming', time: '45s ago', duration: '0.31ms', status: 'success' },
    { id: 'RUN-6731', op: 'PIPELINE', key: 'batch:metrics:sync', time: '2m ago', duration: '1.42ms', status: 'success' },
    { id: 'RUN-6730', op: 'DEL', key: 'cache:temp:expired', time: '5m ago', duration: '0.15ms', status: 'success' },
    { id: 'RUN-6729', op: 'HGETALL', key: 'config:flags:live', time: '8m ago', duration: '0.28ms', status: 'failed', error: 'Key not found' }
];

// Team Members Data
let teamMembersList = [
    { id: 1, name: 'Alex Rivera', email: 'alex@rediscloud.dev', role: 'Owner', status: 'online', avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=Alex', joinDate: 'Jan 2024', lastActive: 'Now', isOwner: true },
    { id: 2, name: 'Sarah Chen', email: 'sarah@company.io', role: 'Engineer', status: 'online', avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=Sarah', joinDate: 'Mar 2024', lastActive: '5m ago', isOwner: false },
    { id: 3, name: 'Michael Whitmore', email: 'michael@company.io', role: 'Engineer', status: 'away', avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=Michael', joinDate: 'Feb 2024', lastActive: '2h ago', isOwner: false },
    { id: 4, name: 'Clara Blackwood', email: 'clara@company.io', role: 'Designer', status: 'offline', avatar: 'https://api.dicebear.com/7.x/identicon/svg?seed=Clara', joinDate: 'May 2024', lastActive: 'Yesterday', isOwner: false }
];

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
    initOverviewDashboard();
    initAnalyticsPage();
    initTeamPage();
});

// ==========================================
// Navigation & Views
// ==========================================
function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item[data-view-pane]');
    navItems.forEach(item => {
        item.addEventListener('click', () => {
            const targetPaneId = item.dataset.viewPane;
            navigateToPane(targetPaneId);

            if (targetPaneId === 'pane-datasheet' && item.dataset.typeFilter) {
                activeTypeFilter = item.dataset.typeFilter;
                activeNamespace = null;
                currentPage = 1;
                updateBreadcrumb();
                fetchKeys();
            }
        });
    });

    // Global Search Bar in Header
    const globalSearch = document.getElementById('globalSearchInput');
    if (globalSearch) {
        globalSearch.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const val = globalSearch.value.trim();
                navigateToPane('pane-datasheet');
                const filterInput = document.getElementById('filterSearchInput');
                if (filterInput) {
                    filterInput.value = val;
                    currentSearch = val;
                    currentPage = 1;
                    fetchKeys();
                }
            }
        });
    }

    // Sidebar search input
    const sidebarSearch = document.getElementById('sidebarSearchInput');
    if (sidebarSearch) {
        sidebarSearch.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const val = sidebarSearch.value.trim();
                navigateToPane('pane-datasheet');
                const filterInput = document.getElementById('filterSearchInput');
                if (filterInput) {
                    filterInput.value = val;
                    currentSearch = val;
                    currentPage = 1;
                    fetchKeys();
                }
            }
        });
    }

    // Refresh Namespaces Button
    const refreshNsBtn = document.getElementById('refreshNamespacesBtn');
    if (refreshNsBtn) {
        refreshNsBtn.addEventListener('click', () => {
            fetchKeys();
            showToast('Refreshed namespaces');
        });
    }

    // Flush DB Button
    const flushBtn = document.getElementById('flushDbBtn');
    if (flushBtn) {
        flushBtn.addEventListener('click', async () => {
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
}

function navigateToPane(targetPaneId) {
    // Hide all panes
    document.querySelectorAll('.studio-pane').forEach(p => p.style.display = 'none');

    // Show target pane
    const targetPane = document.getElementById(targetPaneId);
    if (targetPane) {
        targetPane.style.display = (targetPaneId === 'pane-overview' || targetPaneId === 'pane-team' || targetPaneId === 'pane-settings' || targetPaneId === 'pane-analytics') ? 'block' : 'flex';
    }

    // Update active nav styling
    document.querySelectorAll('.nav-item').forEach(item => {
        if (item.dataset.viewPane === targetPaneId && (!item.dataset.typeFilter || item.dataset.typeFilter === 'ALL')) {
            item.className = 'nav-item flex items-center w-full justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors bg-purple-50 text-purple-700 hover:bg-purple-100';
        } else if (!item.dataset.typeFilter) {
            item.className = 'nav-item flex items-center w-full justify-between px-3 py-2 rounded-md text-sm font-medium text-gray-600 hover:bg-gray-50 hover:text-gray-900 transition-colors';
        }
    });

    // Update breadcrumb
    if (targetPaneId === 'pane-overview') {
        updateBreadcrumb('Overview');
        renderOverviewChart(activeOverviewChartMetric);
    } else if (targetPaneId === 'pane-datasheet') {
        updateBreadcrumb();
        fetchKeys();
    } else if (targetPaneId === 'pane-databases') {
        updateBreadcrumb('Databases Hub & Team');
        loadUserDatabases();
    } else if (targetPaneId === 'pane-analytics') {
        updateBreadcrumb('Analytics');
        renderAnalyticsCharts();
    } else if (targetPaneId === 'pane-cloud-api') {
        updateBreadcrumb('Cloud REST API & RLS');
        loadAclUsers();
    } else if (targetPaneId === 'pane-benchmark') {
        updateBreadcrumb('Stress Benchmark');
    } else if (targetPaneId === 'pane-team') {
        updateBreadcrumb('Team');
        renderTeamMembers();
    } else if (targetPaneId === 'pane-settings') {
        updateBreadcrumb('Settings');
    }
}

function updateBreadcrumb(customTitle) {
    const crumb = document.getElementById('currentViewBreadcrumb');
    if (!crumb) return;
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
// Overview Dashboard (Matching app/page.tsx)
// ==========================================
function initOverviewDashboard() {
    renderOverviewOperationsTable();
    renderOverviewActivityStream();
    renderOverviewTeamStatus();
    renderOverviewChart(activeOverviewChartMetric);
}

function renderOverviewChart(metric = 'throughput') {
    const ctx = document.getElementById('overviewChart');
    if (!ctx) return;

    if (overviewChartInstance) {
        overviewChartInstance.destroy();
    }

    const labels = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul'];
    let dataset1 = [];
    let dataset2 = [];
    let label1 = 'Pipelined Ops';
    let label2 = 'Direct Commands';

    if (metric === 'throughput') {
        dataset1 = [82000, 94000, 110000, 105000, 118000, 128450, 134000];
        dataset2 = [45000, 52000, 61000, 58000, 64000, 71000, 76000];
        label1 = 'Virtual Threads Ops/s';
        label2 = 'REST API Hits';
    } else if (metric === 'commands') {
        dataset1 = [120000, 145000, 180000, 160000, 195000, 210000, 230000];
        dataset2 = [60000, 75000, 85000, 80000, 92000, 105000, 112000];
        label1 = 'Read Commands';
        label2 = 'Write Commands';
    } else if (metric === 'latency') {
        dataset1 = [0.42, 0.38, 0.35, 0.31, 0.29, 0.28, 0.25];
        dataset2 = [0.65, 0.60, 0.54, 0.49, 0.45, 0.42, 0.39];
        label1 = 'P50 Latency (ms)';
        label2 = 'P99 Latency (ms)';
    }

    const gradient1 = ctx.getContext('2d').createLinearGradient(0, 0, 0, 300);
    gradient1.addColorStop(0, 'rgba(147, 51, 234, 0.25)');
    gradient1.addColorStop(1, 'rgba(147, 51, 234, 0.0)');

    const gradient2 = ctx.getContext('2d').createLinearGradient(0, 0, 0, 300);
    gradient2.addColorStop(0, 'rgba(59, 130, 246, 0.2)');
    gradient2.addColorStop(1, 'rgba(59, 130, 246, 0.0)');

    overviewChartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [
                {
                    label: label1,
                    data: dataset1,
                    borderColor: '#9333ea',
                    backgroundColor: gradient1,
                    fill: true,
                    tension: 0.4,
                    borderWidth: 2.5,
                    pointBackgroundColor: '#9333ea',
                    pointRadius: 3,
                    pointHoverRadius: 6
                },
                {
                    label: label2,
                    data: dataset2,
                    borderColor: '#3b82f6',
                    backgroundColor: gradient2,
                    fill: true,
                    tension: 0.4,
                    borderWidth: 2,
                    pointBackgroundColor: '#3b82f6',
                    pointRadius: 3,
                    pointHoverRadius: 6
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    position: 'top',
                    labels: {
                        boxWidth: 12,
                        font: { family: '"Plus Jakarta Sans", sans-serif', size: 12 },
                        color: '#4b5563'
                    }
                },
                tooltip: {
                    backgroundColor: '#ffffff',
                    titleColor: '#111827',
                    bodyColor: '#4b5563',
                    borderColor: '#e5e7eb',
                    borderWidth: 1,
                    padding: 10,
                    boxPadding: 4,
                    usePointStyle: true,
                    titleFont: { weight: 'bold' }
                }
            },
            scales: {
                x: {
                    grid: { color: '#f3f4f6' },
                    ticks: { color: '#6b7280', font: { family: '"Plus Jakarta Sans", sans-serif', size: 11 } }
                },
                y: {
                    grid: { color: '#f3f4f6' },
                    ticks: { color: '#6b7280', font: { family: '"Plus Jakarta Sans", sans-serif', size: 11 } }
                }
            }
        }
    });
}

function switchOverviewChartTab(metric, btn) {
    activeOverviewChartMetric = metric;
    const parent = btn.parentElement;
    parent.querySelectorAll('button').forEach(b => {
        b.className = 'px-3 py-1 rounded-md text-gray-600 hover:text-gray-900';
    });
    btn.className = 'px-3 py-1 rounded-md bg-white text-gray-900 shadow-sm font-semibold';
    renderOverviewChart(metric);
}

function renderOverviewOperationsTable() {
    const tbody = document.getElementById('overviewOperationsTableBody');
    if (!tbody) return;

    tbody.innerHTML = recentOperationsList.map(item => `
        <tr class="hover:bg-gray-50/80 transition-colors">
            <td class="py-3 px-4 font-mono text-xs font-medium text-gray-500">${item.id}</td>
            <td class="py-3 px-4 font-medium text-gray-900">${item.op}</td>
            <td class="py-3 px-4 text-xs text-gray-500">${item.time}</td>
            <td class="py-3 px-4 text-xs text-gray-500 font-mono">${item.duration}</td>
            <td class="py-3 px-4">
                ${item.status === 'running' ? `
                    <span class="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700">
                        <span class="w-1.5 h-1.5 rounded-full bg-blue-500 animate-pulse"></span>
                        Running
                    </span>
                ` : item.status === 'success' ? `
                    <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700">
                        <iconify-icon icon="lucide:check-circle" width="12"></iconify-icon>
                        Success
                    </span>
                ` : `
                    <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700">
                        <iconify-icon icon="lucide:x-circle" width="12"></iconify-icon>
                        Failed
                    </span>
                `}
            </td>
            <td class="py-3 px-4 font-mono text-xs text-gray-600 truncate max-w-44">${item.key}</td>
            <td class="py-3 px-4 text-right">
                <button class="p-1 text-gray-400 hover:text-gray-600 rounded hover:bg-gray-100" onclick="inspectKey('${item.key}')" title="Inspect Key">
                    <iconify-icon icon="lucide:more-horizontal" width="16"></iconify-icon>
                </button>
            </td>
        </tr>
    `).join('');
}

function refreshRecentOperations() {
    // Generate new mock operation
    const ops = ['GET', 'SET', 'HSET', 'LPUSH', 'DEL', 'EXPIRE'];
    const keys = ['user:session:xyz', 'analytics:visits', 'cache:products', 'lock:sync', 'cart:items:302'];
    const newOp = {
        id: 'RUN-' + (Math.floor(Math.random() * 900) + 6800),
        op: ops[Math.floor(Math.random() * ops.length)],
        key: keys[Math.floor(Math.random() * keys.length)],
        time: 'Just now',
        duration: (Math.random() * 0.4 + 0.1).toFixed(2) + 'ms',
        status: Math.random() > 0.1 ? 'success' : 'running'
    };
    recentOperationsList.unshift(newOp);
    if (recentOperationsList.length > 8) recentOperationsList.pop();
    renderOverviewOperationsTable();
    renderOverviewActivityStream();
    showToast('Recent operations updated');
}

function renderOverviewActivityStream() {
    const container = document.getElementById('ovRecentActivityStream');
    if (!container) return;

    container.innerHTML = recentOperationsList.slice(0, 5).map(item => `
        <div class="flex items-center gap-3 p-3.5 hover:bg-gray-50/80 transition-colors">
            <span class="w-2 h-2 rounded-full ${item.status === 'success' ? 'bg-emerald-500' : item.status === 'running' ? 'bg-blue-500 animate-pulse' : 'bg-red-500'} shrink-0"></span>
            <div class="min-w-0 flex-1">
                <div class="font-medium text-xs text-gray-900 truncate font-mono">${item.op} ${item.key}</div>
                <div class="text-[11px] text-gray-500 mt-0.5">${item.time} • ${item.duration}</div>
            </div>
        </div>
    `).join('');
}

function renderOverviewTeamStatus() {
    const container = document.getElementById('ovTeamStatusList');
    if (!container) return;

    container.innerHTML = teamMembersList.map(member => `
        <div class="flex items-center gap-3 p-3.5 hover:bg-gray-50/80 transition-colors">
            <div class="relative shrink-0">
                <img src="${member.avatar}" class="w-8 h-8 rounded-full border border-gray-200">
                <span class="absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 border-white ${member.status === 'online' ? 'bg-emerald-500' : member.status === 'away' ? 'bg-amber-500' : 'bg-gray-400'}"></span>
            </div>
            <div class="min-w-0 flex-1">
                <div class="font-medium text-xs text-gray-900 truncate">${member.name}</div>
                <div class="text-[11px] text-gray-500 mt-0.5">${member.role} • ${member.status === 'online' ? 'Available' : 'Away'}</div>
            </div>
        </div>
    `).join('');
}

// ==========================================
// Analytics Page (Matching app/analytics/page.tsx)
// ==========================================
function initAnalyticsPage() {
    // Analytics charts rendered when switching to pane
}

function renderAnalyticsCharts() {
    renderAnalyticsExecTrends();
    renderAnalyticsSuccessBreakdown();
    renderAnalyticsDistribution();
    renderAnalyticsTopNamespaces();
}

function renderAnalyticsExecTrends() {
    const ctx = document.getElementById('analyticsExecTrendsChart');
    if (!ctx) return;
    if (analyticsTrendsChartInstance) analyticsTrendsChartInstance.destroy();

    const gradient = ctx.getContext('2d').createLinearGradient(0, 0, 0, 250);
    gradient.addColorStop(0, 'rgba(147, 51, 234, 0.25)');
    gradient.addColorStop(1, 'rgba(147, 51, 234, 0.0)');

    analyticsTrendsChartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul'],
            datasets: [{
                label: 'Executions',
                data: [4000, 3000, 5000, 2780, 1890, 2390, 3490],
                borderColor: '#9333ea',
                backgroundColor: gradient,
                fill: true,
                tension: 0.4,
                borderWidth: 2
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                x: { grid: { color: '#f3f4f6' }, ticks: { font: { size: 11 } } },
                y: { grid: { color: '#f3f4f6' }, ticks: { font: { size: 11 } } }
            }
        }
    });
}

function renderAnalyticsSuccessBreakdown() {
    const ctx = document.getElementById('analyticsSuccessChart');
    if (!ctx) return;
    if (analyticsSuccessChartInstance) analyticsSuccessChartInstance.destroy();

    analyticsSuccessChartInstance = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul'],
            datasets: [
                {
                    label: 'Success',
                    data: [3800, 2850, 4900, 2650, 1820, 2300, 3400],
                    backgroundColor: '#10b981',
                    borderRadius: 4
                },
                {
                    label: 'Failed',
                    data: [200, 150, 100, 130, 70, 90, 90],
                    backgroundColor: '#ef4444',
                    borderRadius: 4
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    position: 'top',
                    labels: { font: { size: 11 }, boxWidth: 10 }
                }
            },
            scales: {
                x: { stacked: true, grid: { color: '#f3f4f6' } },
                y: { stacked: true, grid: { color: '#f3f4f6' } }
            }
        }
    });
}

function renderAnalyticsDistribution() {
    const ctx = document.getElementById('analyticsDistributionChart');
    if (!ctx) return;
    if (analyticsDistributionChartInstance) analyticsDistributionChartInstance.destroy();

    analyticsDistributionChartInstance = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: ['user:', 'session:', 'app:', 'cache:', 'queue:'],
            datasets: [{
                data: [35, 25, 20, 15, 5],
                backgroundColor: ['#9333ea', '#3b82f6', '#10b981', '#f59e0b', '#ef4444'],
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { position: 'right', labels: { boxWidth: 10, font: { size: 11 } } }
            },
            cutout: '70%'
        }
    });
}

function renderAnalyticsTopNamespaces() {
    const list = document.getElementById('analyticsNamespacesList');
    if (!list) return;

    const data = [
        { name: 'user:*', share: '35%', runs: '8,690', color: 'bg-purple-600' },
        { name: 'session:*', share: '25%', runs: '6,210', color: 'bg-blue-600' },
        { name: 'app:*', share: '20%', runs: '4,960', color: 'bg-emerald-600' },
        { name: 'cache:*', share: '15%', runs: '3,720', color: 'bg-amber-600' },
        { name: 'queue:*', share: '5%', runs: '1,240', color: 'bg-red-600' }
    ];

    list.innerHTML = data.map(item => `
        <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
                <span class="w-3 h-3 rounded-full ${item.color}"></span>
                <span class="font-mono text-xs font-semibold text-gray-800">${item.name}</span>
            </div>
            <div class="flex items-center gap-3 text-xs">
                <span class="text-gray-500 font-medium">${item.share}</span>
                <span class="bg-gray-100 text-gray-700 px-2 py-0.5 rounded font-mono">${item.runs} ops</span>
            </div>
        </div>
    `).join('');
}

// ==========================================
// Team Page (Matching app/team/page.tsx)
// ==========================================
function initTeamPage() {
    renderTeamMembers();
}

function renderTeamMembers() {
    const container = document.getElementById('teamTabMembers');
    if (!container) return;

    container.innerHTML = teamMembersList.map(member => `
        <div class="bg-white p-5 rounded-xl border border-gray-200 shadow-sm flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div class="flex items-center gap-4">
                <div class="relative">
                    <img src="${member.avatar}" class="w-12 h-12 rounded-full border border-gray-200">
                    <span class="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-white ${member.status === 'online' ? 'bg-emerald-500' : member.status === 'away' ? 'bg-amber-500' : 'bg-gray-400'}"></span>
                </div>
                <div>
                    <div class="flex items-center gap-2">
                        <h4 class="font-semibold text-gray-900 text-sm">${member.name}</h4>
                        ${member.isOwner ? `<iconify-icon icon="lucide:crown" class="text-purple-600" width="14"></iconify-icon>` : ''}
                        <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${member.role === 'Owner' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'}">${member.role}</span>
                    </div>
                    <div class="flex flex-wrap items-center gap-4 text-xs text-gray-500 mt-1">
                        <span>${member.email}</span>
                        <span>•</span>
                        <span>Joined ${member.joinDate}</span>
                        <span>•</span>
                        <span>Active: ${member.lastActive}</span>
                    </div>
                </div>
            </div>
            <div class="flex items-center gap-2">
                <button class="px-3 py-1.5 border border-gray-200 text-gray-700 hover:bg-gray-50 rounded-lg text-xs font-medium" onclick="showToast('Message sent to ' + '${member.name}')">Message</button>
                ${!member.isOwner ? `
                    <button class="px-3 py-1.5 text-red-600 hover:bg-red-50 rounded-lg text-xs font-medium" onclick="showToast('Member removed')">Remove</button>
                ` : ''}
            </div>
        </div>
    `).join('');

    // Tab Invitations
    const invitesContainer = document.getElementById('teamTabInvitations');
    if (invitesContainer) {
        invitesContainer.innerHTML = `
            <div class="bg-white p-5 rounded-xl border border-gray-200 shadow-sm flex items-center justify-between">
                <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-full bg-gray-100 text-gray-500 flex items-center justify-center">
                        <iconify-icon icon="lucide:mail" width="18"></iconify-icon>
                    </div>
                    <div>
                        <div class="font-semibold text-sm text-gray-900">devops@partner.org</div>
                        <div class="text-xs text-gray-500">Invited as Viewer • 2 days ago</div>
                    </div>
                </div>
                <div class="flex items-center gap-2">
                    <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-amber-100 text-amber-700">Pending</span>
                    <button class="text-xs text-red-600 hover:underline px-2 py-1" onclick="showToast('Invitation cancelled')">Cancel</button>
                </div>
            </div>
        `;
    }

    // Tab Roles
    const rolesContainer = document.getElementById('teamTabRoles');
    if (rolesContainer) {
        rolesContainer.innerHTML = `
            <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
                <div class="bg-white p-6 rounded-xl border border-gray-200 shadow-sm space-y-3">
                    <div class="flex items-center justify-between">
                        <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-purple-100 text-purple-700">Owner</span>
                        <span class="text-xs text-gray-400">1 member</span>
                    </div>
                    <p class="text-xs text-gray-600">Full administrative access across all databases, billing, user management, and API tokens.</p>
                </div>
                <div class="bg-white p-6 rounded-xl border border-gray-200 shadow-sm space-y-3">
                    <div class="flex items-center justify-between">
                        <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-blue-100 text-blue-700">Editor</span>
                        <span class="text-xs text-gray-400">2 members</span>
                    </div>
                    <p class="text-xs text-gray-600">Read and write permissions to databases, ability to insert/edit/delete keys, and run benchmarks.</p>
                </div>
                <div class="bg-white p-6 rounded-xl border border-gray-200 shadow-sm space-y-3">
                    <div class="flex items-center justify-between">
                        <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-gray-100 text-gray-700">Viewer</span>
                        <span class="text-xs text-gray-400">2 members</span>
                    </div>
                    <p class="text-xs text-gray-600">Read-only permissions to query records, view telemetry charts, and inspect keys.</p>
                </div>
            </div>
        `;
    }
}

function switchTeamTab(tabName, btn) {
    const parent = btn.parentElement;
    parent.querySelectorAll('.team-tab-btn').forEach(b => {
        b.className = 'team-tab-btn px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 transition-colors';
    });
    btn.className = 'team-tab-btn px-4 py-2 rounded-lg text-sm font-semibold transition-colors bg-purple-50 text-purple-700';

    document.getElementById('teamTabMembers').classList.toggle('hidden', tabName !== 'members');
    document.getElementById('teamTabInvitations').classList.toggle('hidden', tabName !== 'invitations');
    document.getElementById('teamTabRoles').classList.toggle('hidden', tabName !== 'roles');
}

// ==========================================
// Settings Tabs
// ==========================================
function switchSettingsTab(tabName, btn) {
    const parent = btn.parentElement;
    parent.querySelectorAll('.settings-tab-btn').forEach(b => {
        b.className = 'settings-tab-btn px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 transition-colors';
    });
    btn.className = 'settings-tab-btn px-4 py-2 rounded-lg text-sm font-semibold transition-colors bg-purple-50 text-purple-700';

    document.getElementById('settingsTabProfile').classList.toggle('hidden', tabName !== 'profile');
    document.getElementById('settingsTabNotifications').classList.toggle('hidden', tabName !== 'notifications');
    document.getElementById('settingsTabSecurity').classList.toggle('hidden', tabName !== 'security');
    document.getElementById('settingsTabPreferences').classList.toggle('hidden', tabName !== 'preferences');
}

// ==========================================
// Datasheet Filtering, Sorting & Toolbar
// ==========================================
function initDatasheetToolbar() {
    const searchInput = document.getElementById('filterSearchInput');
    const sortSelect = document.getElementById('sortSelect');
    const selectAllCheck = document.getElementById('selectAllCheckbox');

    let debounce;
    if (searchInput) {
        searchInput.addEventListener('input', () => {
            clearTimeout(debounce);
            debounce = setTimeout(() => {
                currentSearch = searchInput.value.trim();
                currentPage = 1;
                fetchKeys();
            }, 250);
        });
    }

    if (sortSelect) {
        sortSelect.addEventListener('change', (e) => {
            currentSort = e.target.value;
            currentPage = 1;
            fetchKeys();
        });
    }

    if (selectAllCheck) {
        selectAllCheck.addEventListener('change', (e) => {
            if (e.target.checked) {
                currentPageKeys.forEach(k => selectedKeys.add(k.key));
            } else {
                currentPageKeys.forEach(k => selectedKeys.delete(k.key));
            }
            renderTableRows();
            updateBatchBar();
        });
    }

    const batchDelBtn = document.getElementById('batchDeleteBtn');
    if (batchDelBtn) {
        batchDelBtn.addEventListener('click', async () => {
            if (selectedKeys.size === 0) return;
            if (!confirm(`Delete ${selectedKeys.size} selected key(s)?`)) return;

            try {
                for (const key of selectedKeys) {
                    await fetch(`/api/key?key=${encodeURIComponent(key)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                        method: 'DELETE'
                    });
                }
                showToast(`Deleted ${selectedKeys.size} key(s)`);
                selectedKeys.clear();
                updateBatchBar();
                fetchKeys();
                fetchStats();
            } catch (err) {
                showToast('Failed to delete keys: ' + err.message, true);
            }
        });
    }

    const batchCancelBtn = document.getElementById('batchCancelBtn');
    if (batchCancelBtn) {
        batchCancelBtn.addEventListener('click', () => {
            selectedKeys.clear();
            const chk = document.getElementById('selectAllCheckbox');
            if (chk) chk.checked = false;
            renderTableRows();
            updateBatchBar();
        });
    }
}

function updateBatchBar() {
    const bar = document.getElementById('batchActionBar');
    const countText = document.getElementById('batchCountText');
    if (!bar || !countText) return;

    if (selectedKeys.size > 0) {
        bar.classList.remove('hidden');
        bar.classList.add('flex');
        countText.textContent = `${selectedKeys.size} selected`;
    } else {
        bar.classList.add('hidden');
        bar.classList.remove('flex');
    }
}

// ==========================================
// Pagination Engine
// ==========================================
function initPagination() {
    const pageSizeSelect = document.getElementById('pagPageSizeSelect');
    const firstBtn = document.getElementById('pagFirstBtn');
    const prevBtn = document.getElementById('pagPrevBtn');
    const nextBtn = document.getElementById('pagNextBtn');
    const lastBtn = document.getElementById('pagLastBtn');
    const jumpInput = document.getElementById('pagJumpInput');

    if (pageSizeSelect) {
        pageSizeSelect.addEventListener('change', (e) => {
            pageSize = parseInt(e.target.value, 10);
            currentPage = 1;
            fetchKeys();
        });
    }

    if (firstBtn) {
        firstBtn.addEventListener('click', () => {
            if (currentPage > 1) {
                currentPage = 1;
                fetchKeys();
            }
        });
    }

    if (prevBtn) {
        prevBtn.addEventListener('click', () => {
            if (currentPage > 1) {
                currentPage--;
                fetchKeys();
            }
        });
    }

    if (nextBtn) {
        nextBtn.addEventListener('click', () => {
            if (currentPage < totalPages) {
                currentPage++;
                fetchKeys();
            }
        });
    }

    if (lastBtn) {
        lastBtn.addEventListener('click', () => {
            if (currentPage < totalPages) {
                currentPage = totalPages;
                fetchKeys();
            }
        });
    }

    if (jumpInput) {
        jumpInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                let target = parseInt(jumpInput.value, 10);
                if (isNaN(target)) target = 1;
                if (target < 1) target = 1;
                if (target > totalPages) target = totalPages;
                currentPage = target;
                fetchKeys();
            }
        });
    }
}

function updatePaginationBar() {
    const rangeInfo = document.getElementById('pagRangeInfo');
    const currentNum = document.getElementById('pagCurrentPageNum');
    const totalNum = document.getElementById('pagTotalPagesNum');
    const jumpInput = document.getElementById('pagJumpInput');
    const firstBtn = document.getElementById('pagFirstBtn');
    const prevBtn = document.getElementById('pagPrevBtn');
    const nextBtn = document.getElementById('pagNextBtn');
    const lastBtn = document.getElementById('pagLastBtn');
    const counter = document.getElementById('recordCounter');

    if (totalRecords === 0) {
        if (rangeInfo) rangeInfo.textContent = 'Showing 0 – 0 of 0 records';
        if (currentNum) currentNum.textContent = '0';
        if (totalNum) totalNum.textContent = '0';
        if (jumpInput) jumpInput.value = 1;
        if (firstBtn) firstBtn.disabled = true;
        if (prevBtn) prevBtn.disabled = true;
        if (nextBtn) nextBtn.disabled = true;
        if (lastBtn) lastBtn.disabled = true;
        if (counter) counter.textContent = '0 records';
        return;
    }

    const startIdx = (currentPage - 1) * pageSize + 1;
    const endIdx = Math.min(currentPage * pageSize, totalRecords);

    if (rangeInfo) rangeInfo.textContent = `Showing ${startIdx} – ${endIdx} of ${totalRecords.toLocaleString()} records`;
    if (currentNum) currentNum.textContent = currentPage;
    if (totalNum) totalNum.textContent = totalPages;
    if (jumpInput) jumpInput.value = currentPage;

    if (firstBtn) firstBtn.disabled = currentPage <= 1;
    if (prevBtn) prevBtn.disabled = currentPage <= 1;
    if (nextBtn) nextBtn.disabled = currentPage >= totalPages;
    if (lastBtn) lastBtn.disabled = currentPage >= totalPages;

    if (counter) counter.textContent = `${totalRecords.toLocaleString()} records`;
}

// ==========================================
// Data Fetching & Table Rendering
// ==========================================
async function fetchKeys() {
    const tbody = document.getElementById('datasheetBody');
    if (!tbody) return;

    try {
        let url = `/api/keys?page=${currentPage}&limit=${pageSize}&sort=${currentSort}`;
        if (activeTypeFilter !== 'ALL') url += `&type=${encodeURIComponent(activeTypeFilter)}`;
        if (activeNamespace) url += `&namespace=${encodeURIComponent(activeNamespace)}`;
        if (currentSearch) url += `&pattern=${encodeURIComponent(currentSearch)}`;
        if (activeVirtualDb !== 'default') url += `&db=${encodeURIComponent(activeVirtualDb)}`;

        const res = await fetch(url);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();

        totalRecords = data.total || 0;
        totalPages = Math.max(1, Math.ceil(totalRecords / pageSize));
        currentPageKeys = data.keys || [];

        renderTableRows();
        updatePaginationBar();
        updateSidebarCounts(data.counts || {});
        buildNamespaceTree(data.namespaces || []);

        // Also update overview KPI
        const ovKeys = document.getElementById('ovTotalKeys');
        if (ovKeys) ovKeys.textContent = totalRecords.toLocaleString();

    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="8" class="p-8 text-center text-red-500 font-medium">Failed to load data: ${escapeHtml(err.message)}</td></tr>`;
    }
}

function updateSidebarCounts(counts) {
    if (document.getElementById('countAll')) document.getElementById('countAll').textContent = (counts.all ?? totalRecords).toLocaleString();
    if (document.getElementById('countStrings')) document.getElementById('countStrings').textContent = (counts.string ?? 0).toLocaleString();
    if (document.getElementById('countHashes')) document.getElementById('countHashes').textContent = (counts.hash ?? 0).toLocaleString();
    if (document.getElementById('countLists')) document.getElementById('countLists').textContent = (counts.list ?? 0).toLocaleString();
    if (document.getElementById('countSets')) document.getElementById('countSets').textContent = (counts.set ?? 0).toLocaleString();
}

function buildNamespaceTree(namespaces) {
    const tree = document.getElementById('namespaceTreeList');
    if (!tree) return;

    if (!namespaces || namespaces.length === 0) {
        tree.innerHTML = '<div class="px-3 py-1 text-gray-400 italic">No namespaces found</div>';
        return;
    }

    tree.innerHTML = namespaces.map(ns => `
        <div class="namespace-tree-item ${activeNamespace === ns.name ? 'active' : ''}" onclick="selectNamespace('${escapeHtml(ns.name)}')">
            <div class="flex items-center gap-2">
                <iconify-icon icon="lucide:folder" width="13" class="${activeNamespace === ns.name ? 'text-purple-600' : 'text-gray-400'}"></iconify-icon>
                <span class="truncate">${escapeHtml(ns.name)}</span>
            </div>
            <span class="text-[10px] text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded-full">${ns.count}</span>
        </div>
    `).join('');
}

function selectNamespace(nsName) {
    if (activeNamespace === nsName) {
        activeNamespace = null;
    } else {
        activeNamespace = nsName;
    }
    currentPage = 1;
    updateBreadcrumb();
    fetchKeys();
}

function renderTableRows() {
    const tbody = document.getElementById('datasheetBody');
    if (!tbody) return;

    if (currentPageKeys.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="8" class="p-12 text-center text-gray-400">
                    <div class="flex flex-col items-center justify-center gap-2">
                        <iconify-icon icon="lucide:database" width="32" class="text-gray-300"></iconify-icon>
                        <span class="text-sm font-medium text-gray-500">No records found matching current filter</span>
                    </div>
                </td>
            </tr>
        `;
        return;
    }

    const startIdx = (currentPage - 1) * pageSize;
    tbody.innerHTML = currentPageKeys.map((item, idx) => {
        const isChecked = selectedKeys.has(item.key);
        const rowNum = startIdx + idx + 1;
        const typeClass = `badge-${item.type.toLowerCase()}`;
        const ttlDisplay = item.ttl < 0 ? '<span class="text-gray-400 font-mono text-xs">Persistent</span>' : `<span class="text-amber-600 font-mono text-xs font-semibold">${item.ttl}s</span>`;

        return `
            <tr class="hover:bg-gray-50/80 transition-colors ${isChecked ? 'bg-purple-50/40' : ''}">
                <td class="py-3 px-4 text-center">
                    <input type="checkbox" class="rounded border-gray-300 text-purple-600 focus:ring-purple-500" ${isChecked ? 'checked' : ''} onchange="toggleSelectKey('${escapeHtml(item.key)}', this.checked)">
                </td>
                <td class="py-3 px-4 text-center text-gray-400 font-mono text-xs">${rowNum}</td>
                <td class="py-3 px-4 font-mono text-xs font-medium text-gray-900 cursor-pointer hover:text-purple-600 truncate max-w-xs" onclick="inspectKey('${escapeHtml(item.key)}')">
                    ${escapeHtml(item.key)}
                </td>
                <td class="py-3 px-4">
                    <span class="px-2 py-0.5 rounded text-[11px] font-bold uppercase tracking-wider ${typeClass}">${item.type}</span>
                </td>
                <td class="py-3 px-4 font-mono text-xs text-gray-500 truncate max-w-xs cursor-pointer" onclick="inspectKey('${escapeHtml(item.key)}')">
                    ${escapeHtml(item.preview || '')}
                </td>
                <td class="py-3 px-4 font-mono text-xs text-gray-500">${formatBytes(item.size || 0)}</td>
                <td class="py-3 px-4">${ttlDisplay}</td>
                <td class="py-3 px-4 text-right">
                    <div class="flex items-center justify-end gap-1">
                        <button class="p-1 text-gray-400 hover:text-purple-600 rounded hover:bg-purple-50" onclick="inspectKey('${escapeHtml(item.key)}')" title="Inspect / Edit">
                            <iconify-icon icon="lucide:eye" width="15"></iconify-icon>
                        </button>
                        <button class="p-1 text-gray-400 hover:text-red-600 rounded hover:bg-red-50" onclick="deleteSingleKey('${escapeHtml(item.key)}')" title="Delete Key">
                            <iconify-icon icon="lucide:trash-2" width="15"></iconify-icon>
                        </button>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}

function toggleSelectKey(key, isChecked) {
    if (isChecked) {
        selectedKeys.add(key);
    } else {
        selectedKeys.delete(key);
    }
    updateBatchBar();
}

async function deleteSingleKey(key) {
    if (!confirm(`Delete key "${key}"?`)) return;
    try {
        const res = await fetch(`/api/key?key=${encodeURIComponent(key)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
            method: 'DELETE'
        });
        if (res.ok) {
            showToast(`Deleted key: ${key}`);
            selectedKeys.delete(key);
            updateBatchBar();
            fetchKeys();
            fetchStats();
            if (activeInspectKey === key) closeInspector();
        }
    } catch (err) {
        showToast('Delete failed: ' + err.message, true);
    }
}

// ==========================================
// Inspector Drawer
// ==========================================
function initInspector() {
    const closeBtn = document.getElementById('closeInspectorBtn');
    const cancelBtn = document.getElementById('inspCancelBtn');
    const deleteBtn = document.getElementById('inspDeleteRecordBtn');
    const saveBtn = document.getElementById('inspSaveRecordBtn');
    const setTtlBtn = document.getElementById('inspSetTtlBtn');
    const persistBtn = document.getElementById('inspPersistBtn');

    if (closeBtn) closeBtn.addEventListener('click', closeInspector);
    if (cancelBtn) cancelBtn.addEventListener('click', closeInspector);

    if (deleteBtn) {
        deleteBtn.addEventListener('click', () => {
            if (activeInspectKey) deleteSingleKey(activeInspectKey);
        });
    }

    if (saveBtn) {
        saveBtn.addEventListener('click', async () => {
            if (!activeInspectKey || !inspectCache) return;
            const contentHost = document.getElementById('inspectorContentHost');
            const type = inspectCache.type.toLowerCase();

            try {
                let payload = {};
                if (type === 'string') {
                    const textarea = contentHost.querySelector('textarea');
                    payload.value = textarea ? textarea.value : '';
                } else if (type === 'hash') {
                    const fields = {};
                    contentHost.querySelectorAll('.hash-field-row').forEach(row => {
                        const f = row.querySelector('.hash-f-name').value.trim();
                        const v = row.querySelector('.hash-f-val').value;
                        if (f) fields[f] = v;
                    });
                    payload.fields = fields;
                } else if (type === 'list' || type === 'set') {
                    const items = [];
                    contentHost.querySelectorAll('.list-item-row input').forEach(inp => {
                        if (inp.value) items.push(inp.value);
                    });
                    payload.items = items;
                }

                const res = await fetch(`/api/key?key=${encodeURIComponent(activeInspectKey)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });

                if (res.ok) {
                    showToast('Key saved successfully');
                    fetchKeys();
                    fetchStats();
                    inspectKey(activeInspectKey);
                }
            } catch (err) {
                showToast('Save failed: ' + err.message, true);
            }
        });
    }

    if (setTtlBtn) {
        setTtlBtn.addEventListener('click', async () => {
            if (!activeInspectKey) return;
            const ttlInput = document.getElementById('inspTtlSeconds');
            const seconds = parseInt(ttlInput.value, 10);
            if (isNaN(seconds) || seconds < 0) return showToast('Enter valid TTL seconds', true);

            try {
                const res = await fetch(`/api/expire?key=${encodeURIComponent(activeInspectKey)}&seconds=${seconds}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                    method: 'POST'
                });
                if (res.ok) {
                    showToast(`TTL set to ${seconds}s`);
                    inspectKey(activeInspectKey);
                    fetchKeys();
                }
            } catch (err) {
                showToast('Set TTL failed: ' + err.message, true);
            }
        });
    }

    if (persistBtn) {
        persistBtn.addEventListener('click', async () => {
            if (!activeInspectKey) return;
            try {
                const res = await fetch(`/api/persist?key=${encodeURIComponent(activeInspectKey)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                    method: 'POST'
                });
                if (res.ok) {
                    showToast('Key persisted (TTL removed)');
                    inspectKey(activeInspectKey);
                    fetchKeys();
                }
            } catch (err) {
                showToast('Persist failed: ' + err.message, true);
            }
        });
    }
}

async function inspectKey(key) {
    activeInspectKey = key;
    const drawer = document.getElementById('recordInspector');
    if (!drawer) return;

    drawer.classList.add('open');
    document.getElementById('inspKeyTitle').textContent = key;
    document.getElementById('inspTypeBadge').textContent = 'LOADING';

    try {
        const res = await fetch(`/api/key?key=${encodeURIComponent(key)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        inspectCache = data;

        document.getElementById('inspTypeBadge').textContent = data.type.toUpperCase();
        document.getElementById('inspMetaType').textContent = data.type;
        document.getElementById('inspMetaSize').textContent = formatBytes(data.size || 0);
        document.getElementById('inspMetaTtl').textContent = data.ttl < 0 ? 'No Expiry (-1)' : `${data.ttl}s`;
        document.getElementById('inspTtlSeconds').value = data.ttl > 0 ? data.ttl : '';

        renderInspectorContent(data);
    } catch (err) {
        document.getElementById('inspectorContentHost').innerHTML = `<div class="p-4 text-sm text-red-500">Failed to load key: ${escapeHtml(err.message)}</div>`;
    }
}

function closeInspector() {
    const drawer = document.getElementById('recordInspector');
    if (drawer) drawer.classList.remove('open');
    activeInspectKey = null;
    inspectCache = null;
}

function renderInspectorContent(data) {
    const host = document.getElementById('inspectorContentHost');
    const tools = document.getElementById('contentTools');
    if (!host) return;

    tools.innerHTML = '';
    const type = data.type.toLowerCase();

    if (type === 'string') {
        tools.innerHTML = `
            <button class="text-xs text-purple-600 hover:text-purple-800 font-semibold" onclick="beautifyJsonInspector()">Beautify JSON</button>
        `;
        host.innerHTML = `
            <textarea id="inspStringValue" rows="8" class="w-full bg-gray-50 border border-gray-200 rounded-lg p-3 text-xs font-mono text-gray-800 focus:outline-none focus:bg-white focus:border-purple-500">${escapeHtml(data.value || '')}</textarea>
        `;
    } else if (type === 'hash') {
        const fields = data.fields || {};
        tools.innerHTML = `
            <button class="text-xs text-purple-600 hover:text-purple-800 font-semibold flex items-center gap-1" onclick="addHashFieldRow()">
                <iconify-icon icon="lucide:plus" width="12"></iconify-icon> Add Field
            </button>
        `;
        host.innerHTML = `
            <div class="space-y-2 max-h-60 overflow-y-auto" id="hashFieldsList">
                ${Object.entries(fields).map(([k, v]) => `
                    <div class="hash-field-row flex items-center gap-2">
                        <input type="text" class="hash-f-name w-1/3 bg-white border border-gray-200 rounded px-2 py-1 text-xs font-mono" value="${escapeHtml(k)}">
                        <input type="text" class="hash-f-val flex-1 bg-white border border-gray-200 rounded px-2 py-1 text-xs font-mono" value="${escapeHtml(v)}">
                        <button class="text-gray-400 hover:text-red-500" onclick="this.parentElement.remove()"><iconify-icon icon="lucide:x" width="14"></iconify-icon></button>
                    </div>
                `).join('')}
            </div>
        `;
    } else if (type === 'list' || type === 'set') {
        const items = data.items || [];
        tools.innerHTML = `
            <button class="text-xs text-purple-600 hover:text-purple-800 font-semibold flex items-center gap-1" onclick="addListItemRow()">
                <iconify-icon icon="lucide:plus" width="12"></iconify-icon> Add Element
            </button>
        `;
        host.innerHTML = `
            <div class="space-y-2 max-h-60 overflow-y-auto" id="listItemsContainer">
                ${items.map((it, i) => `
                    <div class="list-item-row flex items-center gap-2">
                        <span class="text-gray-400 font-mono text-xs w-6">${i}</span>
                        <input type="text" class="flex-1 bg-white border border-gray-200 rounded px-2 py-1 text-xs font-mono" value="${escapeHtml(it)}">
                        <button class="text-gray-400 hover:text-red-500" onclick="this.parentElement.remove()"><iconify-icon icon="lucide:x" width="14"></iconify-icon></button>
                    </div>
                `).join('')}
            </div>
        `;
    }
}

function beautifyJsonInspector() {
    const textarea = document.getElementById('inspStringValue');
    if (!textarea) return;
    try {
        const parsed = JSON.parse(textarea.value);
        textarea.value = JSON.stringify(parsed, null, 2);
    } catch (e) {
        showToast('Not valid JSON', true);
    }
}

function addHashFieldRow() {
    const list = document.getElementById('hashFieldsList');
    if (!list) return;
    const div = document.createElement('div');
    div.className = 'hash-field-row flex items-center gap-2';
    div.innerHTML = `
        <input type="text" class="hash-f-name w-1/3 bg-white border border-gray-200 rounded px-2 py-1 text-xs font-mono" placeholder="field">
        <input type="text" class="hash-f-val flex-1 bg-white border border-gray-200 rounded px-2 py-1 text-xs font-mono" placeholder="value">
        <button class="text-gray-400 hover:text-red-500" onclick="this.parentElement.remove()"><iconify-icon icon="lucide:x" width="14"></iconify-icon></button>
    `;
    list.appendChild(div);
}

function addListItemRow() {
    const container = document.getElementById('listItemsContainer');
    if (!container) return;
    const div = document.createElement('div');
    div.className = 'list-item-row flex items-center gap-2';
    div.innerHTML = `
        <span class="text-gray-400 font-mono text-xs w-6">•</span>
        <input type="text" class="flex-1 bg-white border border-gray-200 rounded px-2 py-1 text-xs font-mono" placeholder="item value">
        <button class="text-gray-400 hover:text-red-500" onclick="this.parentElement.remove()"><iconify-icon icon="lucide:x" width="14"></iconify-icon></button>
    `;
    container.appendChild(div);
}

// ==========================================
// Terminal CLI Drawer
// ==========================================
function initTerminalDrawer() {
    const toggleBtn = document.getElementById('toggleTerminalBtn');
    const drawer = document.getElementById('terminalDrawer');
    const closeBtn = document.getElementById('drawerCloseBtn');
    const expandBtn = document.getElementById('drawerExpandBtn');
    const form = document.getElementById('drawerForm');
    const input = document.getElementById('drawerInput');

    if (toggleBtn) {
        toggleBtn.addEventListener('click', () => {
            drawer.classList.toggle('open');
            if (drawer.classList.contains('open')) input.focus();
        });
    }

    if (closeBtn) {
        closeBtn.addEventListener('click', () => {
            drawer.classList.remove('open');
        });
    }

    if (expandBtn) {
        expandBtn.addEventListener('click', () => {
            drawer.classList.toggle('expanded');
        });
    }

    if (form) {
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const cmd = input.value.trim();
            if (!cmd) return;
            input.value = '';
            appendTerminalLine(`redis-cli> ${cmd}`, 'prompt');

            try {
                const res = await fetch(`/api/cli?cmd=${encodeURIComponent(cmd)}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                    method: 'POST'
                });
                const data = await res.json();
                appendTerminalLine(data.output || '(nil)', data.error ? 'error' : 'output');
                fetchKeys();
                fetchStats();
            } catch (err) {
                appendTerminalLine('Error: ' + err.message, 'error');
            }
        });
    }
}

function execCliShortcut(cmd) {
    const input = document.getElementById('drawerInput');
    if (input) {
        input.value = cmd;
        document.getElementById('drawerForm').dispatchEvent(new Event('submit'));
    }
}

function clearCliTerminal() {
    const out = document.getElementById('drawerOutput');
    if (out) out.innerHTML = '';
}

function appendTerminalLine(text, type = 'output') {
    const out = document.getElementById('drawerOutput');
    if (!out) return;
    const div = document.createElement('div');
    div.className = `leading-relaxed ${type === 'prompt' ? 'text-gray-400 font-semibold' : type === 'error' ? 'text-red-400' : 'text-emerald-400'}`;
    div.textContent = text;
    out.appendChild(div);
    out.scrollTop = out.scrollHeight;
}

// ==========================================
// Insert Record Modal
// ==========================================
function initInsertModal() {
    const submitBtn = document.getElementById('insSubmitBtn');
    const typeSelect = document.getElementById('insType');

    if (typeSelect) {
        typeSelect.addEventListener('change', () => {
            const val = typeSelect.value;
            const lbl = document.getElementById('insValueLabel');
            const hint = document.getElementById('insValueHint');
            if (val === 'string') {
                lbl.textContent = 'Value Content *';
                hint.textContent = 'Raw string or JSON string';
            } else if (val === 'hash') {
                lbl.textContent = 'Hash JSON Object *';
                hint.textContent = 'e.g. {"name": "Alice", "role": "admin"}';
            } else {
                lbl.textContent = 'JSON Array of Elements *';
                hint.textContent = 'e.g. ["item1", "item2", "item3"]';
            }
        });
    }

    if (submitBtn) {
        submitBtn.addEventListener('click', async () => {
            const key = document.getElementById('insKey').value.trim();
            const type = document.getElementById('insType').value;
            const rawVal = document.getElementById('insValue').value.trim();
            const ttl = parseInt(document.getElementById('insTtl').value, 10);

            if (!key) return showToast('Key is required', true);

            let payload = { key, type };
            if (!isNaN(ttl) && ttl > 0) payload.ttl = ttl;

            try {
                if (type === 'string') {
                    payload.value = rawVal;
                } else if (type === 'hash') {
                    payload.fields = JSON.parse(rawVal);
                } else if (type === 'list' || type === 'set') {
                    payload.items = JSON.parse(rawVal);
                }

                const res = await fetch(`/api/key${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });

                if (res.ok) {
                    showToast(`Record "${key}" inserted`);
                    closeInsertModal();
                    fetchKeys();
                    fetchStats();
                } else {
                    const err = await res.json();
                    showToast(err.error || 'Insert failed', true);
                }
            } catch (err) {
                showToast('Invalid payload: ' + err.message, true);
            }
        });
    }
}

function openInsertModal() {
    const modal = document.getElementById('insertModal');
    if (modal) {
        modal.classList.remove('hidden');
        document.getElementById('insKey').focus();
    }
}

function closeInsertModal() {
    const modal = document.getElementById('insertModal');
    if (modal) modal.classList.add('hidden');
}

// ==========================================
// Virtual Database Engine & Hub
// ==========================================
function initVirtualDatabaseSelector() {
    const select = document.getElementById('vdbSelect');
    if (select) {
        select.addEventListener('change', (e) => {
            switchVirtualDatabase(e.target.value);
        });
    }
}

function switchVirtualDatabase(dbName) {
    activeVirtualDb = dbName;
    document.querySelectorAll('.vdbSelect').forEach(s => s.value = dbName);

    // Update active role badge
    let role = 'OWNER';
    if (activeVirtualDb !== 'default') {
        const foundMy = userDatabases.myDatabases.find(d => d.id === dbName);
        if (foundMy) role = 'OWNER';
        else {
            const foundShared = userDatabases.sharedWithMe.find(d => d.id === dbName);
            if (foundShared) role = foundShared.role || 'VIEWER';
        }
    }
    activeDatabaseRole = role;

    const badge = document.getElementById('activeDbRoleBadge');
    if (badge) {
        badge.textContent = role;
        badge.className = role === 'OWNER' ? 'px-1.5 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-purple-100 text-purple-700' : 'px-1.5 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-blue-100 text-blue-700';
    }

    const ovLbl = document.getElementById('ovCurrentDbLabel');
    if (ovLbl) ovLbl.textContent = dbName === 'default' ? 'db0' : dbName;

    currentPage = 1;
    selectedKeys.clear();
    updateBatchBar();
    fetchKeys();
    fetchStats();
    showToast(`Switched active keyspace to [${dbName}]`);
}

function initDatabaseHub() {
    const refreshBtn = document.getElementById('refreshDatabasesBtn');
    if (refreshBtn) refreshBtn.addEventListener('click', loadUserDatabases);

    const openCreateBtn = document.getElementById('openCreateDbModalBtn');
    if (openCreateBtn) openCreateBtn.addEventListener('click', openCreateDbModal);

    const closeCreateBtn = document.getElementById('closeCreateDbModalBtn');
    if (closeCreateBtn) closeCreateBtn.addEventListener('click', closeCreateDbModal);

    const cancelCreateBtn = document.getElementById('cancelCreateDbBtn');
    if (cancelCreateBtn) cancelCreateBtn.addEventListener('click', closeCreateDbModal);

    const createForm = document.getElementById('createDatabaseForm');
    if (createForm) {
        createForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const name = document.getElementById('createDbNameInput').value.trim();
            const id = document.getElementById('createDbIdInput').value.trim();
            const pass = document.getElementById('createDbPassInput').value.trim();

            try {
                const res = await fetch('/api/databases', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name, id, password: pass })
                });
                if (res.ok) {
                    showToast(`Database "${name}" created!`);
                    closeCreateDbModal();
                    loadUserDatabases();
                } else {
                    const err = await res.json();
                    showToast(err.error || 'Failed to create DB', true);
                }
            } catch (err) {
                showToast('Create DB error: ' + err.message, true);
            }
        });
    }

    // Tabs: My vs Shared
    const tabMy = document.getElementById('tabMyDbsBtn');
    const tabShared = document.getElementById('tabSharedDbsBtn');
    if (tabMy) {
        tabMy.addEventListener('click', () => {
            activeDbTab = 'my';
            tabMy.className = 'db-tab-btn flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-colors bg-purple-50 text-purple-700';
            tabShared.className = 'db-tab-btn flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 transition-colors';
            renderDatabaseCards();
        });
    }
    if (tabShared) {
        tabShared.addEventListener('click', () => {
            activeDbTab = 'shared';
            tabShared.className = 'db-tab-btn flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-colors bg-purple-50 text-purple-700';
            tabMy.className = 'db-tab-btn flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 transition-colors';
            renderDatabaseCards();
        });
    }

    initShareModalEvents();
}

async function loadUserDatabases() {
    try {
        const res = await fetch('/api/databases');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        userDatabases = {
            myDatabases: data.myDatabases || [],
            sharedWithMe: data.sharedWithMe || []
        };

        const myCount = userDatabases.myDatabases.length;
        const sharedCount = userDatabases.sharedWithMe.length;

        if (document.getElementById('statMyDbsCount')) document.getElementById('statMyDbsCount').textContent = myCount;
        if (document.getElementById('statSharedDbsCount')) document.getElementById('statSharedDbsCount').textContent = sharedCount;
        if (document.getElementById('tabMyDbsBadge')) document.getElementById('tabMyDbsBadge').textContent = myCount;
        if (document.getElementById('tabSharedDbsBadge')) document.getElementById('tabSharedDbsBadge').textContent = sharedCount;
        if (document.getElementById('sbDbsCountBadge')) document.getElementById('sbDbsCountBadge').textContent = (myCount + sharedCount);

        updateVirtualDatabaseSelector(userDatabases.myDatabases, userDatabases.sharedWithMe);
        renderDatabaseCards();

    } catch (err) {
        console.error('Error loading databases:', err);
    }
}

function updateVirtualDatabaseSelector(myDbs = [], sharedDbs = []) {
    const select = document.getElementById('vdbSelect');
    if (!select) return;

    let html = `<option value="default" ${activeVirtualDb === 'default' ? 'selected' : ''}>db0 (Default Root)</option>`;
    if (myDbs.length > 0) {
        html += `<optgroup label="My Databases">`;
        myDbs.forEach(d => {
            html += `<option value="${escapeHtml(d.id)}" ${activeVirtualDb === d.id ? 'selected' : ''}>${escapeHtml(d.name)} (${d.id})</option>`;
        });
        html += `</optgroup>`;
    }
    if (sharedDbs.length > 0) {
        html += `<optgroup label="Shared With Me">`;
        sharedDbs.forEach(d => {
            html += `<option value="${escapeHtml(d.id)}" ${activeVirtualDb === d.id ? 'selected' : ''}>${escapeHtml(d.name)} [${d.role}]</option>`;
        });
        html += `</optgroup>`;
    }
    select.innerHTML = html;
}

function renderDatabaseCards() {
    const container = document.getElementById('databaseCardsContainer');
    if (!container) return;

    const list = activeDbTab === 'my' ? userDatabases.myDatabases : userDatabases.sharedWithMe;

    if (list.length === 0) {
        container.innerHTML = `
            <div class="col-span-full py-16 text-center text-gray-500 bg-white rounded-xl border border-gray-200">
                <iconify-icon icon="lucide:database" width="36" class="text-gray-300 mx-auto mb-2"></iconify-icon>
                <div class="font-medium text-gray-800">No databases in this view</div>
                <div class="text-xs text-gray-400 mt-1">${activeDbTab === 'my' ? 'Create a new database to get started' : 'No team databases have been shared with you yet'}</div>
            </div>
        `;
        return;
    }

    container.innerHTML = list.map(db => `
        <div class="bg-white p-6 rounded-xl border border-gray-200 shadow-sm hover:shadow-md transition-all flex flex-col justify-between">
            <div class="space-y-4">
                <div class="flex items-center justify-between">
                    <div class="flex items-center gap-2">
                        <span class="w-2.5 h-2.5 rounded-full bg-emerald-500"></span>
                        <h4 class="font-semibold text-gray-900 text-base truncate">${escapeHtml(db.name)}</h4>
                    </div>
                    <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${db.role === 'OWNER' || activeDbTab === 'my' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'}">${db.role || 'OWNER'}</span>
                </div>
                <div class="space-y-1.5 font-mono text-xs text-gray-500">
                    <div class="flex justify-between">
                        <span>Database ID:</span>
                        <span class="text-gray-900 font-semibold">${escapeHtml(db.id)}</span>
                    </div>
                    <div class="flex justify-between">
                        <span>Records:</span>
                        <span class="text-gray-900 font-semibold">${(db.keyCount || 0).toLocaleString()} keys</span>
                    </div>
                    <div class="flex justify-between">
                        <span>Memory:</span>
                        <span class="text-gray-900 font-semibold">${formatBytes(db.memoryUsage || 0)}</span>
                    </div>
                </div>
            </div>

            <div class="pt-5 mt-4 border-t border-gray-100 flex items-center justify-between gap-2">
                <button class="flex-1 py-1.5 px-3 bg-purple-50 hover:bg-purple-100 text-purple-700 rounded-lg text-xs font-semibold transition-colors text-center" onclick="openDatabaseInDatasheet('${escapeHtml(db.id)}')">
                    Open
                </button>
                <button class="py-1.5 px-2.5 border border-gray-200 hover:bg-gray-50 text-gray-700 rounded-lg text-xs font-medium transition-colors" onclick="openConnectModalForDb('${escapeHtml(db.id)}')" title="Connect URLs">
                    <iconify-icon icon="lucide:link-2" width="14"></iconify-icon>
                </button>
                <button class="py-1.5 px-2.5 border border-gray-200 hover:bg-gray-50 text-gray-700 rounded-lg text-xs font-medium transition-colors" onclick="openShareModal('${escapeHtml(db.id)}')" title="Share Database">
                    <iconify-icon icon="lucide:share-2" width="14"></iconify-icon>
                </button>
            </div>
        </div>
    `).join('');
}

function openDatabaseInDatasheet(dbId) {
    switchVirtualDatabase(dbId);
    navigateToPane('pane-datasheet');
}

function openCreateDbModal() {
    const modal = document.getElementById('createDbModal');
    if (modal) {
        modal.classList.remove('hidden');
        document.getElementById('createDbNameInput').focus();
    }
}

function closeCreateDbModal() {
    const modal = document.getElementById('createDbModal');
    if (modal) modal.classList.add('hidden');
}

// ==========================================
// Share Database Modal & Invite Links
// ==========================================
function initShareModalEvents() {
    const closeBtn = document.getElementById('closeShareDbModalBtn');
    const closeBtn2 = document.getElementById('closeShareDbModalBtn2');
    if (closeBtn) closeBtn.addEventListener('click', closeShareModal);
    if (closeBtn2) closeBtn2.addEventListener('click', closeShareModal);

    const form = document.getElementById('inviteCollaboratorForm');
    if (form) {
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!activeShareDbId) return;

            const email = document.getElementById('shareTargetEmailInput').value.trim();
            const role = document.getElementById('shareTargetRoleSelect').value;

            try {
                const res = await fetch(`/api/databases/share?db=${encodeURIComponent(activeShareDbId)}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email, role })
                });

                if (res.ok) {
                    showToast(`Invited ${email} as ${role}!`);
                    document.getElementById('shareTargetEmailInput').value = '';
                    openShareModal(activeShareDbId);
                    loadUserDatabases();
                } else {
                    const err = await res.json();
                    showToast(err.error || 'Failed to invite', true);
                }
            } catch (err) {
                showToast('Invite error: ' + err.message, true);
            }
        });
    }

    const linkCheck = document.getElementById('shareLinkEnableCheck');
    const linkRoleSelect = document.getElementById('shareLinkRoleSelect');
    if (linkCheck) {
        linkCheck.addEventListener('change', async () => {
            if (!activeShareDbId) return;
            const enabled = linkCheck.checked;
            const role = linkRoleSelect ? linkRoleSelect.value : 'VIEWER';

            try {
                const res = await fetch(`/api/databases/share-link?db=${encodeURIComponent(activeShareDbId)}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled, role })
                });
                if (res.ok) {
                    openShareModal(activeShareDbId);
                }
            } catch (err) {
                console.error(err);
            }
        });
    }

    const copyLinkBtn = document.getElementById('copyShareLinkBtn');
    if (copyLinkBtn) {
        copyLinkBtn.addEventListener('click', () => {
            const input = document.getElementById('shareLinkInput');
            if (input) copyToClipboard(input.value, 'Shareable invite link copied!');
        });
    }
}

async function openShareModal(dbId) {
    activeShareDbId = dbId;
    const modal = document.getElementById('shareDbModal');
    if (!modal) return;

    modal.classList.remove('hidden');
    document.getElementById('shareDbModalSubtitle').textContent = `Manage collaborators for database [${dbId}]`;

    try {
        const res = await fetch(`/api/databases/details?db=${encodeURIComponent(dbId)}`);
        if (res.ok) {
            const db = await res.json();
            renderShareModalCollaborators(db);

            // Share link state
            const check = document.getElementById('shareLinkEnableCheck');
            const statusLbl = document.getElementById('shareLinkStatusLabel');
            const linkBody = document.getElementById('shareLinkBody');
            const linkInput = document.getElementById('shareLinkInput');

            if (db.shareLinkEnabled && db.shareLinkToken) {
                if (check) check.checked = true;
                if (statusLbl) statusLbl.textContent = 'Active';
                if (linkBody) linkBody.classList.remove('hidden');
                if (linkInput) {
                    const origin = window.location.origin;
                    linkInput.value = `${origin}/#join=${db.shareLinkToken}`;
                }
            } else {
                if (check) check.checked = false;
                if (statusLbl) statusLbl.textContent = 'Disabled';
                if (linkBody) linkBody.classList.add('hidden');
            }
        }
    } catch (err) {
        console.error('Failed to load DB details:', err);
    }
}

function renderShareModalCollaborators(db) {
    const list = document.getElementById('shareCollaboratorsList');
    if (!list) return;

    const collabs = db.collaborators || [];
    list.innerHTML = `
        <div class="flex items-center justify-between py-2 border-b border-gray-100">
            <div class="flex items-center gap-2.5">
                <img src="https://api.dicebear.com/7.x/identicon/svg?seed=${escapeHtml(db.ownerEmail || 'Owner')}" class="w-8 h-8 rounded-full border border-gray-200">
                <div>
                    <div class="text-xs font-semibold text-gray-900">${escapeHtml(db.ownerEmail || 'Owner')}</div>
                    <div class="text-[10px] text-gray-400">Creator &amp; Owner</div>
                </div>
            </div>
            <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-purple-100 text-purple-700">OWNER</span>
        </div>
        ${collabs.map(c => `
            <div class="flex items-center justify-between py-2 border-b border-gray-100">
                <div class="flex items-center gap-2.5">
                    <img src="https://api.dicebear.com/7.x/identicon/svg?seed=${escapeHtml(c.email)}" class="w-8 h-8 rounded-full border border-gray-200">
                    <div>
                        <div class="text-xs font-semibold text-gray-900">${escapeHtml(c.email)}</div>
                        <div class="text-[10px] text-gray-400">Role: ${c.role}</div>
                    </div>
                </div>
                <div class="flex items-center gap-2">
                    <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-blue-100 text-blue-700">${c.role}</span>
                    <button class="text-gray-400 hover:text-red-600 p-1" onclick="removeCollaborator('${escapeHtml(db.id)}', '${escapeHtml(c.email)}')">
                        <iconify-icon icon="lucide:trash-2" width="13"></iconify-icon>
                    </button>
                </div>
            </div>
        `).join('')}
    `;
}

async function removeCollaborator(dbId, email) {
    if (!confirm(`Remove access for ${email}?`)) return;
    try {
        const res = await fetch(`/api/databases/collaborator?db=${encodeURIComponent(dbId)}&email=${encodeURIComponent(email)}`, {
            method: 'DELETE'
        });
        if (res.ok) {
            showToast(`Removed access for ${email}`);
            openShareModal(dbId);
        }
    } catch (err) {
        showToast('Remove error: ' + err.message, true);
    }
}

function closeShareModal() {
    const modal = document.getElementById('shareDbModal');
    if (modal) modal.classList.add('hidden');
    activeShareDbId = null;
}

// ==========================================
// Connect Modal (Prisma, ioredis, Python, CLI)
// ==========================================
function initConnectModal() {
    const openBtn = document.getElementById('openConnectModalBtn');
    const closeBtn = document.getElementById('closeConnectModalBtn');
    const closeBtn2 = document.getElementById('closeConnectModalBtn2');

    if (openBtn) openBtn.addEventListener('click', openConnectModal);
    if (closeBtn) closeBtn.addEventListener('click', closeConnectModal);
    if (closeBtn2) closeBtn2.addEventListener('click', closeConnectModal);

    const tabs = document.querySelectorAll('.connect-tab[data-tab]');
    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.className = 'connect-tab px-3 py-1.5 rounded-lg text-gray-600 hover:bg-gray-100 flex items-center gap-1.5');
            tab.className = 'connect-tab px-3 py-1.5 rounded-lg bg-purple-50 text-purple-700 font-semibold flex items-center gap-1.5';
            activeConnectTab = tab.dataset.tab;
            renderConnectDetails();
        });
    });

    const accountSelect = document.getElementById('connectAccountSelect');
    if (accountSelect) {
        accountSelect.addEventListener('change', (e) => {
            activeConnectTenantUsername = e.target.value;
            renderConnectDetails();
        });
    }

    const copyRedisUrlBtn = document.getElementById('copyConnRedisUrlBtn');
    if (copyRedisUrlBtn) {
        copyRedisUrlBtn.addEventListener('click', () => {
            const input = document.getElementById('connRedisUrlInput');
            if (input) copyToClipboard(input.value, 'Redis URL copied!');
        });
    }

    const copyTokenUrlBtn = document.getElementById('copyConnTokenUrlBtn');
    if (copyTokenUrlBtn) {
        copyTokenUrlBtn.addEventListener('click', () => {
            const input = document.getElementById('connTokenUrlInput');
            if (input) copyToClipboard(input.value, 'Token-Only URL copied!');
        });
    }

    const copySnippetBtn = document.getElementById('copySnippetBtn');
    if (copySnippetBtn) {
        copySnippetBtn.addEventListener('click', () => {
            const code = document.getElementById('connectSnippetCode');
            if (code) copyToClipboard(code.innerText, 'Code snippet copied!');
        });
    }
}

function openConnectModal() {
    const modal = document.getElementById('connectModal');
    if (!modal) return;
    modal.classList.remove('hidden');
    populateConnectAccountSelector();
    renderConnectDetails();
}

function openConnectModalForDb(dbId) {
    openConnectModal();
    const select = document.getElementById('connectAccountSelect');
    if (select) {
        select.value = dbId;
        activeConnectTenantUsername = dbId;
        renderConnectDetails();
    }
}

function closeConnectModal() {
    const modal = document.getElementById('connectModal');
    if (modal) modal.classList.add('hidden');
}

function populateConnectAccountSelector() {
    const select = document.getElementById('connectAccountSelect');
    if (!select) return;

    let html = `
        <option value="admin">Root Admin (admin)</option>
        <option value="tenant-app">Tenant App (tenant-app)</option>
        <option value="readonly">Read Only (readonly)</option>
    `;

    userDatabases.myDatabases.forEach(d => {
        html += `<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)} (${d.id}) [OWNER]</option>`;
    });

    userDatabases.sharedWithMe.forEach(d => {
        html += `<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)} (${d.id}) [${d.role}]</option>`;
    });

    select.innerHTML = html;
    if (activeVirtualDb !== 'default') select.value = activeVirtualDb;
}

function renderConnectDetails() {
    const host = window.location.hostname || 'localhost';
    const redisPort = 6379;
    const webPort = window.location.port || '8080';
    const username = activeConnectTenantUsername;

    let password = 'tok_admin_live_secret';
    let dbSuffix = '';

    if (username === 'tenant-app') {
        password = 'tok_app_tenant';
    } else if (username === 'readonly') {
        password = 'tok_ro_public';
    } else {
        // Virtual database instance
        const found = [...userDatabases.myDatabases, ...userDatabases.sharedWithMe].find(d => d.id === username);
        if (found) {
            password = found.password || 'db_sec_' + username.slice(0, 6);
            dbSuffix = `?db=${username}`;
        }
    }

    const stdUrl = `redis://${encodeURIComponent(username)}:${encodeURIComponent(password)}@${host}:${redisPort}${dbSuffix}`;
    const tokenUrl = `redis://:${encodeURIComponent(password)}@${host}:${redisPort}`;

    const redisUrlInp = document.getElementById('connRedisUrlInput');
    const tokenUrlInp = document.getElementById('connTokenUrlInput');
    if (redisUrlInp) redisUrlInp.value = stdUrl;
    if (tokenUrlInp) tokenUrlInp.value = tokenUrl;

    renderConnectSnippet(username, stdUrl, tokenUrl, host, redisPort, webPort);
}

function renderConnectSnippet(user, stdUrl, tokUrl, host, redisPort, webPort) {
    const titleEl = document.getElementById('snippetLanguageTitle');
    const codeEl = document.getElementById('connectSnippetCode');
    if (!codeEl) return;

    if (activeConnectTab === 'prisma') {
        if (titleEl) titleEl.textContent = 'schema.prisma & .env Configuration';
        codeEl.innerHTML = escapeHtml(`// 1. In your .env file:
REDIS_URL="${stdUrl}"

// 2. In your schema.prisma or Prisma Accelerate config:
datasource db {
  provider = "postgresql" // or mysql
  url      = env("DATABASE_URL")
}

// 3. Connect via standard ioredis inside Prisma extensions:
import { PrismaClient } from '@prisma/client';
import Redis from 'ioredis';

const prisma = new PrismaClient();
const redis = new Redis(process.env.REDIS_URL);

// High-speed cached query
export async function getUser(id: string) {
  const cached = await redis.get(\`user:\${id}\`);
  if (cached) return JSON.parse(cached);

  const user = await prisma.user.findUnique({ where: { id } });
  if (user) await redis.set(\`user:\${id}\`, JSON.stringify(user), 'EX', 3600);
  return user;
}`);
    } else if (activeConnectTab === 'ioredis') {
        if (titleEl) titleEl.textContent = 'Node.js (ioredis)';
        codeEl.innerHTML = escapeHtml(`import Redis from 'ioredis';

// Connect using standard URL
const redis = new Redis("${stdUrl}");

// Test Ping & Write
await redis.set('app:welcome', 'Hello from Node.js!');
const val = await redis.get('app:welcome');
console.log('Redis response:', val);`);
    } else if (activeConnectTab === 'python') {
        if (titleEl) titleEl.textContent = 'Python (redis-py)';
        codeEl.innerHTML = escapeHtml(`import redis

r = redis.from_url("${stdUrl}")

# Execute commands
r.set('python:status', 'active')
print("Status:", r.get('python:status').decode('utf-8'))`);
    } else if (activeConnectTab === 'cli') {
        if (titleEl) titleEl.textContent = 'Redis CLI Terminal';
        codeEl.innerHTML = escapeHtml(`# Connect with standard URL
redis-cli -u "${stdUrl}"

# Or connect directly with password flag
redis-cli -h ${host} -p ${redisPort} -a "${user === 'admin' ? 'tok_admin_live_secret' : 'password'}"`);
    } else if (activeConnectTab === 'rest') {
        if (titleEl) titleEl.textContent = 'cURL REST API';
        codeEl.innerHTML = escapeHtml(`# Read key over Edge HTTP
curl -H "Authorization: Bearer tok_admin_live_secret" \\
  http://${host}:${webPort}/v1/get/user:1001:profile

# Write key over Edge HTTP
curl -X POST -H "Authorization: Bearer tok_admin_live_secret" \\
  -H "Content-Type: application/json" \\
  -d '{"key": "user:1001:profile", "value": "{\\"name\\": \\"Alice\\"}"}' \\
  http://${host}:${webPort}/v1/set`);
    }
}

// ==========================================
// Authentication, Landing Page & Production Flow
// ==========================================

const LANDING_CODE_SNIPPETS = {
    prisma: {
        filename: 'schema.prisma & client.ts',
        code: `// 1. Configure datasource in schema.prisma or .env
DATABASE_URL="redis://db_primary_cache:sec_admin_cache_99@localhost:6379"

// 2. High-speed caching in Next.js / Node.js
import { Redis } from "ioredis";
const redis = new Redis(process.env.DATABASE_URL);

// Instant read/write with sub-millisecond Loom Virtual Thread latency
await redis.set("user:1001:profile", JSON.stringify({ id: 1001, name: "Alice", role: "admin" }), "EX", 3600);
const cached = await redis.get("user:1001:profile");
console.log("Cached User:", JSON.parse(cached));`
    },
    ioredis: {
        filename: 'cache.service.ts',
        code: `import Redis from "ioredis";

// Connect directly to virtualized Redis keyspace
const redis = new Redis({
  host: "localhost",
  port: 6379,
  username: "db_primary_cache",       // Isolated virtual database ID
  password: "sec_admin_cache_99",     // Dedicated database password
  lazyConnect: true
});

await redis.connect();
await redis.hset("session:token:990", { userId: "usr_1001", role: "editor" });
const session = await redis.hgetall("session:token:990");
console.log("Active Session:", session);`
    },
    python: {
        filename: 'redis_client.py',
        code: `import redis

# Connect to isolated virtual keyspace
client = redis.Redis(
    host='localhost',
    port=6379,
    username='db_primary_cache',
    password='sec_admin_cache_99',
    decode_responses=True
)

# Benchmark rapid in-memory operations
client.set('model:weights:v1', 'loaded', ex=600)
status = client.get('model:weights:v1')
print(f"Status: {status} | DB Size: {client.dbsize()}")`
    },
    cli: {
        filename: 'terminal.sh',
        code: `# Connect with native standard redis-cli client
redis-cli -h localhost -p 6379 -a sec_admin_cache_99

# Select or authenticate to virtual keyspace
127.0.0.1:6379> AUTH db_primary_cache sec_admin_cache_99
OK
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> SET cluster:status "online" EX 300
OK
127.0.0.1:6379> GET cluster:status
"online"`
    },
    rest: {
        filename: 'curl_rest_api.sh',
        code: `# Direct HTTP REST execution from Edge Runtimes (Cloudflare Workers, Vercel)
# GET Key
curl -H "Authorization: Bearer tok_admin_live_secret" \\
  http://localhost:8080/v1/get/cluster:status

# POST Set Key with Expiration
curl -X POST -H "Authorization: Bearer tok_admin_live_secret" \\
  -H "Content-Type: application/json" \\
  -d '{"key": "cache:edge:flag", "value": "enabled", "ttl": 300}' \\
  http://localhost:8080/v1/set`
    }
};

function initLandingPage() {
    const tabs = document.querySelectorAll('.landing-tab-btn');
    const codeBlock = document.getElementById('landingCodeBlock');
    const filenameLabel = document.getElementById('landingCodeFilename');
    const copyBtn = document.getElementById('copyLandingSnippetBtn');
    const copyText = document.getElementById('copyLandingSnippetText');
    const copyCliBtn = document.getElementById('copyQuickCliBtn');

    if (tabs.length && codeBlock) {
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                tabs.forEach(t => {
                    t.className = 'landing-tab-btn px-2.5 py-1 rounded text-slate-400 hover:text-white';
                });
                tab.className = 'landing-tab-btn active px-2.5 py-1 rounded bg-purple-600 text-white font-medium';
                const key = tab.getAttribute('data-tab');
                if (LANDING_CODE_SNIPPETS[key]) {
                    codeBlock.textContent = LANDING_CODE_SNIPPETS[key].code;
                    if (filenameLabel) filenameLabel.textContent = LANDING_CODE_SNIPPETS[key].filename;
                }
            });
        });
    }

    if (copyBtn && codeBlock) {
        copyBtn.addEventListener('click', () => {
            navigator.clipboard.writeText(codeBlock.textContent);
            if (copyText) copyText.textContent = 'Copied!';
            setTimeout(() => {
                if (copyText) copyText.textContent = 'Copy Code';
            }, 2000);
        });
    }

    if (copyCliBtn) {
        copyCliBtn.addEventListener('click', () => {
            const cmd = document.getElementById('quickCliCommand');
            if (cmd) {
                navigator.clipboard.writeText(cmd.textContent.trim());
                showToast('CLI command copied to clipboard');
            }
        });
    }

    // Connect landing CTAs to Auth modal
    const signIns = ['landingSignInBtn', 'heroSignInBtn', 'ctaSignInBtn'];
    signIns.forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('click', () => openAuthModal('login'));
    });

    const registers = ['landingGetStartedBtn', 'heroDeployBtn', 'ctaDeployBtn'];
    registers.forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('click', () => openAuthModal('register'));
    });
}

function showLandingPage() {
    const landing = document.getElementById('publicLandingPage');
    const dashboard = document.getElementById('appDashboard');
    if (landing) landing.classList.remove('hidden');
    if (dashboard) dashboard.classList.add('hidden');
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

function showDashboard() {
    const landing = document.getElementById('publicLandingPage');
    const dashboard = document.getElementById('appDashboard');
    if (landing) landing.classList.add('hidden');
    if (dashboard) dashboard.classList.remove('hidden');
}

async function initAuth() {
    initLandingPage();

    const profileBtn = document.getElementById('userProfileBtn');
    const dropdown = document.getElementById('userDropdownCard');
    const signOutBtn = document.getElementById('menuSignOutBtn');
    const adminAccountsBtn = document.getElementById('adminAccountsBtn');
    const closeAuthBtn = document.getElementById('closeAuthModalBtn');
    const closeAdminBtn = document.getElementById('closeAdminAccountsModalBtn');
    const closeAdminBtn2 = document.getElementById('closeAdminAccountsModalBtn2');

    if (profileBtn && dropdown) {
        profileBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            dropdown.classList.toggle('hidden');
        });
        document.addEventListener('click', (e) => {
            if (!dropdown.contains(e.target) && !profileBtn.contains(e.target)) {
                dropdown.classList.add('hidden');
            }
        });
    }

    if (signOutBtn) {
        signOutBtn.addEventListener('click', async () => {
            try {
                await fetch('/api/auth/logout', { method: 'POST' });
            } catch (err) {}
            currentSessionToken = null;
            currentUser = null;
            localStorage.removeItem('redis_cloud_session');
            localStorage.removeItem('redis_studio_session');
            if (dropdown) dropdown.classList.add('hidden');
            showLandingPage();
            showToast('Signed out successfully.');
        });
    }

    if (closeAuthBtn) closeAuthBtn.addEventListener('click', closeAuthModal);
    if (closeAdminBtn) closeAdminBtn.addEventListener('click', closeAdminAccountsModal);
    if (closeAdminBtn2) closeAdminBtn2.addEventListener('click', closeAdminAccountsModal);

    if (adminAccountsBtn) {
        adminAccountsBtn.addEventListener('click', () => {
            if (dropdown) dropdown.classList.add('hidden');
            openAdminAccountsModal();
        });
    }

    // Google Sign In
    const googleBtn = document.getElementById('googleSignInBtn');
    if (googleBtn) {
        googleBtn.addEventListener('click', async () => {
            const email = prompt('Enter your Google Account email for Single Sign-On:', 'developer@gmail.com');
            if (!email || !email.includes('@')) return;
            try {
                const res = await fetch('/api/auth/google', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email: email.trim(), name: email.split('@')[0], avatar: `https://api.dicebear.com/7.x/identicon/svg?seed=${encodeURIComponent(email)}` })
                });
                const data = await res.json();
                if (res.ok && data.token) {
                    onAuthSuccess(data);
                } else {
                    showAuthError(data.error || 'Google authentication failed');
                }
            } catch (err) {
                showAuthError('Connection error during Google Sign-In');
            }
        });
    }

    // Real Login Form
    const loginForm = document.getElementById('authLoginForm');
    if (loginForm) {
        loginForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            hideAuthError();
            const email = document.getElementById('loginEmailInput').value.trim();
            const password = document.getElementById('loginPasswordInput').value;

            const submitBtn = document.getElementById('loginSubmitBtn');
            if (submitBtn) submitBtn.disabled = true;

            try {
                const res = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email, password })
                });
                const data = await res.json();
                if (res.ok && data.token) {
                    onAuthSuccess(data);
                } else {
                    showAuthError(data.error || 'Invalid email or password');
                }
            } catch (err) {
                showAuthError('Unable to connect to authentication service');
            } finally {
                if (submitBtn) submitBtn.disabled = false;
            }
        });
    }

    // Real Register Form
    const regForm = document.getElementById('authRegisterForm');
    if (regForm) {
        regForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            hideAuthError();
            const name = document.getElementById('regNameInput').value.trim();
            const email = document.getElementById('regEmailInput').value.trim();
            const password = document.getElementById('regPasswordInput').value;

            const submitBtn = document.getElementById('regSubmitBtn');
            if (submitBtn) submitBtn.disabled = true;

            try {
                const res = await fetch('/api/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email, password, name })
                });
                const data = await res.json();
                if (res.ok && data.token) {
                    onAuthSuccess(data, true);
                } else {
                    showAuthError(data.error || 'Failed to create account');
                }
            } catch (err) {
                showAuthError('Unable to connect to registration service');
            } finally {
                if (submitBtn) submitBtn.disabled = false;
            }
        });
    }

    // Tabs inside modal
    const tabLogin = document.getElementById('authTabLogin');
    const tabReg = document.getElementById('authTabRegister');
    if (tabLogin && tabReg) {
        tabLogin.addEventListener('click', () => switchAuthTab('login'));
        tabReg.addEventListener('click', () => switchAuthTab('register'));
    }

    // Auto-check existing session on page load
    if (currentSessionToken) {
        try {
            const res = await fetch('/api/auth/me');
            if (res.ok) {
                const data = await res.json();
                if (data.authenticated && data.user) {
                    currentUser = data.user;
                    updateUserProfileUI(currentUser);
                    showDashboard();
                    startAuthenticatedSession();
                    return;
                }
            }
        } catch (err) {
            console.warn('Session verification error:', err);
        }
    }

    // If unauthenticated: show Landing Page
    currentSessionToken = null;
    currentUser = null;
    showLandingPage();
}

function onAuthSuccess(data, isNew = false) {
    currentSessionToken = data.token;
    localStorage.setItem('redis_cloud_session', currentSessionToken);
    currentUser = data.user;
    updateUserProfileUI(currentUser);
    closeAuthModal();
    showDashboard();
    startAuthenticatedSession();
    showToast(isNew ? `Welcome to Redis Cloud, ${currentUser.name}!` : `Welcome back, ${currentUser.name}!`);
}

function startAuthenticatedSession() {
    loadUserDatabases();
    fetchKeys();
    fetchStats();
    loadTenants();
    fetchOverviewMetrics();
    checkInviteHash();
}

function switchAuthTab(tab) {
    hideAuthError();
    const tabLogin = document.getElementById('authTabLogin');
    const tabReg = document.getElementById('authTabRegister');
    const formLogin = document.getElementById('authLoginForm');
    const formReg = document.getElementById('authRegisterForm');

    if (tab === 'login') {
        if (tabLogin) tabLogin.className = 'flex-1 py-1.5 rounded-md bg-white text-gray-900 shadow-sm transition-all';
        if (tabReg) tabReg.className = 'flex-1 py-1.5 rounded-md text-gray-500 hover:text-gray-900 transition-all';
        if (formLogin) formLogin.classList.remove('hidden');
        if (formReg) formReg.classList.add('hidden');
    } else {
        if (tabReg) tabReg.className = 'flex-1 py-1.5 rounded-md bg-white text-gray-900 shadow-sm transition-all';
        if (tabLogin) tabLogin.className = 'flex-1 py-1.5 rounded-md text-gray-500 hover:text-gray-900 transition-all';
        if (formReg) formReg.classList.remove('hidden');
        if (formLogin) formLogin.classList.add('hidden');
    }
}

function showAuthError(msg) {
    const box = document.getElementById('authErrorMessage');
    const text = document.getElementById('authErrorText');
    if (box && text) {
        text.textContent = msg;
        box.classList.remove('hidden');
    }
}

function hideAuthError() {
    const box = document.getElementById('authErrorMessage');
    if (box) box.classList.add('hidden');
}

function openAuthModal(tab = 'login') {
    switchAuthTab(tab);
    const modal = document.getElementById('authModal');
    if (modal) modal.classList.remove('hidden');
}

function closeAuthModal() {
    const modal = document.getElementById('authModal');
    if (modal) modal.classList.add('hidden');
}

function openAdminAccountsModal() {
    const modal = document.getElementById('adminAccountsModal');
    if (modal) {
        modal.classList.remove('hidden');
        loadAdminAccountsList();
    }
}

function closeAdminAccountsModal() {
    const modal = document.getElementById('adminAccountsModal');
    if (modal) modal.classList.add('hidden');
}

async function loadAdminAccountsList() {
    const list = document.getElementById('adminAccountsList');
    if (!list) return;
    list.innerHTML = '<div class="py-4 text-center text-xs text-gray-500">Loading system accounts...</div>';

    try {
        const res = await fetch('/api/auth/demo-users');
        if (!res.ok) {
            list.innerHTML = '<div class="py-4 text-center text-xs text-red-500">Superadmin privileges required.</div>';
            return;
        }
        const data = await res.json();
        const users = data.users || [];
        if (!users.length) {
            list.innerHTML = '<div class="py-4 text-center text-xs text-gray-500">No registered accounts found.</div>';
            return;
        }

        list.innerHTML = users.map(u => `
            <div class="py-3 flex items-center justify-between">
                <div class="flex items-center gap-3">
                    <img src="${u.avatarUrl || 'https://api.dicebear.com/7.x/identicon/svg?seed=' + encodeURIComponent(u.email)}" class="w-8 h-8 rounded-full border border-gray-200">
                    <div>
                        <div class="text-xs font-semibold text-gray-900 flex items-center gap-1.5">
                            <span>${u.name || u.email}</span>
                            <span class="px-1.5 py-0.5 rounded text-[10px] font-bold uppercase ${u.role === 'admin' ? 'bg-purple-100 text-purple-700' : 'bg-gray-100 text-gray-600'}">${u.role || 'user'}</span>
                        </div>
                        <div class="text-[11px] text-gray-500 font-mono">${u.email}</div>
                    </div>
                </div>
                <div class="text-xs text-gray-400 font-mono">${u.id}</div>
            </div>
        `).join('');
    } catch (err) {
        list.innerHTML = '<div class="py-4 text-center text-xs text-red-500">Error loading system accounts.</div>';
    }
}

function updateUserProfileUI(user) {
    if (!user) return;
    const avatar = document.getElementById('userAvatarImg');
    const dropAvatar = document.getElementById('userDropdownAvatar');
    const dropName = document.getElementById('userDropdownName');
    const dropEmail = document.getElementById('userDropdownEmail');
    const adminSection = document.getElementById('adminMenuSection');

    const avatarUrl = user.avatarUrl || user.avatar || `https://api.dicebear.com/7.x/identicon/svg?seed=${encodeURIComponent(user.email)}`;

    if (avatar) avatar.src = avatarUrl;
    if (dropAvatar) dropAvatar.src = avatarUrl;
    if (dropName) dropName.textContent = user.name || user.email.split('@')[0];
    if (dropEmail) dropEmail.textContent = user.email;

    // Superadmin verification: ONLY admin@gmail.com or role 'admin' can see the superadmin tools
    if (adminSection) {
        if (user.role === 'admin' || user.email === 'admin@gmail.com' || user.isAdmin === true) {
            adminSection.classList.remove('hidden');
        } else {
            adminSection.classList.add('hidden');
        }
    }
}

// Check join invite hash in URL (e.g. #join=lnk_xxxx)
async function checkInviteHash() {
    const hash = window.location.hash;
    if (hash && hash.startsWith('#join=')) {
        const token = hash.replace('#join=', '');
        try {
            const res = await fetch(`/api/databases/join?token=${encodeURIComponent(token)}`, {
                method: 'POST'
            });
            if (res.ok) {
                const data = await res.json();
                showToast(`Joined database "${data.databaseName || data.databaseId}" as ${data.role}!`);
                window.location.hash = '';
                loadUserDatabases();
                if (data.databaseId) switchVirtualDatabase(data.databaseId);
            }
        } catch (err) {
            console.error('Join error:', err);
        }
    }
}

// ==========================================
// Cloud REST API & RLS Playground
// ==========================================
function initCloudApiPlayground() {
    const epSelect = document.getElementById('apiEndpointSelect');
    const tokenSelect = document.getElementById('apiTokenSelect');
    const keyInput = document.getElementById('apiTargetKey');
    const executeBtn = document.getElementById('apiExecuteBtn');
    const copyCurlBtn = document.getElementById('copyCurlBtn');
    const refreshAclBtn = document.getElementById('refreshAclBtn');

    if (epSelect) epSelect.addEventListener('change', updateCurlSnippet);
    if (tokenSelect) {
        tokenSelect.addEventListener('change', () => {
            const isCustom = tokenSelect.value === 'custom';
            const grp = document.getElementById('apiCustomTokenGroup');
            if (grp) grp.classList.toggle('hidden', !isCustom);
            updateCurlSnippet();
        });
    }
    if (keyInput) keyInput.addEventListener('input', updateCurlSnippet);

    if (executeBtn) {
        executeBtn.addEventListener('click', async () => {
            const endpoint = epSelect.value;
            const token = getSelectedApiToken();
            const key = keyInput.value.trim();
            const body = document.getElementById('apiRequestBody').value.trim();
            const respStatus = document.getElementById('apiResponseStatus');
            const respBox = document.getElementById('apiResponseBox');

            respStatus.textContent = 'Sending...';
            respStatus.className = 'text-xs font-bold text-purple-600';
            respBox.textContent = '// Request in-flight...';

            try {
                let url = '';
                let method = 'GET';
                let reqBody = null;

                if (endpoint === 'GET_KEY') {
                    url = `/v1/get/${encodeURIComponent(key)}`;
                    method = 'GET';
                } else if (endpoint === 'SET_KEY') {
                    url = `/v1/set`;
                    method = 'POST';
                    reqBody = body || JSON.stringify({ key, value: 'sample_value', ttl: 3600 });
                } else if (endpoint === 'LPUSH') {
                    url = `/v1/lpush`;
                    method = 'POST';
                    reqBody = body || JSON.stringify({ key, values: ['alpha', 'beta'] });
                } else if (endpoint === 'LRANGE') {
                    url = `/v1/lrange/${encodeURIComponent(key)}?start=0&stop=-1`;
                    method = 'GET';
                } else if (endpoint === 'HSET') {
                    url = `/v1/hset`;
                    method = 'POST';
                    reqBody = body || JSON.stringify({ key, fields: { status: 'active', count: '42' } });
                } else if (endpoint === 'HGETALL') {
                    url = `/v1/hgetall/${encodeURIComponent(key)}`;
                    method = 'GET';
                } else if (endpoint === 'DEL_KEY') {
                    url = `/v1/del/${encodeURIComponent(key)}`;
                    method = 'DELETE';
                } else if (endpoint === 'PIPELINE') {
                    url = `/v1/pipeline`;
                    method = 'POST';
                    reqBody = body || JSON.stringify({ commands: [['SET', 'a', '1'], ['GET', 'a']] });
                } else if (endpoint === 'RAW_CMD') {
                    url = `/v1/command`;
                    method = 'POST';
                    reqBody = body || JSON.stringify({ command: 'PING' });
                }

                const res = await fetch(url, {
                    method: method,
                    headers: {
                        'Authorization': `Bearer ${token}`,
                        'Content-Type': 'application/json'
                    },
                    body: reqBody
                });

                respStatus.textContent = `${res.status} ${res.statusText}`;
                respStatus.className = `text-xs font-bold ${res.ok ? 'text-emerald-600' : 'text-red-600'}`;

                const text = await res.text();
                try {
                    const parsed = JSON.parse(text);
                    respBox.textContent = JSON.stringify(parsed, null, 2);
                } catch (e) {
                    respBox.textContent = text;
                }

                fetchKeys();
                fetchStats();

            } catch (err) {
                respStatus.textContent = 'Network Error';
                respStatus.className = 'text-xs font-bold text-red-600';
                respBox.textContent = err.message;
            }
        });
    }

    if (copyCurlBtn) {
        copyCurlBtn.addEventListener('click', () => {
            const snippet = document.getElementById('apiCurlSnippet');
            if (snippet) copyToClipboard(snippet.textContent, 'cURL command copied!');
        });
    }

    if (refreshAclBtn) {
        refreshAclBtn.addEventListener('click', loadAclUsers);
    }

    updateCurlSnippet();
}

function getSelectedApiToken() {
    const sel = document.getElementById('apiTokenSelect');
    if (!sel) return 'tok_admin_live_secret';
    if (sel.value === 'custom') {
        const inp = document.getElementById('apiCustomTokenInput');
        return inp ? inp.value.trim() : '';
    }
    return sel.value;
}

function updateCurlSnippet() {
    const snippet = document.getElementById('apiCurlSnippet');
    if (!snippet) return;

    const ep = document.getElementById('apiEndpointSelect').value;
    const token = getSelectedApiToken();
    const key = document.getElementById('apiTargetKey').value || 'app:user:101';
    const host = window.location.host || 'localhost:8080';

    if (ep === 'GET_KEY') {
        snippet.textContent = `curl -H "Authorization: Bearer ${token}" http://${host}/v1/get/${key}`;
    } else if (ep === 'SET_KEY') {
        snippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '{"key":"${key}","value":"{\\"status\\":\\"active\\"}"}' http://${host}/v1/set`;
    } else if (ep === 'HGETALL') {
        snippet.textContent = `curl -H "Authorization: Bearer ${token}" http://${host}/v1/hgetall/${key}`;
    } else if (ep === 'DEL_KEY') {
        snippet.textContent = `curl -X DELETE -H "Authorization: Bearer ${token}" http://${host}/v1/del/${key}`;
    } else {
        snippet.textContent = `curl -X POST -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d '{"command":"PING"}' http://${host}/v1/command`;
    }
}

async function loadAclUsers() {
    const container = document.getElementById('aclUserListContainer');
    if (!container) return;

    try {
        const res = await fetch('/api/acl');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const data = await res.json();
        const users = data.users || [];

        container.innerHTML = users.map(u => `
            <div class="p-3 bg-gray-50 rounded-xl border border-gray-100 mb-3 space-y-2">
                <div class="flex items-center justify-between">
                    <div class="flex items-center gap-2">
                        <span class="w-2 h-2 rounded-full ${u.role === 'ADMIN' ? 'bg-purple-600' : u.role === 'READ_WRITE' ? 'bg-blue-600' : 'bg-gray-400'}"></span>
                        <span class="font-semibold text-xs text-gray-900">${escapeHtml(u.username)}</span>
                    </div>
                    <span class="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${u.role === 'ADMIN' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'}">${u.role}</span>
                </div>
                <div class="text-[11px] text-gray-500 font-mono bg-white p-2 rounded border border-gray-200 flex items-center justify-between">
                    <span class="truncate">${u.apiToken}</span>
                    <button class="text-purple-600 hover:text-purple-800 ml-2" onclick="copyToClipboard('${u.apiToken}', 'Token copied!')" title="Copy Token">
                        <iconify-icon icon="lucide:copy" width="13"></iconify-icon>
                    </button>
                </div>
                <div class="text-[11px] text-gray-400">
                    Allowed Namespaces: <strong class="text-gray-600 font-mono">${escapeHtml(u.keyPatterns || '*')}</strong>
                </div>
            </div>
        `).join('');
    } catch (err) {
        container.innerHTML = `<div class="p-4 text-xs text-red-500">Failed to load ACL: ${escapeHtml(err.message)}</div>`;
    }
}

// ==========================================
// Virtual Threads Concurrency Benchmark Engine
// ==========================================
function initBenchmarkEngine() {
    const startBtn = document.getElementById('startBenchmarkBtn');
    if (!startBtn) return;

    startBtn.addEventListener('click', async () => {
        const ops = parseInt(document.getElementById('benchOpsSelect').value, 10);
        const concurrency = parseInt(document.getElementById('benchConcurrencySelect').value, 10);

        startBtn.disabled = true;
        startBtn.innerHTML = `<div class="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin"></div> Running Benchmark...`;

        try {
            const res = await fetch(`/api/benchmark?ops=${ops}&concurrency=${concurrency}${activeVirtualDb !== 'default' ? '&db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                method: 'POST'
            });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();

            document.getElementById('benchOpsPerSec').textContent = (data.opsPerSec || 0).toLocaleString();
            document.getElementById('benchDuration').textContent = `${data.durationMs || 0} ms`;
            document.getElementById('benchCompletedOps').textContent = (data.completedOps || 0).toLocaleString();
            document.getElementById('benchConcurrencyLabel').textContent = `${concurrency} Loom Threads`;

            document.getElementById('benchP50').textContent = `${data.p50 || 0} ms`;
            document.getElementById('benchP90').textContent = `${data.p90 || 0} ms`;
            document.getElementById('benchP99').textContent = `${data.p99 || 0} ms`;
            document.getElementById('benchMin').textContent = `${data.min || 0} ms`;
            document.getElementById('benchMax').textContent = `${data.max || 0} ms`;

            // Also update overview KPI
            const ovOps = document.getElementById('ovOpsPerSec');
            if (ovOps) ovOps.textContent = (data.opsPerSec || 0).toLocaleString();

            const ovLat = document.getElementById('ovAvgLatency');
            if (ovLat) ovLat.textContent = `${data.p50 || 0} ms`;

            showToast(`Stress test completed: ${(data.opsPerSec || 0).toLocaleString()} ops/sec!`);
            fetchStats();
        } catch (err) {
            showToast('Benchmark failed: ' + err.message, true);
        } finally {
            startBtn.disabled = false;
            startBtn.innerHTML = `<iconify-icon icon="lucide:zap" width="16"></iconify-icon> Start Stress Test`;
        }
    });
}

function initTenantsPanel() {
    // Tenants initialization
}

function loadTenants() {
    loadAclUsers();
}

// ==========================================
// Live Telemetry & Stats Fetching
// ==========================================
async function fetchStats() {
    try {
        const res = await fetch(`/api/stats${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`);
        if (!res.ok) return;
        const data = await res.json();

        const memBytes = data.used_memory_bytes || data.usedMemoryBytes || 0;
        const totalCmds = data.total_commands_processed || data.totalCommandsProcessed || 0;
        const uptime = data.uptime_in_seconds || data.uptimeSeconds || 0;
        const totalKeys = data.total_keys || data.totalKeys || 0;
        const clients = data.connected_clients || data.connectedClients || 1;

        if (document.getElementById('sbMemory')) document.getElementById('sbMemory').textContent = formatBytes(memBytes);
        if (document.getElementById('sbCommands')) document.getElementById('sbCommands').textContent = totalCmds.toLocaleString();
        if (document.getElementById('sbUptime')) document.getElementById('sbUptime').textContent = formatUptimeShort(uptime);

        // Overview Memory Card
        const memDisplay = document.getElementById('ovMemoryUsageDisplay');
        if (memDisplay) memDisplay.textContent = formatBytes(memBytes);

        const memBar = document.getElementById('ovMemoryProgressBar');
        if (memBar) {
            const pct = Math.min(100, Math.max(5, (memBytes / (256 * 1024 * 1024)) * 100));
            memBar.style.width = pct.toFixed(1) + '%';
        }

        const clientsEl = document.getElementById('ovConnectedClients');
        if (clientsEl) clientsEl.textContent = `${clients} Client Connections`;

        const ovKeys = document.getElementById('ovTotalKeys');
        if (ovKeys && totalKeys > 0) ovKeys.textContent = totalKeys.toLocaleString();

    } catch (e) {
        // Ignore telemetry fetch errors
    }
}

function initLiveSync() {
    const check = document.getElementById('autoPollCheck');
    const refreshBtn = document.getElementById('manualRefreshBtn');

    if (refreshBtn) {
        refreshBtn.addEventListener('click', () => {
            fetchKeys();
            fetchStats();
            showToast('Refreshed data');
        });
    }

    if (check) {
        check.addEventListener('change', () => {
            if (check.checked) startPolling();
            else stopPolling();
        });
        if (check.checked) startPolling();
    }
}

function startPolling() {
    stopPolling();
    pollTimer = setInterval(() => {
        fetchKeys();
        fetchStats();
    }, 3000);
}

function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
}

// ==========================================
// Export / Import
// ==========================================
function initExportImport() {
    const exportBtn = document.getElementById('exportDataBtn');
    const importBtn = document.getElementById('importDataBtn');
    const fileInput = document.getElementById('importFileInput');

    if (exportBtn) {
        exportBtn.addEventListener('click', async () => {
            try {
                const res = await fetch(`/api/export${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`);
                const data = await res.json();
                const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `redis-${activeVirtualDb}-${Date.now()}.json`;
                a.click();
                URL.revokeObjectURL(url);
                showToast('Database exported as JSON');
            } catch (err) {
                showToast('Export failed: ' + err.message, true);
            }
        });
    }

    if (importBtn && fileInput) {
        importBtn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', async (e) => {
            const file = e.target.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = async (evt) => {
                try {
                    const json = JSON.parse(evt.target.result);
                    const res = await fetch(`/api/import${activeVirtualDb !== 'default' ? '?db=' + encodeURIComponent(activeVirtualDb) : ''}`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(json)
                    });
                    if (res.ok) {
                        showToast('Database records imported successfully');
                        fetchKeys();
                        fetchStats();
                    } else {
                        showToast('Import failed', true);
                    }
                } catch (err) {
                    showToast('Import parsing error: ' + err.message, true);
                }
            };
            reader.readAsText(file);
        });
    }
}

// ==========================================
// Utilities
// ==========================================
function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function formatUptimeShort(sec) {
    if (!sec || sec < 60) return `${sec || 0}s`;
    const m = Math.floor(sec / 60);
    if (m < 60) return `${m}m`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
}

function escapeHtml(str) {
    if (typeof str !== 'string') return String(str || '');
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function showToast(msg, isErr = false) {
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const div = document.createElement('div');
    div.className = `toast-msg ${isErr ? 'error' : ''}`;
    div.innerHTML = `
        <iconify-icon icon="${isErr ? 'lucide:alert-circle' : 'lucide:check-circle-2'}" width="18" class="${isErr ? 'text-red-500' : 'text-purple-600'} shrink-0"></iconify-icon>
        <span class="text-xs font-medium">${escapeHtml(msg)}</span>
    `;
    container.appendChild(div);

    setTimeout(() => {
        div.classList.add('toast-out');
        setTimeout(() => div.remove(), 250);
    }, 3200);
}

function copyToClipboard(text, successMsg = 'Copied to clipboard!') {
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(() => {
            showToast(successMsg);
        }).catch(() => fallbackCopy(text, successMsg));
    } else {
        fallbackCopy(text, successMsg);
    }
}

function fallbackCopy(text, successMsg) {
    const ta = document.createElement('textarea');
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    showToast(successMsg);
}

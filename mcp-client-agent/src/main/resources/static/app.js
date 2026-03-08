// ===== 登录检查 =====
const token = localStorage.getItem('token');
if (!token) {
    window.location.href = '/login.html';
}

// ===== 用户信息 =====
const currentUsername = localStorage.getItem('username') || 'anonymous';
const currentNickname = localStorage.getItem('nickname') || 'anonymous';

// ===== Actions 菜单 =====
function toggleActionsMenu() {
    const menu = document.getElementById('actionsMenu');
    menu.classList.toggle('show');
}

// 点击其他地方关闭菜单
document.addEventListener('click', function(e) {
    const dropdown = document.querySelector('.actions-dropdown');
    const menu = document.getElementById('actionsMenu');
    if (dropdown && !dropdown.contains(e.target)) {
        menu.classList.remove('show');
    }
});

// ===== 登出功能 =====
function logout() {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('nickname');
    window.location.href = '/login.html';
}

// ===== Token 带上的 fetch 包装 =====
async function authFetch(url, options = {}) {
    const token = localStorage.getItem('token');
    const headers = {
        ...options.headers,
        'Authorization': `Bearer ${token}`
    };
    return fetch(url, { ...options, headers });
}

// ===== Theme =====
function initTheme() {
    const saved = localStorage.getItem('theme') || 'dark';
    applyTheme(saved);
}
function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    const icon = document.getElementById('themeIcon');
    if (icon) icon.innerHTML = theme === 'dark' ? '&#9790;' : '&#9728;&#65039;';
}
function toggleTheme() {
    const current = document.documentElement.getAttribute('data-theme') || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    localStorage.setItem('theme', next);
}
initTheme();

// ===== 用户信息 =====
function initUserInfo() {
    document.getElementById('userName').textContent = currentNickname || currentUsername;
}
initUserInfo();

function initSidebarState() {
    const sidebar = document.getElementById('sidebar');
    if (!sidebar) return;

    const applyDesktopState = () => {
        if (window.innerWidth <= 768) {
            sidebar.classList.remove('collapsed');
        } else {
            sidebar.classList.remove('open');
            if (localStorage.getItem('sidebarCollapsed') === '1') {
                sidebar.classList.add('collapsed');
            } else {
                sidebar.classList.remove('collapsed');
            }
        }
    };

    applyDesktopState();
    window.addEventListener('resize', applyDesktopState);
}
initSidebarState();

// ===== State =====
let conversations = []; // { id, title, messages: [] }
let currentConvId = null;
let isLoading = false;
let currentModel = 'MiniMax-M2.5';

// ===== Model Management =====
function initModel() {
    const saved = localStorage.getItem('selectedModel');
    if (saved) {
        currentModel = saved;
        document.getElementById('modelSelect').value = saved;
    }
}

function changeModel() {
    const select = document.getElementById('modelSelect');
    currentModel = select.value;
    localStorage.setItem('selectedModel', currentModel);
    console.log('切换模型:', currentModel);
}

// ===== Load History from Backend =====
async function loadConversations() {
    try {
        const token = localStorage.getItem('token');
        const res = await fetch('/api/agent/conversations', {
            headers: { 'Authorization': `Bearer ${token}` }
        });
        if (!res.ok) {
            console.warn('加载会话列表失败:', res.status);
            newConversation();
            return;
        }
        const conversationIds = await res.json();
        if (!conversationIds || conversationIds.length === 0) {
            newConversation();
            return;
        }

        // 加载每个会话的历史消息
        for (const convId of conversationIds) {
            try {
                const historyRes = await fetch(`/api/agent/conversations/${convId}/history`, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (historyRes.ok) {
                    const history = await historyRes.json();
                    const messages = history.messages || [];
                    // 转换消息格式
                    const formattedMessages = messages.map(m => ({
                        role: m.messageType === 'USER' ? 'user' : 'assistant',
                        content: m.content
                    }));
                    // 用第一条用户消息作为标题
                    const firstUserMsg = formattedMessages.find(m => m.role === 'user');
                    const title = firstUserMsg
                        ? (firstUserMsg.content.length > 30 ? firstUserMsg.content.substring(0, 30) + '...' : firstUserMsg.content)
                        : 'Chat';
                    conversations.push({ id: convId, title, messages: formattedMessages });
                }
            } catch (e) {
                console.warn('加载会话历史失败:', convId, e);
            }
        }

        if (conversations.length === 0) {
            newConversation();
        } else {
            // 选择最新的会话并展示消息
            currentConvId = conversations[conversations.length - 1].id;
            renderConvList();
            replayConversation(currentConvId);
        }
    } catch (e) {
        console.error('加载会话列表异常:', e);
        newConversation();
    }
}

// Init
initModel();
loadConversations();

// ===== Conversation Management =====
function newConversation() {
    const id = crypto.randomUUID();
    conversations.push({ id, title: 'New Chat', messages: [] });
    currentConvId = id;
    renderConvList();
    clearChat();
}

function switchConversation(id) {
    currentConvId = id;
    renderConvList();
    replayConversation(id);
}

function renderConvList() {
    const list = document.getElementById('convList');
    list.innerHTML = conversations.slice().reverse().map(c =>
        `<div class="conv-item ${c.id === currentConvId ? 'active' : ''}"
              onclick="switchConversation('${c.id}')"
              title="${escapeHtml(c.title)}">
            <span class="conv-title" onclick="event.stopPropagation(); switchConversation('${c.id}')">${escapeHtml(c.title)}</span>
            <button class="conv-delete" onclick="event.stopPropagation(); deleteConversation('${c.id}')" title="删除会话">×</button>
        </div>`
    ).join('');
}

function replayConversation(id) {
    const conv = conversations.find(c => c.id === id);
    clearChat();
    if (!conv) return;
    conv.messages.forEach(m => {
        if (m.role === 'user') {
            appendUserMessage(m.content);
        } else {
            appendStaticAssistantMessage(m.content);
        }
    });
}

function clearChat() {
    const container = document.getElementById('chatContainer');
    container.innerHTML = '';
    const conv = conversations.find(c => c.id === currentConvId);
    if (!conv || conv.messages.length === 0) {
        container.innerHTML = `
            <div class="welcome" id="welcome">
                <div class="welcome-icon">AI</div>
                <h2>AI Agent</h2>
                <p>SKILL.md 驱动的智能助手，支持天气查询、订单管理、数据分析等多种技能</p>
            </div>
            <div class="quick-grid" id="quickGrid">
                <div class="quick-card" onclick="sendQuick('上海今天天气怎么样？')">
                    <div class="qc-icon">&#9728;&#65039;</div>
                    <div class="qc-title">查天气</div>
                    <div class="qc-desc">上海今天天气怎么样？</div>
                </div>
                <div class="quick-card" onclick="sendQuick('帮我查一下张三的订单')">
                    <div class="qc-icon">&#128230;</div>
                    <div class="qc-title">查订单</div>
                    <div class="qc-desc">帮我查一下张三的订单</div>
                </div>
                <div class="quick-card" onclick="sendQuick('订单ORD20250201001帮我退款，质量问题')">
                    <div class="qc-icon">&#128176;</div>
                    <div class="qc-title">申请退款</div>
                    <div class="qc-desc">订单退款，质量问题</div>
                </div>
                <div class="quick-card" onclick="sendQuick('查一下ORD20250201002的物流')">
                    <div class="qc-icon">&#128666;</div>
                    <div class="qc-title">查物流</div>
                    <div class="qc-desc">查一下物流进度</div>
                </div>
                <div class="quick-card" onclick="sendQuick('统计一下各类商品的销售额')">
                    <div class="qc-icon">&#128202;</div>
                    <div class="qc-title">数据分析</div>
                    <div class="qc-desc">统计各类商品销售额</div>
                </div>
                <div class="quick-card" onclick="sendQuick('你好，你是谁？')">
                    <div class="qc-icon">&#128172;</div>
                    <div class="qc-title">闲聊</div>
                    <div class="qc-desc">你好，你是谁？</div>
                </div>
            </div>`;
    }
}

// ===== Sidebar toggle (mobile) =====
function toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    if (!sidebar) return;

    if (window.innerWidth <= 768) {
        sidebar.classList.toggle('open');
        return;
    }

    sidebar.classList.toggle('collapsed');
    localStorage.setItem('sidebarCollapsed', sidebar.classList.contains('collapsed') ? '1' : '0');
}

// ===== Input =====
function sendQuick(text) {
    document.getElementById('messageInput').value = text;
    sendMessage();
}

function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 150) + 'px';
}

// ===== Send =====
async function sendMessage() {
    const input = document.getElementById('messageInput');
    const message = input.value.trim();
    if (!message || isLoading) return;

    // Remove welcome
    const welcome = document.getElementById('welcome');
    if (welcome) welcome.remove();
    const quickGrid = document.getElementById('quickGrid');
    if (quickGrid) quickGrid.remove();

    // Update conversation title
    const conv = conversations.find(c => c.id === currentConvId);
    if (conv && conv.messages.length === 0) {
        conv.title = message.length > 30 ? message.substring(0, 30) + '...' : message;
        renderConvList();
    }

    appendUserMessage(message);
    conv.messages.push({ role: 'user', content: message });

    input.value = '';
    input.style.height = 'auto';
    isLoading = true;
    document.getElementById('sendBtn').disabled = true;

    const { thinkingBlock, resultContainer, messageRow } = appendAssistantMessage();
    let finalContent = '';

    try {
        const token = localStorage.getItem('token');
        const res = await fetch('/api/agent/chat/stream', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream',
                'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify({ conversationId: currentConvId, message, model: currentModel })
        });

        if (!res.ok) {
            resultContainer.innerHTML = '<span style="color:#ef4444">请求失败: ' + res.status + '</span>';
            return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            let boundary;
            while ((boundary = buffer.indexOf('\n\n')) !== -1) {
                const block = buffer.substring(0, boundary);
                buffer = buffer.substring(boundary + 2);

                const lines = block.split('\n');
                let raw = '';
                for (const l of lines) {
                    if (l.startsWith('data:')) raw += l.slice(5);
                    else if (raw && l.trim()) raw += l;
                }
                raw = raw.trim();
                if (!raw) continue;

                try {
                    const event = JSON.parse(raw);
                    finalContent = handleEvent(event, thinkingBlock, resultContainer, finalContent);
                } catch(e) {
                    console.warn('SSE parse error:', raw, e);
                }
            }
        }
    } catch (err) {
        resultContainer.innerHTML = `<span style="color:#ef4444">网络错误: ${escapeHtml(err.message)}</span>`;
    } finally {
        // Finalize thinking block
        finishThinking(thinkingBlock);
        isLoading = false;
        document.getElementById('sendBtn').disabled = false;
        input.focus();
        if (conv && finalContent) {
            conv.messages.push({ role: 'assistant', content: finalContent });
        }
        scrollToBottom();
    }
}

// ===== Event Handler =====
function handleEvent(event, thinkingBlock, resultContainer, finalContent) {
    const toggle = thinkingBlock.querySelector('.thinking-toggle');
    const content = thinkingBlock.querySelector('.thinking-content');

    switch (event.type) {
        case 'skill_start':
            // 多意图模式：显示子任务分隔标记
            const skillDiv = document.createElement('div');
            skillDiv.className = 'skill-start-marker';
            skillDiv.innerHTML = `<strong>${escapeHtml(event.message)}</strong>`;
            content.appendChild(skillDiv);
            updateThinkingLabel(toggle, event.message, true);
            toggle.classList.add('open');
            content.classList.add('open');
            break;

        case 'planning':
            updateThinkingLabel(toggle, event.message, true);
            // Auto-open thinking
            toggle.classList.add('open');
            content.classList.add('open');
            break;

        case 'plan':
            updateThinkingLabel(toggle, '执行计划', true);
            setPlanSteps(content, event.steps);
            break;

        case 'action':
            updateStepStatus(content, event.step, event.status, event.message, event.content);
            break;

        case 'observe':
            showObservation(content, event.step, event.message);
            break;

        case 'replan':
            showReplan(content, event.message, event.steps);
            break;

        case 'result':
            finalContent = event.content || '';
            resultContainer.innerHTML = renderMarkdown(finalContent);
            resultContainer.classList.add('result-content');
            // Collapse thinking
            toggle.classList.remove('open');
            content.classList.remove('open');
            break;

        case 'error':
            resultContainer.innerHTML = `<span style="color:#ef4444">${escapeHtml(event.message)}</span>`;
            break;

        case 'done':
            finishThinking(thinkingBlock);
            break;
    }

    scrollToBottom();
    return finalContent;
}

// ===== Thinking Block =====
function updateThinkingLabel(toggle, text, spinning) {
    const spinnerHtml = spinning ? '<span class="spinner-sm"></span>' : '';
    toggle.innerHTML = `<span class="arrow">&#9654;</span> ${spinnerHtml} ${escapeHtml(text)}`;
    if (toggle.classList.contains('open')) {
        toggle.querySelector('.arrow').style.transform = 'rotate(90deg)';
    }
}

function finishThinking(thinkingBlock) {
    const toggle = thinkingBlock.querySelector('.thinking-toggle');
    const content = thinkingBlock.querySelector('.thinking-content');
    if (!toggle) return;
    // Remove spinner, keep label
    const spinners = toggle.querySelectorAll('.spinner-sm');
    spinners.forEach(s => s.remove());
    // If no plan steps, hide thinking block entirely
    if (!content.querySelector('.plan-steps')) {
        thinkingBlock.style.display = 'none';
    }
}

function setPlanSteps(contentEl, steps) {
    let stepsEl = contentEl.querySelector('.plan-steps');
    if (!stepsEl) {
        stepsEl = document.createElement('ul');
        stepsEl.className = 'plan-steps';
        contentEl.appendChild(stepsEl);
    }
    stepsEl.innerHTML = steps.map((s, i) =>
        `<li class="pending" data-step="${i + 1}">
            <div class="step-header">
                <span class="step-icon">&#9675;</span>
                <span>${escapeHtml(s)}</span>
            </div>
            <div class="step-result result-content"></div>
        </li>`
    ).join('');
}

function updateStepStatus(contentEl, stepNum, status, message, resultContent) {
    const li = contentEl.querySelector(`li[data-step="${stepNum}"]`);
    if (!li) return;

    if (status === 'running') {
        li.className = 'running';
        li.querySelector('.step-icon').innerHTML = '<span class="spinner-sm"></span>';
    } else if (status === 'done') {
        li.className = 'done';
        li.querySelector('.step-icon').innerHTML = '&#10003;';
        if (resultContent) {
            const resultEl = li.querySelector('.step-result');
            if (resultEl) {
                const text = resultContent.length > 300 ? resultContent.substring(0, 300) + '...' : resultContent;
                resultEl.innerHTML = renderMarkdown(text);
                resultEl.classList.add('show');
            }
        }
    }
}

function showObservation(contentEl, stepNum, observation) {
    const li = contentEl.querySelector(`li[data-step="${stepNum}"]`);
    if (!li) return;
    const old = li.querySelector('.observe-tag');
    if (old) old.remove();
    const tag = document.createElement('div');
    const isOk = observation && observation.toUpperCase().startsWith('OK');
    tag.className = `observe-tag ${isOk ? 'ok' : 'replan'}`;
    tag.textContent = observation.length > 80 ? observation.substring(0, 80) + '...' : observation;
    li.appendChild(tag);
}

function showReplan(contentEl, reason, newSteps) {
    const banner = document.createElement('div');
    banner.className = 'replan-banner';
    banner.innerHTML = `<strong>&#128260; 重新规划</strong>${escapeHtml(reason.length > 80 ? reason.substring(0, 80) + '...' : reason)}`;
    contentEl.appendChild(banner);

    const oldSteps = contentEl.querySelector('.plan-steps');
    if (oldSteps) oldSteps.remove();

    const stepsEl = document.createElement('ul');
    stepsEl.className = 'plan-steps';
    stepsEl.innerHTML = newSteps.map((s, i) =>
        `<li class="pending" data-step="${i + 1}">
            <div class="step-header">
                <span class="step-icon">&#9675;</span>
                <span>${escapeHtml(s)}</span>
            </div>
            <div class="step-result result-content"></div>
        </li>`
    ).join('');
    contentEl.appendChild(stepsEl);
}

// ===== DOM Helpers =====
function appendUserMessage(content) {
    const container = document.getElementById('chatContainer');
    const row = document.createElement('div');
    row.className = 'msg-row user-row';
    row.innerHTML = `<div class="msg-bubble">${escapeHtml(content)}</div>`;
    container.appendChild(row);
    scrollToBottom();
}

function appendAssistantMessage() {
    const container = document.getElementById('chatContainer');
    const row = document.createElement('div');
    row.className = 'msg-row assistant-row';

    row.innerHTML = `
        <div class="assistant-avatar">
            <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
        </div>
        <div class="assistant-body">
            <div class="thinking-block">
                <div class="thinking-toggle" onclick="this.classList.toggle('open');this.nextElementSibling.classList.toggle('open')">
                    <span class="arrow">&#9654;</span>
                    <span class="spinner-sm"></span>
                    思考中...
                </div>
                <div class="thinking-content"></div>
            </div>
            <div class="assistant-result"></div>
        </div>
    `;

    container.appendChild(row);
    scrollToBottom();

    return {
        thinkingBlock: row.querySelector('.thinking-block'),
        resultContainer: row.querySelector('.assistant-result'),
        messageRow: row
    };
}

function appendStaticAssistantMessage(content) {
    const container = document.getElementById('chatContainer');
    const row = document.createElement('div');
    row.className = 'msg-row assistant-row';
    row.innerHTML = `
        <div class="assistant-avatar">
            <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg>
        </div>
        <div class="assistant-body">
            <div class="result-content">${renderMarkdown(content)}</div>
        </div>
    `;
    container.appendChild(row);
}

function scrollToBottom() {
    const scroll = document.getElementById('chatScroll');
    requestAnimationFrame(() => { scroll.scrollTop = scroll.scrollHeight; });
}

function renderMarkdown(text) {
    if (!text) return '';
    try {
        if (typeof marked !== 'undefined') {
            return marked.parse ? marked.parse(text) : marked(text);
        }
    } catch(e) { console.warn('marked error:', e); }
    return escapeHtml(text).replace(/\n/g, '<br>');
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ===== 删除会话 =====
let pendingDeleteConvId = null;
function showDeleteModal(convId) {
    pendingDeleteConvId = convId;
    document.getElementById('deleteModal').style.display = 'flex';
}
function hideDeleteModal() {
    pendingDeleteConvId = null;
    document.getElementById('deleteModal').style.display = 'none';
}
function confirmDelete() {
    if (pendingDeleteConvId) {
        doDeleteConversation(pendingDeleteConvId);
    }
    hideDeleteModal();
}

async function doDeleteConversation(convId) {
    try {
        const res = await fetch(`/api/agent/conversations/${convId}`, {
            method: 'DELETE'
        });
        if (res.ok) {
            conversations = conversations.filter(c => c.id !== convId);
            if (currentConvId === convId) {
                currentConvId = conversations.length > 0 ? conversations[conversations.length - 1].id : null;
                if (currentConvId) {
                    switchConversation(currentConvId);
                } else {
                    clearChat();
                }
            }
            renderConvList();
        }
    } catch (e) {}
}

async function deleteConversation(convId) {
    showDeleteModal(convId);
}

// ===== Skills 模态框 =====
let skillsList = [];

function openSkillsModal() {
    document.getElementById('skillsModal').style.display = 'flex';
    loadSkills();
    // 关闭 actions 菜单
    document.getElementById('actionsMenu').classList.remove('show');
}

function closeSkillsModal() {
    document.getElementById('skillsModal').style.display = 'none';
}

function openVectorTestModal() {
    document.getElementById('vectorSearchInput').value = '';
    document.getElementById('vectorTestResults').classList.add('hidden');
    document.getElementById('vectorTestModal').style.display = 'flex';
}

function closeVectorTestModal() {
    document.getElementById('vectorTestModal').style.display = 'none';
}

async function testVectorMatch() {
    const query = document.getElementById('vectorSearchInput').value.trim();
    if (!query) return;
    try {
        const res = await fetch('/api/skills/test-match', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query, topK: 3 })
        });
        const data = await res.json();
        const resultsDiv = document.getElementById('vectorTestResults');
        resultsDiv.classList.remove('hidden');

        if (data.results && data.results.length > 0) {
            const sorted = data.results.sort((a, b) => b.score - a.score);
            resultsDiv.innerHTML = `
                <div class="test-results-header">
                    <span>向量匹配结果</span>
                    <span style="color:var(--text-muted);font-weight:400;">${query}</span>
                </div>
                <div class="skills-grid" style="margin-bottom:0;">
                    ${sorted.map(r => {
                        const skill = skillsList.find(s => s.name === r.name) || {};
                        return `
                            <div class="skill-card">
                                <div class="skill-card-header">
                                    <div class="skill-card-name">${escapeHtml(r.name)}</div>
                                    <div class="skill-card-status">
                                        <span class="skill-badge" style="background:rgba(16,163,127,0.15);color:#10a37f;">${r.score.toFixed(4)}</span>
                                        ${skill.vector ? '<span class="skill-badge vector">向量</span>' : ''}
                                        <span class="skill-badge ${skill.enabled ? 'enabled' : 'disabled'}">${skill.enabled ? '启用' : '禁用'}</span>
                                    </div>
                                </div>
                                <div class="skill-card-desc">${escapeHtml(skill.description || '暂无描述')}</div>
                                ${skill.allowedTools ? `
                                    <div class="skill-card-tools">
                                        ${skill.allowedTools.split(/[,\s]+/).filter(t=>t).map(t => `<span class="skill-tool-tag">${escapeHtml(t)}</span>`).join('')}
                                    </div>
                                ` : ''}
                            </div>
                        `;
                    }).join('')}
                </div>
            `;
        } else {
            resultsDiv.innerHTML = '<div style="color:var(--text-muted);font-size:14px;padding:12px;">无匹配结果</div>';
        }
    } catch (e) {
        alert('测试失败: ' + e.message);
    }
}

async function loadSkills() {
    try {
        const res = await fetch('/api/skills');
        skillsList = await res.json();
        renderSkills();
    } catch (e) {
        console.error('加载 skills 失败:', e);
    }
}

// 模糊搜索 - 本地过滤
function filterSkills() {
    const query = document.getElementById('skillSearchInput').value.trim().toLowerCase();
    if (!query) {
        renderSkills();
        return;
    }
    const filtered = skillsList.filter(skill => {
        const name = (skill.name || '').toLowerCase();
        const desc = (skill.description || '').toLowerCase();
        return name.includes(query) || desc.includes(query);
    });
    renderSkills(filtered);
}

function renderSkills(filteredList = null) {
    const grid = document.getElementById('skillsGrid');
    const list = filteredList || skillsList;
    if (list.length === 0) {
        grid.innerHTML = '<div style="text-align:center;color:var(--text-muted);padding:20px;">暂无 Skills</div>';
        return;
    }
    grid.innerHTML = list.map(skill => `
        <div class="skill-card">
            <div class="skill-card-header">
                <div class="skill-card-name">${escapeHtml(skill.name)}</div>
                <div class="skill-card-status">
                    ${skill.vector ? '<span class="skill-badge vector">向量</span>' : ''}
                    <span class="skill-badge ${skill.enabled ? 'enabled' : 'disabled'}">${skill.enabled ? '启用' : '禁用'}</span>
                </div>
            </div>
            <div class="skill-card-desc">${escapeHtml(skill.description || '暂无描述')}</div>
            ${skill.allowedTools ? `
                <div class="skill-card-tools">
                    ${skill.allowedTools.split(/[,\s]+/).filter(t=>t).map(t => `<span class="skill-tool-tag">${escapeHtml(t)}</span>`).join('')}
                </div>
            ` : ''}
            <div class="skill-card-actions">
                <button class="btn btn-secondary btn-sm" onclick="openSkillEditModal(${skill.id})">编辑</button>
                <button class="btn btn-secondary btn-sm" onclick="toggleSkillEnabled(${skill.id})">${skill.enabled ? '禁用' : '启用'}</button>
                <button class="btn btn-danger btn-sm" onclick="deleteSkill(${skill.id})">删除</button>
            </div>
        </div>
    `).join('');
}

function openSkillCreateModal() {
    document.getElementById('skillEditTitle').textContent = '新建 Skill';
    document.getElementById('skillEditForm').reset();
    document.getElementById('skillEditId').value = '';
    document.getElementById('skillEditModal').style.display = 'flex';
}

function openSkillEditModal(id) {
    const skill = skillsList.find(s => s.id === id);
    if (!skill) return;
    document.getElementById('skillEditTitle').textContent = '编辑 Skill';
    document.getElementById('skillEditId').value = skill.id;
    document.getElementById('skillName').value = skill.name;
    document.getElementById('skillDescription').value = skill.description || '';
    document.getElementById('skillTools').value = skill.allowedTools || '';
    document.getElementById('skillPrompt').value = skill.promptBody || '';
    document.getElementById('skillPriority').value = skill.priority || 0;
    document.getElementById('skillEnabled').value = skill.enabled ? 'true' : 'false';
    document.getElementById('skillEditModal').style.display = 'flex';
}

function closeSkillEditModal() {
    document.getElementById('skillEditModal').style.display = 'none';
}

document.getElementById('skillEditForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = document.getElementById('skillEditId').value;
    const data = {
        name: document.getElementById('skillName').value,
        description: document.getElementById('skillDescription').value,
        allowedTools: document.getElementById('skillTools').value,
        promptBody: document.getElementById('skillPrompt').value,
        priority: parseInt(document.getElementById('skillPriority').value) || 0,
        enabled: document.getElementById('skillEnabled').value === 'true'
    };
    try {
        const url = id ? `/api/skills/${id}` : '/api/skills';
        const method = id ? 'PUT' : 'POST';
        const res = await fetch(url, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        if (res.ok) {
            closeSkillEditModal();
            loadSkills();
        } else {
            alert('保存失败');
        }
    } catch (e) {
        alert('保存失败: ' + e.message);
    }
});

async function toggleSkillEnabled(id) {
    try {
        const res = await fetch(`/api/skills/${id}/toggle`, { method: 'POST' });
        if (res.ok) loadSkills();
    } catch (e) {
        alert('操作失败');
    }
}

async function deleteSkill(id) {
    if (!confirm('确定要删除这个 Skill 吗？')) return;
    try {
        const res = await fetch(`/api/skills/${id}`, { method: 'DELETE' });
        if (res.ok) loadSkills();
    } catch (e) {
        alert('删除失败');
    }
}

function showConfirmModal(message, onConfirm) {
    document.getElementById('confirmModalMessage').textContent = message;
    document.getElementById('confirmModalBtn').onclick = function() {
        closeConfirmModal();
        onConfirm();
    };
    document.getElementById('confirmModal').style.display = 'flex';
}

function closeConfirmModal() {
    document.getElementById('confirmModal').style.display = 'none';
}

function showToast(message, type = 'success') {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.className = 'toast ' + type;
    toast.style.display = 'block';
    setTimeout(() => {
        toast.style.display = 'none';
    }, 3000);
}

async function regenerateVectors() {
    showConfirmModal('确定要重新生成所有向量吗？', async () => {
        try {
            const res = await fetch('/api/skills/regenerate-vectors', { method: 'POST' });
            const data = await res.json();
            showToast(data.message || '生成成功', 'success');
            loadSkills();
        } catch (e) {
            showToast('生成失败: ' + e.message, 'error');
        }
    });
}

// 点击遮罩关闭
document.getElementById('skillsModal').addEventListener('click', function(e) {
    if (e.target === this) closeSkillsModal();
});
document.getElementById('skillEditModal').addEventListener('click', function(e) {
    if (e.target === this) closeSkillEditModal();
});
document.getElementById('vectorTestModal').addEventListener('click', function(e) {
    if (e.target === this) closeVectorTestModal();
});

// ===== 设置模态框 =====
function openSettingsModal() {
    // 实时从 localStorage 读取最新值
    const theme = localStorage.getItem('theme') || 'dark';
    const username = localStorage.getItem('username') || 'anonymous';
    document.getElementById('themeDisplayValue').textContent = theme === 'dark' ? '深色模式' : '浅色模式';
    document.getElementById('settingsUsername').textContent = username;
    document.getElementById('settingsModal').style.display = 'flex';
}

function closeSettingsModal() {
    document.getElementById('settingsModal').style.display = 'none';
}

// 主题设置
function openThemeModal() {
    const theme = localStorage.getItem('theme') || 'dark';
    document.getElementById('themeSelect').value = theme;
    document.getElementById('themeSubModal').style.display = 'flex';
}

function closeThemeModal() {
    document.getElementById('themeSubModal').style.display = 'none';
}

function saveTheme() {
    const theme = document.getElementById('themeSelect').value;
    applyTheme(theme);
    localStorage.setItem('theme', theme);
    document.getElementById('themeDisplayValue').textContent = theme === 'dark' ? '深色模式' : '浅色模式';
    closeThemeModal();
}

// 密码修改
function openPasswordModal() {
    document.getElementById('oldPassword').value = '';
    document.getElementById('newPassword').value = '';
    document.getElementById('confirmPassword').value = '';
    document.getElementById('passwordError').style.display = 'none';
    document.getElementById('passwordSubModal').style.display = 'flex';
}

function closePasswordModal() {
    document.getElementById('passwordSubModal').style.display = 'none';
}

async function savePassword() {
    const oldPwd = document.getElementById('oldPassword').value;
    const newPwd = document.getElementById('newPassword').value;
    const confirmPwd = document.getElementById('confirmPassword').value;
    const errorEl = document.getElementById('passwordError');

    if (!oldPwd || !newPwd) {
        errorEl.textContent = '请填写所有密码字段';
        errorEl.style.display = 'block';
        return;
    }
    if (newPwd.length < 6) {
        errorEl.textContent = '新密码至少6位';
        errorEl.style.display = 'block';
        return;
    }
    if (newPwd !== confirmPwd) {
        errorEl.textContent = '两次输入的密码不一致';
        errorEl.style.display = 'block';
        return;
    }

    try {
        const token = localStorage.getItem('token');
        const username = localStorage.getItem('username');
        const res = await fetch('/api/auth/password', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
                'X-Username': username
            },
            body: JSON.stringify({ oldPassword: oldPwd, newPassword: newPwd })
        });

        if (!res.ok) {
            const data = await res.json();
            throw new Error(data.message || '修改失败');
        }

        alert('密码修改成功，请重新登录');
        localStorage.clear();
        window.location.href = '/login.html';
    } catch (err) {
        errorEl.textContent = err.message;
        errorEl.style.display = 'block';
    }
}

// 账户信息
function openProfileModal() {
    // 实时从 localStorage 读取最新值
    const username = localStorage.getItem('username') || 'anonymous';
    const nickname = localStorage.getItem('nickname') || username;
    document.getElementById('profileUsername').value = username;
    document.getElementById('profileNickname').value = nickname;
    document.getElementById('profileSubModal').style.display = 'flex';
}

function closeProfileModal() {
    document.getElementById('profileSubModal').style.display = 'none';
}

function saveProfile() {
    const nickname = document.getElementById('profileNickname').value;
    const username = localStorage.getItem('username') || 'anonymous';
    localStorage.setItem('nickname', nickname);
    document.getElementById('userName').textContent = nickname || username;
    closeProfileModal();
}

// 点击遮罩关闭
document.getElementById('settingsModal').addEventListener('click', function(e) {
    if (e.target === this) closeSettingsModal();
});
document.getElementById('themeSubModal').addEventListener('click', function(e) {
    if (e.target === this) closeThemeModal();
});
document.getElementById('passwordSubModal').addEventListener('click', function(e) {
    if (e.target === this) closePasswordModal();
});
document.getElementById('profileSubModal').addEventListener('click', function(e) {
    if (e.target === this) closeProfileModal();
});
document.getElementById('confirmModal').addEventListener('click', function(e) {
    if (e.target === this) closeConfirmModal();
});
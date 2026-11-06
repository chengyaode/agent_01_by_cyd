<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { marked } from 'marked'
import {
  fetchHealth, fetchAgents, chatStream, chatSync, deleteSession,
  uploadContext, listContext, deleteContext
} from './api.js'

marked.setOptions({ breaks: true })

// ---------- 状态 ----------
const agents = ref([])
const currentAgent = ref('')
const health = ref('unknown')
const error = ref('')
const streamMode = ref(true)
const dropdownOpen = ref(false)

// 参考文档状态
const ctxFiles = ref([])
const isUploading = ref(false)
const ctxError = ref('')

// 会话存储: { [agentName]: { active, convs: [{id,title,sessionId,messages,updatedAt}] } }
const STORE_KEY = 'agent-conv-store-v2'
const store = reactive(loadStore())

// 当前 agent 的会话组 & 激活会话
const group = computed(() => store[currentAgent.value])
const activeConv = computed(() => group.value?.convs.find(c => c.id === group.value.active))

const input = ref('')
const listRef = ref(null)
const ctxInput = ref(null)
let aborter = null

function loadStore() {
  try { return JSON.parse(localStorage.getItem(STORE_KEY) || '{}') }
  catch (e) { return {} }
}
function saveStore() { localStorage.setItem(STORE_KEY, JSON.stringify(store)) }

function ensureGroup(name) {
  if (!store[name]) store[name] = { active: null, convs: [] }
  return store[name]
}

function newConv(name = currentAgent.value) {
  const g = ensureGroup(name)
  const conv = {
    id: 'c-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    title: '新对话',
    sessionId: '',
    messages: [],
    updatedAt: Date.now()
  }
  g.convs.unshift(conv)
  g.active = conv.id
  saveStore()
  return conv
}

function selectConv(convId) {
  const g = group.value
  if (!g || g.active === convId) return
  g.active = convId
  saveStore()
  scrollBottom()
}

function deleteConv(convId) {
  const g = group.value
  if (!g) return
  const idx = g.convs.findIndex(c => c.id === convId)
  if (idx < 0) return
  const conv = g.convs[idx]
  // 删服务端记忆
  if (conv.sessionId) deleteSession(currentAgent.value, conv.sessionId).catch(() => {})
  g.convs.splice(idx, 1)
  if (g.active === convId) g.active = g.convs[0]?.id || null
  saveStore()
}

function renameConv(conv) {
  const t = prompt('会话标题', conv.title)
  if (t && t.trim()) { conv.title = t.trim(); saveStore() }
}

function switchAgent(name) {
  if (name === currentAgent.value) return
  // 先确保这个 agent 至少有一个会话
  const g = ensureGroup(name)
  if (!g.convs.length) newConv(name)
  else if (!g.active) g.active = g.convs[0].id
  currentAgent.value = name
  dropdownOpen.value = false
  refreshCtx()
  scrollBottom()
}

// ---------- 参考文档 ----------
async function refreshCtx() {
  ctxError.value = ''
  if (!currentAgent.value) { ctxFiles.value = []; return }
  try {
    const r = await listContext(currentAgent.value)
    ctxFiles.value = r.files || []
  } catch (e) {
    ctxError.value = '加载参考文档失败: ' + e.message
  }
}

async function uploadCtx(event) {
  const files = event.target.files
  if (!files || !files.length) return
  isUploading.value = true
  ctxError.value = ''
  try {
    const r = await uploadContext(currentAgent.value, Array.from(files))
    const total = r.uploaded + r.failed
    if (r.failed === total) {
      ctxError.value = r.files.map(f => `${f.originalName}: ${f.reason || '失败'}`).join('；')
    }
    await refreshCtx()
  } catch (e) {
    ctxError.value = '上传失败: ' + e.message
  } finally {
    isUploading.value = false
    // 清空 input 让下次选同一文件能触发 change
    event.target.value = ''
  }
}

async function removeCtx(filename) {
  try {
    await deleteContext(currentAgent.value, filename)
    await refreshCtx()
  } catch (e) {
    ctxError.value = '删除失败: ' + e.message
  }
}

function humanSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

// 会话标题：用第一条 user 消息的前 20 字（去换行）
function inferTitle(conv) {
  const firstUser = conv.messages.find(m => m.role === 'user')
  if (!firstUser) return conv.title
  const t = firstUser.text.replace(/[\n\r]/g, ' ').slice(0, 20)
  return t + (firstUser.text.length > 20 ? '…' : '')
}

// ---------- 加载 agent 列表 ----------
async function boot() {
  // health
  try { await fetchHealth(); health.value = 'ok' } catch (e) { health.value = 'fail' }

  // agents
  try {
    const r = await fetchAgents()
    agents.value = r.agents || r || []
    if (agents.value.length) {
      switchAgent(agents.value[0].name)
    }
  } catch (e) {
    error.value = '连接失败：' + e.message + '（Key 已写死在 api.js，改完请 npm run dev）'
  }
}
onMounted(boot)

// 点击外部关闭下拉
function onDropdownClick(e) {
  if (!e.target.closest('.dropdown')) dropdownOpen.value = false
}

// ---------- 工具调用展示 ----------
const TOOL_LABELS = {
  query_weather: '天气查询',
  query_express: '快递查询'
}
function toolLabel(name) { return TOOL_LABELS[name] || name }

// 同名工具多次调用合并展示，带次数
function toolSummary(tools) {
  const out = []
  for (const t of tools || []) {
    const hit = out.find(x => x.name === t)
    if (hit) hit.count++
    else out.push({ name: t, label: toolLabel(t), count: 1 })
  }
  return out
}

// ---------- 发送消息 ----------
const typing = ref(false)

async function send() {
  const text = input.value.trim()
  const conv = activeConv.value
  if (!text || !conv || typing.value) return
  input.value = ''
  conv.messages.push({ role: 'user', text })
  conv.messages.push({ role: 'assistant', text: '', tools: [] })
  conv.title = inferTitle(conv)
  conv.updatedAt = Date.now()
  typing.value = true
  const holder = conv.messages[conv.messages.length - 1]
  scrollBottom()

  try {
    if (!conv.sessionId) {
      conv.sessionId = 'web-' + Math.random().toString(36).slice(2, 14)
    }

    if (streamMode.value) {
      aborter = new AbortController()
      await chatStream(currentAgent.value, text, conv.sessionId, (ev, data) => {
        if (ev === 'start') conv.sessionId = data.sessionId
        else if (ev === 'delta') { holder.text += data.text; scrollBottom() }
        else if (ev === 'tool') {
          holder.tools = holder.tools || []
          holder.tools.push(data.tool || 'tool')
          scrollBottom()
        }
        else if (ev === 'error') holder.text += '\n\n[错误] ' + data.message
      }, aborter.signal)
    } else {
      const r = await chatSync(currentAgent.value, text, conv.sessionId)
      holder.text = r.reply
      conv.sessionId = r.sessionId
    }
  } catch (e) {
    if (e.name === 'AbortError') holder.text += '\n\n*（已停止）*'
    else holder.text = '[请求失败] ' + e.message
  } finally {
    typing.value = false
    aborter = null
    saveStore()
    scrollBottom()
  }
}

function stop() { if (aborter) aborter.abort() }

// ---------- 渲染 ----------
function renderMarkdown(text) {
  try { return marked.parse(text || '') } catch (e) { return text }
}
function scrollBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const same = d.toDateString() === now.toDateString()
  const pad = (n) => n.toString().padStart(2, '0')
  return same ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : `${pad(d.getMonth()+1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<template>
  <div class="app" @click="onDropdownClick">
    <!-- 侧栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="logo">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"/>
          </svg>
        </div>
        <span>Agent Studio</span>
        <span class="health-dot" :class="health" :title="health === 'ok' ? '后端在线' : '后端离线'"></span>
      </div>

      <!-- 顶部 agent 下拉 -->
      <div v-if="agents.length" class="agent-picker dropdown">
        <button class="picker-btn" @click.stop="dropdownOpen = !dropdownOpen">
          <div class="picker-info">
            <span class="picker-name">{{ currentAgent }}</span>
            <span class="picker-model" v-if="agents.find(a => a.name === currentAgent)?.model">
              {{ agents.find(a => a.name === currentAgent).model }}
            </span>
          </div>
          <svg viewBox="0 0 24 24" width="14" height="14" :class="{ open: dropdownOpen }" class="chev"><path fill="currentColor" d="M7 10l5 5 5-5z"/></svg>
        </button>
        <transition name="fade">
          <div v-if="dropdownOpen" class="picker-menu">
            <button v-for="a in agents" :key="a.name" class="picker-item"
                    :class="{ active: a.name === currentAgent }"
                    @click="switchAgent(a.name)">
              <div class="pi-name">{{ a.name }}</div>
              <div class="pi-sub">{{ a.model }}</div>
            </button>
          </div>
        </transition>
      </div>

      <!-- 会话列表 -->
      <div class="conv-header">
        <span>会话</span>
        <button class="new-btn" @click="newConv()" :disabled="!currentAgent">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"/></svg>
          新对话
        </button>
      </div>

      <div class="conv-list">
        <div v-if="!group?.convs?.length" class="empty-hint">暂无会话，点右上"新对话"开始</div>
        <div v-for="c in group?.convs" :key="c.id" class="conv-item"
             :class="{ active: c.id === group?.active }"
             @click="selectConv(c.id)">
          <div class="ci-title">{{ c.title || '新对话' }}</div>
          <div class="ci-meta">
            <span>{{ formatTime(c.updatedAt) }}</span>
            <span>{{ c.messages.length }} 条</span>
            <div class="ci-actions">
              <button title="重命名" @click.stop="renameConv(c)">✎</button>
              <button title="删除（含服务端记忆）" @click.stop="deleteConv(c.id)">✕</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="error" class="sidebar-err">{{ error }}</div>
      <div class="sidebar-foot" v-if="activeConv">
        sessionId: {{ activeConv.sessionId || '—' }}
      </div>
    </aside>

    <!-- 主聊天区 -->
    <main class="main">
      <header class="topbar">
        <div class="top-left">
          <span class="agent-tag">{{ currentAgent }}</span>
          <span class="model-tag" v-if="agents.find(a => a.name === currentAgent)">
            {{ agents.find(a => a.name === currentAgent).model }}
          </span>
        </div>
        <div class="top-right">
          <label class="toggle">
            <input type="checkbox" v-model="streamMode" />
            <span class="t-slider"></span>
            <span>流式输出</span>
          </label>
        </div>
      </header>

      <div class="chat-area" ref="listRef">
        <!-- 欢迎 -->
        <div v-if="activeConv && !activeConv.messages.length" class="welcome">
          <div class="w-title">👋 你好，我是 <b>{{ currentAgent }}</b></div>
          <div class="w-suggestions">
            <button class="chip" @click="input='用一句话介绍你自己'">用一句话介绍你自己</button>
            <button class="chip" @click="input='北京今天天气怎么样？'">北京今天天气怎么样</button>
            <button class="chip" @click="input='帮我查下快递单号 78123456789012 到哪了？'">查快递物流</button>
            <button class="chip" @click="input='看看工作区有什么文件'">看看工作区有什么文件</button>
            <button class="chip" @click="input='帮我计算 23 * 47'">帮我计算 23 × 47</button>
          </div>
        </div>

        <template v-if="activeConv">
          <div v-for="(m, i) in activeConv.messages" :key="i"
               class="bubble-wrap" :class="m.role">
            <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
            <div class="bubble" :class="m.role">
              <div v-if="m.role === 'assistant' && m.tools && m.tools.length" class="tool-row">
                <span v-for="t in toolSummary(m.tools)" :key="t.name" class="tool-chip" :title="t.name">
                  <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a4 4 0 0 0 5 5l-8.4 8.4a2.8 2.8 0 0 1-4-4z"/></svg>
                  {{ t.label }}<em v-if="t.count > 1">×{{ t.count }}</em>
                </span>
              </div>
              <div v-if="m.role === 'assistant'" class="md" v-html="renderMarkdown(m.text)"></div>
              <div v-else class="plain">{{ m.text }}</div>
              <div v-if="typing && m.role === 'assistant' && i === activeConv.messages.length - 1" class="cursor">▍</div>
            </div>
          </div>
        </template>
      </div>

      <footer class="composer">
        <!-- 参考文档附件条 -->
        <div v-if="ctxError || ctxFiles.length" class="ctx-bar">
          <div v-if="ctxError" class="ctx-err">{{ ctxError }}</div>
          <div v-if="ctxFiles.length" class="ctx-chips">
            <span v-for="f in ctxFiles" :key="f.name" class="ctx-chip" :title="f.name">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>
              <span class="cc-name">{{ f.name }}</span>
              <em class="cc-size">{{ humanSize(f.size) }}</em>
              <button class="cc-del" title="删除" @click.stop="removeCtx(f.name)">✕</button>
            </span>
          </div>
        </div>

        <textarea v-model="input" rows="2"
                  placeholder="输入消息，Enter 发送，Shift+Enter 换行"
                  :disabled="!activeConv"
                  @keydown="onKeydown"></textarea>
        <div class="c-actions">
          <label class="attach-btn" :class="{ busy: isUploading }" for="ctxInput">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
            {{ isUploading ? '上传中…' : '上传文档' }}
          </label>
          <input ref="ctxInput" id="ctxInput" type="file" multiple hidden
                 @change="uploadCtx" accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.html,.rtf,.xml,.json"/>
          <button v-if="typing && streamMode" class="btn-stop" @click="stop">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
            停止
          </button>
          <button class="btn-send" :disabled="typing || !input.trim() || !activeConv" @click="send">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M2 21l21-9L2 3v7l15 2-15 2z"/></svg>
            发送
          </button>
        </div>
      </footer>
    </main>
  </div>
</template>

<style>
/* ---------- 深色主题 ---------- */
:root {
  --bg-0: #0b0f1a;
  --bg-1: #111827;
  --bg-2: #1e293b;
  --bg-3: #334155;
  --border: #1f2a44;
  --text-1: #e5e7eb;
  --text-2: #94a3b8;
  --text-3: #64748b;
  --accent: #6366f1;
  --accent-2: #8b5cf6;
  --accent-soft: rgba(99,102,241,.15);
  --user-bubble: linear-gradient(135deg, #4f46e5, #7c3aed);
  --assistant-bubble: #1e293b;
  --danger: #ef4444;
  --ok: #10b981;
}

* { box-sizing: border-box; margin: 0; padding: 0; }
html, body, #app { height: 100%; width: 100%; }
body {
  font: 14px/1.7 -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
  background: var(--bg-0);
  color: var(--text-1);
  overflow: hidden;
}

/* ---------- 整体 ---------- */
.app { display: flex; height: 100vh; }

/* ---------- 侧栏 ---------- */
.sidebar {
  width: 272px;
  flex-shrink: 0;
  background: linear-gradient(180deg, var(--bg-1), var(--bg-0));
  border-right: 1px solid var(--border);
  display: flex; flex-direction: column;
  padding: 18px 12px 12px;
  gap: 14px;
}
.brand { display: flex; align-items: center; gap: 8px; font-size: 16px; font-weight: 700; color: var(--text-1); padding: 0 4px; }
.logo { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px;
  background: linear-gradient(135deg, var(--accent), var(--accent-2)); }
.health-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-3); margin-left: auto; }
.health-dot.ok { background: var(--ok); box-shadow: 0 0 8px var(--ok); }
.health-dot.fail { background: var(--danger); box-shadow: 0 0 8px var(--danger); }

/* agent picker */
.agent-picker { position: relative; }
.picker-btn {
  width: 100%; text-align: left;
  background: var(--bg-2); border: 1px solid var(--border);
  border-radius: 10px; padding: 10px 12px; color: var(--text-1);
  display: flex; align-items: center; gap: 10px; cursor: pointer;
  transition: border-color .15s;
}
.picker-btn:hover { border-color: var(--accent); }
.picker-info { flex: 1; min-width: 0; }
.picker-name { display: block; font-weight: 600; }
.picker-model { display: block; font-size: 11px; color: var(--text-2); margin-top: 2px; }
.chev { transition: transform .2s; color: var(--text-2); }
.chev.open { transform: rotate(180deg); }
.picker-menu {
  position: absolute; top: calc(100% + 6px); left: 0; right: 0; z-index: 20;
  background: var(--bg-2); border: 1px solid var(--border); border-radius: 10px;
  overflow: hidden; box-shadow: 0 12px 32px rgba(0,0,0,.5);
}
.picker-item {
  width: 100%; text-align: left; background: transparent; border: none;
  padding: 10px 12px; cursor: pointer; color: var(--text-1);
  border-bottom: 1px solid var(--border);
  display: flex; flex-direction: column; gap: 2px;
}
.picker-item:last-child { border-bottom: none; }
.picker-item:hover { background: var(--bg-3); }
.picker-item.active { background: var(--accent-soft); }
.pi-name { font-weight: 600; }
.pi-sub { font-size: 11px; color: var(--text-2); }

.fade-enter-active, .fade-leave-active { transition: opacity .15s, transform .15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; transform: translateY(-4px); }

.conv-header { display: flex; justify-content: space-between; align-items: center; padding: 0 4px; font-size: 12px; color: var(--text-2); }
.new-btn {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--accent); color: white; border: none; border-radius: 6px;
  padding: 4px 10px; font-size: 12px; cursor: pointer; transition: background .15s;
}
.new-btn:hover:not(:disabled) { background: var(--accent-2); }
.new-btn:disabled { opacity: .4; cursor: not-allowed; }

.conv-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
.empty-hint { color: var(--text-3); font-size: 12px; padding: 12px 8px; text-align: center; }
.conv-item {
  padding: 8px 10px; border-radius: 8px; cursor: pointer;
  transition: background .15s;
}
.conv-item:hover { background: var(--bg-2); }
.conv-item.active { background: var(--accent-soft); }
.ci-title { font-size: 13px; color: var(--text-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ci-meta { display: flex; justify-content: space-between; align-items: center; margin-top: 3px; font-size: 11px; color: var(--text-3); }
.ci-actions { display: none; gap: 4px; }
.conv-item:hover .ci-actions { display: inline-flex; }
.ci-actions button {
  background: transparent; border: none; color: var(--text-3); cursor: pointer;
  padding: 2px 4px; border-radius: 4px; font-size: 11px;
}
.ci-actions button:hover { background: var(--bg-3); color: var(--text-1); }

.sidebar-err { color: #fca5a5; font-size: 12px; padding: 8px; background: rgba(239,68,68,.1); border-radius: 8px; }
.sidebar-foot { font-size: 11px; color: var(--text-3); word-break: break-all; padding: 0 4px; }

/* ---------- 主区 ---------- */
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 24px; border-bottom: 1px solid var(--border); background: var(--bg-1);
}
.top-left { display: flex; align-items: center; gap: 10px; }
.agent-tag {
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  color: white; font-size: 12px; font-weight: 600;
  padding: 4px 12px; border-radius: 12px;
}
.model-tag { font-size: 12px; color: var(--text-2); padding: 4px 10px; border-radius: 10px; background: var(--bg-2); }
.toggle { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-2); cursor: pointer; }
.toggle input { display: none; }
.t-slider {
  width: 34px; height: 18px; background: var(--bg-3); border-radius: 10px;
  position: relative; transition: background .15s;
}
.t-slider::after {
  content: ''; position: absolute; top: 2px; left: 2px;
  width: 14px; height: 14px; background: white; border-radius: 50%; transition: left .15s;
}
.toggle input:checked + .t-slider { background: var(--accent); }
.toggle input:checked + .t-slider::after { left: 18px; }

/* chat area */
.chat-area { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 16px; }
.welcome { text-align: center; margin-top: 60px; }
.w-title { font-size: 18px; color: var(--text-1); margin-bottom: 20px; }
.w-suggestions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; }
.chip {
  background: var(--bg-2); border: 1px solid var(--border); color: var(--text-2);
  padding: 8px 14px; border-radius: 18px; cursor: pointer; font-size: 13px;
  transition: all .15s;
}
.chip:hover { border-color: var(--accent); color: var(--text-1); background: var(--bg-3); }

/* bubbles */
.bubble-wrap { display: flex; gap: 10px; max-width: 85%; }
.bubble-wrap.user { align-self: flex-end; flex-direction: row-reverse; }
.bubble-wrap.assistant { align-self: flex-start; }
.avatar {
  flex-shrink: 0; width: 34px; height: 34px; border-radius: 10px;
  display: grid; place-items: center; font-size: 12px; font-weight: 700;
}
.bubble-wrap.user .avatar { background: var(--user-bubble); color: white; }
.bubble-wrap.assistant .avatar {
  background: linear-gradient(135deg, var(--accent), var(--accent-2)); color: white;
}
.bubble {
  padding: 12px 16px; border-radius: 14px; word-break: break-word;
  animation: fadeIn .25s;
}
.bubble.user {
  background: var(--user-bubble); color: white; border-top-right-radius: 4px;
}
.bubble.assistant {
  background: var(--assistant-bubble); color: var(--text-1);
  border: 1px solid var(--border); border-top-left-radius: 4px;
}
@keyframes fadeIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
.cursor { display: inline-block; color: var(--accent); animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }

/* 工具调用标记 */
.tool-row { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.tool-chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--accent-soft); border: 1px solid rgba(99,102,241,.4);
  color: #a5b4fc; font-size: 11px; padding: 2px 8px; border-radius: 10px;
}
.tool-chip svg { opacity: .8; }
.tool-chip em { font-style: normal; color: var(--text-2); margin-left: 2px; }

/* markdown */
.md :deep(p) { margin: 0 0 8px 0; }
.md :deep(p:last-child) { margin-bottom: 0; }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { margin: 12px 0 6px; color: var(--text-1); }
.md :deep(h1) { font-size: 16px; } .md :deep(h2) { font-size: 15px; } .md :deep(h3) { font-size: 14px; }
.md :deep(ul), .md :deep(ol) { padding-left: 20px; margin: 6px 0; }
.md :deep(li) { margin: 2px 0; }
.md :deep(a) { color: var(--accent); text-decoration: underline; }
.md :deep(blockquote) {
  border-left: 3px solid var(--accent); margin: 8px 0; padding: 4px 12px;
  color: var(--text-2); background: var(--bg-1); border-radius: 0 6px 6px 0;
}
.md :deep(code) {
  background: var(--bg-1); padding: 2px 6px; border-radius: 4px;
  font-family: Consolas, Monaco, monospace; font-size: 12px; color: #f0abfc;
}
.md :deep(pre) {
  background: #0d1117; border-radius: 8px; padding: 12px 14px; overflow-x: auto;
  margin: 10px 0; border: 1px solid var(--border);
}
.md :deep(pre code) {
  background: transparent; color: inherit; padding: 0; font-size: 12.5px;
}
.md :deep(table) { border-collapse: collapse; margin: 10px 0; }
.md :deep(th), .md :deep(td) {
  border: 1px solid var(--border); padding: 6px 10px; font-size: 13px;
}
.md :deep(th) { background: var(--bg-2); }

/* composer */
.composer {
  padding: 16px 24px 20px; border-top: 1px solid var(--border); background: var(--bg-1);
}
/* 参考文档附件条 */
.ctx-bar { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
.ctx-chips { display: flex; flex-wrap: wrap; gap: 6px; max-height: 96px; overflow-y: auto; }
.ctx-chip {
  display: inline-flex; align-items: center; gap: 6px; max-width: 240px;
  background: var(--bg-2); border: 1px solid var(--border); border-radius: 8px;
  padding: 4px 8px; font-size: 12px; color: var(--text-1);
  animation: fadeIn .25s;
}
.ctx-chip svg { flex-shrink: 0; color: var(--accent); }
.cc-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cc-size { color: var(--text-3); font-size: 10px; font-style: normal; flex-shrink: 0; }
.cc-del {
  background: transparent; border: none; color: var(--text-3); cursor: pointer;
  font-size: 11px; padding: 0 2px; border-radius: 3px; flex-shrink: 0;
}
.cc-del:hover { color: var(--danger); background: rgba(239,68,68,.15); }
.ctx-err { color: #fca5a5; font-size: 11px; padding: 6px 8px; background: rgba(239,68,68,.1); border-radius: 6px; }
.attach-btn {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer; margin-right: auto;
  background: var(--bg-2); border: 1px solid var(--border); color: var(--text-2);
  padding: 7px 12px; border-radius: 8px; font-size: 12px; transition: all .15s;
}
.attach-btn:hover { border-color: var(--accent); color: var(--text-1); background: var(--accent-soft); }
.attach-btn.busy { opacity: .5; cursor: progress; }
.composer textarea {
  width: 100%; resize: none; background: var(--bg-2); border: 1px solid var(--border);
  border-radius: 12px; padding: 12px 14px; color: var(--text-1); font: inherit;
  outline: none; transition: border-color .15s;
}
.composer textarea:focus { border-color: var(--accent); }
.composer textarea:disabled { opacity: .5; }
.c-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
.btn-send, .btn-stop {
  display: inline-flex; align-items: center; gap: 6px; border: none; border-radius: 8px;
  padding: 8px 18px; cursor: pointer; font-size: 13px; font-weight: 600;
  transition: all .15s;
}
.btn-send { background: linear-gradient(135deg, var(--accent), var(--accent-2)); color: white; }
.btn-send:hover:not(:disabled) { filter: brightness(1.1); }
.btn-send:disabled { opacity: .4; cursor: not-allowed; }
.btn-stop { background: rgba(239,68,68,.15); color: #fca5a5; }
.btn-stop:hover { background: rgba(239,68,68,.3); }

/* scrollbar */
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--bg-3); border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: var(--text-3); }
</style>

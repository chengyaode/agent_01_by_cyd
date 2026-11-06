// 后端接口封装：所有请求走 /api 前缀（由 vite 代理转发到 8080）
// Key 硬编码；改 key 只需改这一行，然后重新 npm run build

const API_KEY = 'change-me'

function headers() {
  return {
    'Content-Type': 'application/json',
    'X-API-Key': API_KEY
  }
}

// 上传用 multipart，不能带 Content-Type（浏览器自动设 boundary）
function uploadHeaders() {
  return { 'X-API-Key': API_KEY }
}

async function checkResp(resp) {
  if (resp.ok) return resp
  // 上传接口偶尔返回纯文本错误，先读 text 再尝试 JSON
  const text = await resp.text()
  let msg = text || (resp.status + ' ' + resp.statusText)
  try {
    const body = JSON.parse(text)
    if (body && (body.message || body.error)) msg = body.message || body.error
  } catch (e) { /* 非 JSON，用原始文本 */ }
  throw new Error(msg)
}

export async function fetchHealth() {
  const resp = await fetch('/api/health')
  return checkResp(resp).then((r) => r.json())
}

export async function fetchAgents() {
  const resp = await fetch('/api/agents', { headers: headers() })
  return checkResp(resp).then((r) => r.json())
}

export async function chatSync(name, message, sessionId) {
  const resp = await fetch(`/api/agents/${name}/chat`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ message, sessionId })
  })
  return checkResp(resp).then((r) => r.json())
}

// 流式对话。解析 SSE 帧，每帧回调 onEvent(eventName, dataObj)
export async function chatStream(name, message, sessionId, onEvent, signal) {
  const resp = await fetch(`/api/agents/${name}/chat/stream`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ message, sessionId }),
    signal
  })
  await checkResp(resp)

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    buf = buf.replace(/\r\n/g, '\n')
    let idx
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      parseFrame(frame, onEvent)
    }
  }
}

function parseFrame(frame, onEvent) {
  let event = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return
  try {
    onEvent(event, JSON.parse(dataLines.join('')))
  } catch (e) { /* 跳过坏帧 */ }
}

export async function deleteSession(name, sessionId) {
  const resp = await fetch(
    `/api/agents/${name}/sessions/${sessionId}`,
    { method: 'DELETE', headers: headers() }
  )
  return checkResp(resp).then((r) => r.json())
}

// ---------- context 参考文档 ----------

export async function uploadContext(name, files) {
  const form = new FormData()
  for (const f of files) form.append('files', f)
  const resp = await fetch(`/api/agents/${name}/context`, {
    method: 'POST',
    headers: uploadHeaders(),
    body: form
  })
  return checkResp(resp).then((r) => r.json())
}

export async function listContext(name) {
  const resp = await fetch(`/api/agents/${name}/context`, { headers: headers() })
  return checkResp(resp).then((r) => r.json())
}

export async function deleteContext(name, filename) {
  const safeName = encodeURIComponent(filename)
  const resp = await fetch(`/api/agents/${name}/context/${safeName}`, {
    method: 'DELETE', headers: headers()
  })
  return checkResp(resp).then((r) => r.json())
}

'use strict'
/**
 * wp-bff — work-platform BFF（最小 Ops 子集）
 *
 * 端点：
 *   GET  /api/wp/middleware              探测 8 个中间件，返回 MiddlewareOverview 契约
 *   POST /api/wp/middleware/:key/start   白名单内执行 docker compose up -d <key>
 *   POST /api/wp/middleware/:key/stop    白名单内执行 docker compose stop <key>
 *
 * 安全：key 白名单 + 固定命令形态，无任意参数透传；控制操作写审计日志。
 */
const http = require('node:http')
const net = require('node:net')
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

function httpGetJson(url, timeoutMs = 2500) {
  return new Promise(resolve => {
    const request = http.get(url, { timeout: timeoutMs }, response => {
      let body = ''
      response.on('data', chunk => { body += chunk })
      response.on('end', () => {
        try { resolve(JSON.parse(body)) } catch { resolve(null) }
      })
    })
    request.on('timeout', () => { request.destroy(); resolve(null) })
    request.on('error', () => resolve(null))
  })
}

const PORT = Number(process.env.WP_BFF_PORT || 8090)
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..')
const AUDIT_LOG = path.join(__dirname, 'logs', 'wp-bff-audit.log')

// key 白名单：key 即 docker-compose.yml 服务名，禁止其余任何值
const MIDDLEWARE = {
  redis: { name: 'Redis', role: '会话热存储 · L1 缓存', port: 6379 },
  postgres: { name: 'PostgreSQL', role: '关系库 · pgvector 冷存储', port: 5432 },
  qdrant: { name: 'Qdrant', role: '向量库 · 温存储检索', port: 6333, console_url: 'http://127.0.0.1:6333/dashboard', console_label: '控制台' },
  nats: { name: 'NATS', role: '神经总线 · 请求/回应', port: 4222 },
  nacos: { name: 'Nacos', role: '服务注册与发现', port: 8848, console_url: 'http://127.0.0.1:8848/nacos', console_label: '控制台' },
  minio: { name: 'MinIO', role: '对象存储 · 采集暂存区', port: 9000, console_url: 'http://127.0.0.1:9001', console_label: '控制台' },
  jaeger: { name: 'Jaeger', role: '分布式链路追踪', port: 16686, console_url: 'http://127.0.0.1:16686', console_label: 'Jaeger UI' },
  kafka: { name: 'Kafka', role: '事件流 · 发布订阅', port: 9092 },
}
const WHITELIST = new Set(Object.keys(MIDDLEWARE))

// 内存 ops 表：key -> { action: 'start'|'stop', startedAt }，由 GET 探针或 watchdog 清除
const ops = new Map()
const START_TIMEOUT_MS = 180000
const STOP_TIMEOUT_MS = 120000

function audit(action, key, detail) {
  const line = `[${new Date().toISOString()}] ${action} ${key || '-'} ${detail || ''}\n`
  try {
    fs.mkdirSync(path.dirname(AUDIT_LOG), { recursive: true })
    fs.appendFileSync(AUDIT_LOG, line)
  } catch { /* 审计失败不阻断主流程 */ }
  process.stdout.write(line)
}

function probeTcp(port, timeoutMs = 900) {
  return new Promise(resolve => {
    const started = Date.now()
    const socket = net.connect({ port, host: '127.0.0.1' })
    const done = up => {
      socket.destroy()
      resolve({ up, latencyMs: Date.now() - started })
    }
    socket.setTimeout(timeoutMs)
    socket.once('connect', () => done(true))
    socket.once('timeout', () => done(false))
    socket.once('error', () => done(false))
  })
}

function nowTime() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

function nodeFor(key, state, latencyMs) {
  const meta = MIDDLEWARE[key]
  return {
    key,
    name: meta.name,
    role: meta.role,
    port: meta.port,
    state,
    console_url: meta.console_url,
    console_label: meta.console_label,
    metrics: [
      { label: '探针', value: `${latencyMs}ms` },
      { label: '端口', value: String(meta.port) },
      { label: '进程', value: state === 'up' ? '健康' : '未响应' },
    ],
    last_check: nowTime(),
  }
}

async function probeState(key) {
  const { up, latencyMs } = await probeTcp(MIDDLEWARE[key].port)
  // ops 表修正：探针到达目标态即清除操作，否则报告中间态
  const op = ops.get(key)
  if (op) {
    if (op.action === 'start' && up) {
      ops.delete(key)
      audit('OP_DONE', key, `start 完成，耗时 ${Math.round((Date.now() - op.startedAt) / 1000)}s`)
      return { state: 'up', latencyMs }
    }
    if (op.action === 'stop' && !up) {
      ops.delete(key)
      audit('OP_DONE', key, 'stop 完成')
      return { state: 'down', latencyMs }
    }
    return { state: op.action === 'start' ? 'starting' : 'stopping', latencyMs }
  }
  return { state: up ? 'up' : 'down', latencyMs }
}

function composeService(action, key) {
  // 命令形态固定：仅 up -d 与 stop，key 已过白名单
  const args = action === 'start' ? ['compose', 'up', '-d', key] : ['compose', 'stop', key]
  const child = spawn('docker', args, { cwd: REPO_ROOT, windowsHide: true })
  let output = ''
  child.stdout.on('data', chunk => { output += chunk })
  child.stderr.on('data', chunk => { output += chunk })
  child.on('error', err => {
    audit('SPAWN_FAIL', key, String(err))
    ops.delete(key)
  })
  child.on('close', code => {
    audit('COMPOSE_EXIT', key, `${action} exit=${code} ${output.split('\n').slice(-3).join(' | ').trim()}`)
    if (code !== 0) ops.delete(key)
  })
  return child
}

function send(res, code, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Tenant-Id',
  })
  res.end(body)
}

async function handleControl(req, res, key, action) {
  if (!WHITELIST.has(key)) {
    audit('REJECT', key, `非白名单 key，action=${action}`)
    return send(res, 403, { error: `key "${key}" 不在白名单内` })
  }
  const probe = await probeState(key)
  if (ops.has(key)) {
    return send(res, 200, { data: nodeFor(key, probe.state, probe.latencyMs) })
  }
  if (action === 'start' && probe.state === 'up') {
    return send(res, 200, { data: nodeFor(key, 'up', probe.latencyMs) })
  }
  if (action === 'stop' && probe.state === 'down') {
    return send(res, 200, { data: nodeFor(key, 'down', probe.latencyMs) })
  }
  ops.set(key, { action, startedAt: Date.now() })
  const watchdogMs = action === 'start' ? START_TIMEOUT_MS : STOP_TIMEOUT_MS
  setTimeout(() => {
    if (ops.get(key)?.action === action) {
      ops.delete(key)
      audit('OP_TIMEOUT', key, `${action} 超时 ${Math.round(watchdogMs / 1000)}s，解除中间态`)
    }
  }, watchdogMs).unref()
  audit('OP_START', key, `docker compose ${action === 'start' ? 'up -d' : 'stop'} ${key}`)
  composeService(action, key)
  return send(res, 200, { data: nodeFor(key, action === 'start' ? 'starting' : 'stopping', probe.latencyMs) })
}

async function handleMiddleware(res) {
  const entries = await Promise.all(Object.keys(MIDDLEWARE).map(async key => ({ key, probe: await probeState(key) })))
  const items = entries.map(({ key, probe }) => nodeFor(key, probe.state, probe.latencyMs))
  const up = items.filter(item => item.state === 'up').length
  send(res, 200, {
    data: {
      enabled: true,
      checked_at: nowTime(),
      summary: { total: items.length, up, down: items.length - up },
      items,
    },
  })
}

// 拉取单个服务最近 traces 并聚合统计（limit 内采样口径）
function fetchServiceTraces(serviceName, limit = 20) {
  const url = `http://127.0.0.1:16686/api/traces?service=${encodeURIComponent(serviceName)}&limit=${limit}&lookback=24h`
  return httpGetJson(url, 4000).then(payload => Array.isArray(payload?.data) ? payload.data : [])
}

function spanServiceName(trace, span) {
  const process = trace.processes?.[span.processID]
  return process?.serviceName || 'unknown'
}

function spanHasError(span) {
  return (span.tags || []).some(tag => tag.key === 'error' && tag.value === true)
}

function aggregateTraces(serviceName, traces) {
  let spanCount = 0
  let errorTraces = 0
  const durationsMs = []
  for (const trace of traces) {
    const spans = trace.spans || []
    spanCount += spans.length
    if (spans.some(spanHasError)) errorTraces += 1
    if (spans.length) durationsMs.push(Math.max(...spans.map(span => span.duration || 0)) / 1000)
  }
  durationsMs.sort((a, b) => a - b)
  const p99 = durationsMs.length ? durationsMs[Math.min(durationsMs.length - 1, Math.floor(durationsMs.length * 0.99))] : 0
  return {
    name: serviceName,
    traces: traces.length,
    spans_24h: spanCount,
    error_rate: traces.length ? Math.round((errorTraces / traces.length) * 1000) / 10 : 0,
    p99_ms: Math.round(p99 * 10) / 10,
  }
}

function traceToRecent(trace) {
  const spans = (trace.spans || []).slice().sort((a, b) => (a.startTime || 0) - (b.startTime || 0))
  const root = spans.find(span => !span.references?.length) || spans[0]
  if (!root) return null
  const totalDuration = Math.max(...spans.map(span => (span.duration || 0))) / 1000
  return {
    time: new Date(root.startTime / 1000).toLocaleTimeString('zh-CN', { hour12: false }),
    trace_id: String(trace.traceID || ''),
    service: spanServiceName(trace, root),
    operation: root.operationName || '-',
    spans: spans.length,
    duration_ms: Math.round(totalDuration * 10) / 10,
    status: spans.some(spanHasError) ? 'error' : 'ok',
  }
}

async function handleTracing(res) {
  const probe = await probeTcp(MIDDLEWARE.jaeger.port)
  if (!probe.up) {
    return send(res, 200, { data: { enabled: false, ui_url: '', services: [], recent: [], checked_at: nowTime() } })
  }
  // 真实 Jaeger 数据：服务列表 + 每服务最近 20 条 trace 聚合（spans/错误率/P99）
  const servicesPayload = await httpGetJson('http://127.0.0.1:16686/api/services')
  const names = Array.isArray(servicesPayload?.data) ? servicesPayload.data : []
  const settled = await Promise.all(names.map(name =>
    fetchServiceTraces(name)
      .then(traces => ({ name, traces }))
      .catch(() => ({ name, traces: [] })),
  ))
  const services = settled.map(({ name, traces }) => aggregateTraces(name, traces))
  const recent = settled
    .flatMap(({ traces }) => traces.map(traceToRecent))
    .filter(Boolean)
    .sort((a, b) => b.time.localeCompare(a.time))
    .slice(0, 12)
  send(res, 200, {
    data: {
      enabled: true,
      ui_url: 'http://127.0.0.1:16686',
      services,
      recent,
      checked_at: nowTime(),
    },
  })
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const match = url.pathname.match(/^\/api\/wp\/middleware\/([a-z0-9-]+)\/(start|stop)$/)
  if (req.method === 'OPTIONS') return send(res, 204, {})
  if (req.method === 'GET' && url.pathname === '/api/wp/middleware') return handleMiddleware(res)
  if (req.method === 'GET' && url.pathname === '/api/wp/tracing') return handleTracing(res)
  if (req.method === 'POST' && match) return handleControl(req, res, match[1], match[2])
  return send(res, 404, { error: 'not found' })
})

server.listen(PORT, '127.0.0.1', () => {
  audit('BOOT', null, `wp-bff listening on 127.0.0.1:${PORT}, repo=${REPO_ROOT}`)
})

'use strict'
/**
 * wp-bff — work-platform BFF（最小 Ops 子集）
 *
 * 端点：
 *   GET  /api/wp/middleware              探测 8 个中间件，返回 MiddlewareOverview 契约
 *   POST /api/wp/middleware/:key/start   白名单内执行 docker compose up -d <key>
 *   POST /api/wp/middleware/:key/stop    白名单内执行 docker compose stop <key>
 *   GET  /api/wp/tracing                 Jaeger 服务注册与最近 trace 聚合
 *   GET  /api/wp/knowledge               躯体层知识统计（知识量 / 检索 P99 / 命中率 / 三层存储）
 *   POST /api/wp/knowledge               知识入库（单篇）—— 代理体层，工作平台侧写路径
 *   POST /api/wp/knowledge/search        检索测试（返回命中片段与高亮词，供前端标注）
 *   GET  /api/wp/tools                   执行视图聚合（R-C05 预：工具调用流 + 沙箱状态）
 *   GET  /api/wp/tools/registry          工具注册表摘要 + 变更历史（IN-06）
 *   GET  /api/wp/tools/audit             工具调用审计明细（R5-08）
 *   GET  /api/wp/tools/audit/stats       工具调用审计汇总
 *   GET  /api/wp/tools/metrics           成功率 / P50·P95·P99 / 熔断状态
 *   GET  /api/wp/tools/sandbox           沙箱状态
 *   POST /api/wp/tools/execute           执行工具（控制端点：来源白名单 + 控制令牌）
 *   GET  /api/wp/tools/{name}            单工具详情
 *   GET  /api/wp/tools/{name}/impact     工具变更影响分析（IN-06）
 *   GET  /api/wp/healthz                 进程存活 + 控制面配置自检（只读）
 *
 * 安全模型（2026-09-16 加固，09-18 补写路径边界）：
 *   1. 读端点（GET）开放但仅面向白名单来源回显 CORS 头
 *   2. 控制端点（POST）双重校验：来源白名单（Origin/Referer）+ 控制令牌 X-WP-Control-Token
 *      —— 判定按**方法**而非路径：`/knowledge` 的 GET 免令牌，其 POST（写知识库）需令牌
 *   3. 控制令牌来自 WP_BFF_CONTROL_TOKEN；未配置时启动生成一次性随机令牌并写入 logs/ 与 stdout
 *   4. CORS 不再使用通配符，仅回显 WP_BFF_ALLOWED_ORIGINS 内的来源
 *   5. key 白名单 + 固定命令形态，无任意参数透传；控制操作写审计日志
 *   6. 令牌比较使用 timingSafeEqual，避免时序侧信道
 */
const http = require('node:http')
const net = require('node:net')
const crypto = require('node:crypto')
const { spawn: realSpawn } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const DEFAULT_ALLOWED_ORIGINS = [
  'http://127.0.0.1:3001',
  'http://localhost:3001',
  'http://[::1]:3001',
]

// ─────────── 跨服务超时预算（权威登记表：contracts/timeout-budget.yaml）───────────
//
// 为什么分档，而不是给所有子请求一个统一定时值：
//   操作类型差异极大 —— 纯查询是毫秒级，LLM 生成是秒级。给同一个值，
//   对读型必然过长（掩盖真实故障），对生成型必然过短（把「慢」说成「没有」）。
//   Phase 5 实测的正是后者：沙箱冷探测 5.7s 撞上 5s 统一预算，
//   `/api/wp/tools` 长期宣称「沙箱不可用」，而直连该端点是 200。
//
// 约束：每个常量必须 >= 登记表中对应边的 downstream_worst_case_ms × min_headroom_ratio。
// 改动任一侧后，必须同步登记表并重跑 `python scripts/timeout-budget-check.py`（CI 门禁）。
//
// 注意（本文件易混淆点）：这里存在多个超时值，用途完全不同 ——
//   · 下方 WP_* 档位常量：**跨服务调用**预算，参与登记表门禁
//   · `httpGetJson` 的 2500ms：仅供本地聚合读取，不对应任何跨服务边
//   · `START/STOP_TIMEOUT_MS`：docker 容器启停 watchdog，量级差两个数量级
// 新增跨服务调用请用下方档位常量，不要就地写魔法数字。
const READ_TIMEOUT_MS = Number(process.env.WP_BFF_READ_TIMEOUT_MS || 5000)
const RETRIEVE_TIMEOUT_MS = Number(process.env.WP_BFF_RETRIEVE_TIMEOUT_MS || 15000)
const GENERATE_TIMEOUT_MS = Number(process.env.WP_BFF_GENERATE_TIMEOUT_MS || 45000)
// ASK 档：仅服务 wp-bff → session-manager /ask 这一条边（GAP-06 闭合，2026-09-19）。
// session 一次 ask 的下游最坏 = ASK_TOTAL_BUDGET_MS = 35s（RequestBudget 硬上限），
// 53s / 35s = 1.51x。**不直接抬高 GENERATE 档**：它被 /brain/ask 等多条边共享，
// 抬高会把那些「快速失败并降级」的边一起拖慢。
const ASK_TIMEOUT_MS = Number(process.env.WP_BFF_ASK_TIMEOUT_MS || 53000)
const WRITE_TIMEOUT_MS = Number(process.env.WP_BFF_WRITE_TIMEOUT_MS || 60000)
const EXECUTE_TIMEOUT_MS = Number(process.env.WP_BFF_EXECUTE_TIMEOUT_MS || 60000)

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

/**
 * 带**失败原因**的通用 JSON 出站请求（GET/POST）。
 *
 * 为什么需要它：把「超时 / 连接拒绝 / 404 / 非 JSON」统统折叠成 `null` 时，
 * 上层只能把 null 表达成「不可用」——于是一个**只是慢**的接口会被写成**不存在**。
 * Phase 5 实测到 `GET /api/tool/sandbox` 冷探测 5.7s 超过 5s 预算，
 * `/api/wp/tools` 因此长期宣称「沙箱不可用」，与服务侧真相相反且无从察觉。
 * 本函数保留原因，让上层能如实区分「慢」与「没有」。
 *
 * @returns {Promise<{ok: true, data: any, reason: null}
 *                 | {ok: false, data: null, reason: 'timeout'|'unreachable'|'endpoint_missing'|'http_error'|'bad_json', status?: number}>}
 */
function httpRequestJsonMeta(url, { method = 'GET', headers = {}, body = null, timeoutMs = READ_TIMEOUT_MS } = {}) {
  return new Promise(resolve => {
    const payload = body == null ? null : Buffer.from(JSON.stringify(body), 'utf8')
    let settled = false
    const finish = outcome => { if (!settled) { settled = true; resolve(outcome) } }
    const request = http.request(url, {
      method,
      timeout: timeoutMs,
      headers: {
        ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': payload.length } : {}),
        ...headers,
      },
    }, response => {
      let raw = ''
      response.on('data', chunk => { raw += chunk })
      response.on('end', () => {
        const status = response.statusCode
        if (status === 404) return finish({ ok: false, data: null, reason: 'endpoint_missing', status })
        if (status < 200 || status >= 300) return finish({ ok: false, data: null, reason: 'http_error', status })
        try { finish({ ok: true, data: JSON.parse(raw), reason: null }) }
        catch { finish({ ok: false, data: null, reason: 'bad_json', status }) }
      })
    })
    request.on('timeout', () => { request.destroy(); finish({ ok: false, data: null, reason: 'timeout' }) })
    request.on('error', () => finish({ ok: false, data: null, reason: 'unreachable' }))
    if (payload) request.write(payload)
    request.end()
  })
}

/**
 * 通用 JSON 出站请求（GET/POST）。失败一律 resolve(null)，
 * 由调用方按「诚实地报告不可用」处理，绝不返回伪造数据。
 *
 * 需要区分失败原因的调用方请用 {@link httpRequestJsonMeta}；本函数是它丢弃原因后的薄封装，
 * 两者共享同一套传输行为（超时/状态码/解析口径一致）。
 */
async function httpRequestJson(url, opts = {}) {
  const outcome = await httpRequestJsonMeta(url, opts)
  return outcome.ok ? outcome.data : null
}

/** 读取请求体 JSON（带体积上限，避免超大请求拖垮 BFF） */
function readJsonBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', chunk => {
      size += chunk.length
      if (size > limit) {
        reject(new Error('请求体过大（上限 64KB）'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      if (!raw.trim()) return resolve({})
      try { resolve(JSON.parse(raw)) } catch { reject(new Error('请求体不是合法 JSON')) }
    })
    req.on('error', reject)
  })
}

/** 检索高亮词提取：整体查询 + 分词 + 中文 2-gram（无空格语言的高亮兜底） */
function queryTerms(query) {
  const raw = String(query || '').trim()
  if (!raw) return []
  const terms = new Set([raw])
  raw.split(/[\s,，。、；;：:？！!?（）()\[\]{}"'“”]+/).filter(Boolean).forEach(token => {
    terms.add(token)
    if (/[\u4e00-\u9fa5]/.test(token)) {
      for (let i = 0; i + 2 <= token.length; i++) terms.add(token.slice(i, i + 2))
    }
  })
  return [...terms].filter(term => term.length >= 2).sort((a, b) => b.length - a.length).slice(0, 24)
}

/** 围绕首个命中词截窗，返回片段与命中词（BFF 计算，前端据此高亮） */
function buildSnippet(content, terms, window = 120) {
  const text = String(content || '').replace(/\s+/g, ' ').trim()
  if (!text) return { snippet: '', matched_terms: [] }
  const matched = terms.filter(term => text.includes(term))
  if (text.length <= window * 2) return { snippet: text, matched_terms: matched }
  let index = -1
  for (const term of terms) {
    const found = text.indexOf(term)
    if (found >= 0 && (index < 0 || found < index)) index = found
  }
  if (index < 0) return { snippet: `${text.slice(0, window * 2)}…`, matched_terms: [] }
  const start = Math.max(0, index - window)
  const end = Math.min(text.length, start + window * 2)
  return {
    snippet: `${start > 0 ? '…' : ''}${text.slice(start, end)}${end < text.length ? '…' : ''}`,
    matched_terms: matched,
  }
}

const DEFAULT_PORT = Number(process.env.WP_BFF_PORT || 8090)

// key 白名单：key 即 docker-compose.yml 服务名，禁止其余任何值
const MIDDLEWARE = {
  redis: { name: 'Redis', role: '会话热存储 · L1 缓存', port: 6379 },
  postgres: { name: 'PostgreSQL', role: '关系库 · 元数据真相源', port: 5432 },
  qdrant: { name: 'Qdrant', role: '向量库 · 温存储检索', port: 6333, console_url: 'http://127.0.0.1:6333/dashboard', console_label: '控制台' },
  nats: { name: 'NATS', role: '神经总线 · 请求/回应', port: 4222 },
  nacos: { name: 'Nacos', role: '服务注册与发现', port: 8848, console_url: 'http://127.0.0.1:8848/nacos', console_label: '控制台' },
  minio: { name: 'MinIO', role: '对象存储 · 采集暂存区', port: 9000, console_url: 'http://127.0.0.1:9001', console_label: '控制台' },
  jaeger: { name: 'Jaeger', role: '分布式链路追踪', port: 16686, console_url: 'http://127.0.0.1:16686', console_label: 'Jaeger UI' },
  kafka: { name: 'Kafka', role: '事件流 · 发布订阅', port: 9092 },
}
const WHITELIST = new Set(Object.keys(MIDDLEWARE))

/**
 * 本服务实现的端点（路径相对 /api/wp 前缀）。
 * 用途：contract-check.py 依赖此清单校验「实现端点必须在契约中登记」，请与
 * contracts/work-platform-bff-openapi.yaml 的 x-wp-status: implemented 保持一致。
 */
const IMPLEMENTED_ENDPOINTS = [
  { method: 'GET', path: '/healthz' },
  { method: 'GET', path: '/overview' },
  { method: 'GET', path: '/middleware' },
  { method: 'POST', path: '/middleware/{key}/start' },
  { method: 'POST', path: '/middleware/{key}/stop' },
  { method: 'GET', path: '/tracing' },
  { method: 'GET', path: '/knowledge' },
  { method: 'POST', path: '/knowledge' },
  { method: 'POST', path: '/knowledge/search' },
  // R-C05(预) / R5 / IN-06 四肢层工具执行视图
  { method: 'GET', path: '/tools' },
  { method: 'GET', path: '/tools/registry' },
  { method: 'GET', path: '/tools/audit' },
  { method: 'GET', path: '/tools/audit/stats' },
  { method: 'GET', path: '/tools/metrics' },
  { method: 'GET', path: '/tools/sandbox' },
  { method: 'POST', path: '/tools/execute' },
  { method: 'GET', path: '/tools/{name}' },
  { method: 'GET', path: '/tools/{name}/impact' },
  { method: 'GET', path: '/brain' },
  { method: 'GET', path: '/brain/{decision_id}' },
  { method: 'POST', path: '/brain/ask' },
  { method: 'GET', path: '/brain/decisions' },
  { method: 'POST', path: '/brain/memory/ingest' },
  { method: 'POST', path: '/brain/memory/compress' },
  { method: 'GET', path: '/brain/memory/subgraph' },
  { method: 'GET', path: '/brain/memory/stats' },
  { method: 'POST', path: '/session' },
  { method: 'GET', path: '/session/stats' },
  { method: 'POST', path: '/session/{session_id}/ask' },
  { method: 'GET', path: '/session/{session_id}/context' },
  { method: 'DELETE', path: '/session/{session_id}' },
]

/**
 * 路由守卫表：**由 `IMPLEMENTED_ENDPOINTS` 派生**，刻意不另立一份端点清单，
 * 以免出现第三份「端点真相」（前端契约 / IMPLEMENTED_ENDPOINTS / 路由分派）。
 *
 * 作用是把两种语义分开：
 *   - 路径不存在          → 404 AGENT_NOT_FOUND
 *   - 路径存在但方法不对  → 405 AGENT_METHOD_NOT_ALLOWED（附 `Allow` 头）
 * 与 Java 三服务 `GlobalExceptionHandler` 的路由层分流保持同一语义，
 * 详见 `docs/异常流程归纳.md` §2。
 */
const ROUTE_GUARD = (() => {
  const escapeSegment = seg => seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const toRegex = routePath => new RegExp(`^/api/wp${routePath
    .split('/')
    .map(seg => (/^\{.+\}$/.test(seg) ? '[a-z0-9-]+' : escapeSegment(seg)))
    .join('/')}$`)
  const exact = new Map()
  const param = []
  for (const { method, path: routePath } of IMPLEMENTED_ENDPOINTS) {
    if (!routePath.includes('{')) {
      const key = `/api/wp${routePath}`
      exact.set(key, [...(exact.get(key) || []), method])
      continue
    }
    let entry = param.find(item => item.routePath === routePath)
    if (!entry) {
      entry = { routePath, re: toRegex(routePath), methods: [] }
      param.push(entry)
    }
    if (!entry.methods.includes(method)) entry.methods.push(method)
  }
  return {
    /** @returns {string[]|null} 该路径允许的方法；路径本身不存在时返回 null */
    allowedMethods(pathname) {
      if (exact.has(pathname)) return exact.get(pathname)
      const hit = param.find(item => item.re.test(pathname))
      return hit ? hit.methods : null
    },
  }
})()

/**
 * `/api/wp/tools` 下的**固定子路径段**（registry / audit / metrics / sandbox / execute），
 * 由 `IMPLEMENTED_ENDPOINTS` 派生 —— 参数路由 `/tools/{name}` 必须让开这些名字。
 *
 * 为什么必须让开：`/tools/execute` 只接受 POST。若参数路由先拦截 GET，
 * 就会变成「代理一个名为 execute 的工具详情」并返回 200，
 * 而 ROUTE_GUARD 判定的是 405 —— 同一个请求两种语义，路由层分流形同虚设。
 * 工具名与固定段重名时以固定段为准（工具注册表里本来也不允许叫 execute）。
 */
const TOOL_RESERVED_SEGMENTS = new Set(
  IMPLEMENTED_ENDPOINTS
    .map(item => item.path)
    .filter(routePath => routePath.startsWith('/tools/'))
    .map(routePath => routePath.slice('/tools/'.length).split('/')[0])
    .filter(segment => segment && !segment.startsWith('{')),
)

/** 总览页仍待 BFF 实现的聚合数据域，透传给前端用于降级展示 */
const OVERVIEW_GAPS = [
  'vitals', 'organs', 'senses', 'evolution', 'collaboration',
  'experts', 'skills', 'connectors', 'automations', 'cases', 'approvals',
  'models', 'remote_im', 'agents',
]

/** 体层（body-service）地址；未启动时 /knowledge 端点如实返回 available=false */
const DEFAULT_BODY_URL = String(process.env.WP_BFF_BODY_URL || 'http://127.0.0.1:8083').replace(/\/+$/, '')
const DEFAULT_NLP_URL = String(process.env.WP_BFF_NLP_URL || 'http://127.0.0.1:8000').replace(/\/+$/, '')
const DEFAULT_SESSION_URL = String(process.env.WP_BFF_SESSION_URL || 'http://127.0.0.1:8081').replace(/\/+$/, '')
/** 四肢层（tool-executor）地址；未启动时 /tools 系列如实返回 available=false */
const DEFAULT_TOOL_URL = String(process.env.WP_BFF_TOOL_URL || 'http://127.0.0.1:8084').replace(/\/+$/, '')

const START_TIMEOUT_MS = 180000
const STOP_TIMEOUT_MS = 120000

function defaultControlTokenFile() {
  return path.join(__dirname, 'logs', 'wp-bff-control-token')
}

/**
 * 解析控制令牌：优先环境变量；否则生成随机令牌并落盘（0600）。
 * 未配置环境变量时服务仍可启动，但控制端点只接受随机令牌，默认拒绝一切凭据猜测。
 */
function resolveControlToken({ tokenFile = defaultControlTokenFile(), envToken = process.env.WP_BFF_CONTROL_TOKEN } = {}) {
  const fromEnv = String(envToken || '').trim()
  if (fromEnv) return { token: fromEnv, source: 'env', tokenFile: null }
  const token = crypto.randomBytes(24).toString('hex')
  let written = false
  try {
    fs.mkdirSync(path.dirname(tokenFile), { recursive: true })
    fs.writeFileSync(tokenFile, `${token}\n`, { mode: 0o600 })
    written = true
  } catch { /* 落盘失败不阻断启动，令牌仍在 stdout 中输出 */ }
  return { token, source: 'generated', tokenFile: written ? tokenFile : null }
}

function resolveAllowedOrigins(raw = process.env.WP_BFF_ALLOWED_ORIGINS) {
  const list = String(raw || '')
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
  return new Set(list.length ? list : DEFAULT_ALLOWED_ORIGINS)
}

function timingSafeEqual(a, b) {
  const left = Buffer.from(String(a ?? ''), 'utf8')
  const right = Buffer.from(String(b ?? ''), 'utf8')
  if (left.length !== right.length) return false
  return crypto.timingSafeEqual(left, right)
}

/** 返回 true=白名单来源，false=明确越权，null=无浏览器来源信息（非浏览器客户端） */
function originVerdict(req, allowedOrigins) {
  const origin = req.headers.origin
  if (origin) return allowedOrigins.has(String(origin))
  const referer = req.headers.referer
  if (referer) {
    try { return allowedOrigins.has(new URL(String(referer)).origin) } catch { return false }
  }
  return null
}

function createServer(options = {}) {
  const middleware = options.middleware || MIDDLEWARE
  const whitelist = new Set(Object.keys(middleware))
  const allowedOrigins = options.allowedOrigins || (options.allowedOriginsSet || resolveAllowedOrigins())
  const controlToken = options.controlToken || resolveControlToken({ tokenFile: options.tokenFile || defaultControlTokenFile() })
  const tokenValue = typeof controlToken === 'string' ? controlToken : controlToken.token
  const spawnImpl = options.spawnImpl || realSpawn
  const probeImpl = options.probeImpl || probeTcp
  const fetchJson = options.fetchJson || httpGetJson
  const jsonRequest = options.jsonRequest || httpRequestJson
  // 带原因的出站请求（工具域聚合与代理用）。注入假传输的测试没有原因信息，
  // 只能退回通用 reason='unreachable' —— 不假装知道细节，生产路径才拿得到 timeout/404 级区分。
  const jsonRequestMeta = options.jsonRequestMeta || (
    options.jsonRequest
      ? async (url, opts) => {
        const data = await options.jsonRequest(url, opts).catch(() => null)
        return data == null
          ? { ok: false, data: null, reason: 'unreachable' }
          : { ok: true, data, reason: null }
      }
      : httpRequestJsonMeta
  )
  const bodyUrl = String(options.bodyUrl || DEFAULT_BODY_URL).replace(/\/+$/, '')
  const nlpUrl = String(options.nlpUrl || DEFAULT_NLP_URL).replace(/\/+$/, '')
  const sessionUrl = String(options.sessionUrl || DEFAULT_SESSION_URL).replace(/\/+$/, '')
  const toolUrl = String(options.toolUrl || DEFAULT_TOOL_URL).replace(/\/+$/, '')
  const repoRoot = options.repoRoot || path.resolve(__dirname, '..', '..', '..')
  const auditPath = options.auditPath || path.join(__dirname, 'logs', 'wp-bff-audit.log')

  const ops = new Map()

  function audit(action, key, detail) {
    const line = `[${new Date().toISOString()}] ${action} ${key || '-'} ${detail || ''}\n`
    try {
      fs.mkdirSync(path.dirname(auditPath), { recursive: true })
      fs.appendFileSync(auditPath, line)
    } catch { /* 审计失败不阻断主流程 */ }
    if (options.silent !== true) process.stdout.write(line)
  }

  function nowTime() {
    return new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }

  function nodeFor(key, state, latencyMs) {
    const meta = middleware[key]
    return {
      key,
      name: meta.name,
      role: meta.role,
      port: meta.port,
      state,
      console_url: meta.console_url,
      console_label: meta.console_label,
      // 口径：本探针只判断 TCP 端口可达性，不探测进程内部健康度，
      // 因此指标命名为“端口状态”而不是“进程健康”，避免绿点被误读。
      metrics: [
        { label: '探针', value: `${latencyMs}ms` },
        { label: '探针类型', value: 'TCP 端口可达性' },
        { label: '端口', value: String(meta.port) },
        { label: '端口状态', value: state === 'up' ? '可达' : '不可达' },
      ],
      last_check: nowTime(),
    }
  }

  async function probeState(key) {
    const { up, latencyMs } = await probeImpl(middleware[key].port)
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
    const child = spawnImpl('docker', args, { cwd: repoRoot, windowsHide: true })
    let output = ''
    child.stdout?.on('data', chunk => { output += chunk })
    child.stderr?.on('data', chunk => { output += chunk })
    child.on('error', err => {
      audit('SPAWN_FAIL', key, String(err))
      ops.delete(key)
    })
    child.on('close', code => {
      audit('COMPOSE_EXIT', key, `${action} exit=${code} ${String(output).split('\n').slice(-3).join(' | ').trim()}`)
      if (code !== 0) ops.delete(key)
    })
    return child
  }

  function corsHeaders(req) {
    const origin = req.headers.origin
    const headers = {
      'Content-Type': 'application/json; charset=utf-8',
      Vary: 'Origin',
    }
    if (origin && allowedOrigins.has(String(origin))) {
      headers['Access-Control-Allow-Origin'] = String(origin)
      headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
      headers['Access-Control-Allow-Headers'] = 'Content-Type, X-Tenant-Id, X-WP-Control-Token'
      headers['Access-Control-Max-Age'] = '600'
    }
    return headers
  }

  function send(req, res, code, payload) {
    const body = JSON.stringify(payload)
    res.writeHead(code, corsHeaders(req))
    res.end(body)
  }

  /**
   * 统一错误响应信封：`{code, message, details}`。
   *
   * 与 gateway-service 的 `JwtAuthFilter.reject()`、Java 三服务的
   * `GlobalExceptionHandler` **同一格式** —— 客户端只需一套解析逻辑。
   * 2026-09-18 之前本服务用 `{error}`，是全平台唯一的例外（见 `docs/异常流程归纳.md` §2.3）。
   * 注意：这是**行为变更**，任何解析旧 `error` 字段的外部消费方需改读 `message`。
   */
  function fail(req, res, status, code, message, details = {}) {
    return send(req, res, status, { code, message, details })
  }

  function authorizeControl(req) {
    const verdict = originVerdict(req, allowedOrigins)
    if (verdict === false) {
      return {
        ok: false, status: 403, code: 'AGENT_FORBIDDEN',
        message: '请求来源不在白名单内（Origin/Referer 校验失败）',
        details: { guard: 'origin-whitelist' },
      }
    }
    const presented = req.headers['x-wp-control-token']
    if (!presented || !timingSafeEqual(presented, tokenValue)) {
      return {
        ok: false, status: 401, code: 'AGENT_UNAUTHORIZED',
        message: '控制令牌缺失或无效（X-WP-Control-Token）',
        details: { guard: 'control-token' },
      }
    }
    return { ok: true }
  }

  async function handleControl(req, res, key, action) {
    const auth = authorizeControl(req)
    if (!auth.ok) {
      audit('REJECT_AUTH', key, `${action} ${auth.message}`)
      return fail(req, res, auth.status, auth.code, auth.message, auth.details)
    }
    if (!whitelist.has(key)) {
      audit('REJECT', key, `非白名单 key，action=${action}`)
      return fail(req, res, 403, 'AGENT_FORBIDDEN', `key "${key}" 不在白名单内`, { key })
    }
    const probe = await probeState(key)
    if (ops.has(key)) {
      return send(req, res, 200, { data: nodeFor(key, probe.state, probe.latencyMs) })
    }
    if (action === 'start' && probe.state === 'up') {
      return send(req, res, 200, { data: nodeFor(key, 'up', probe.latencyMs) })
    }
    if (action === 'stop' && probe.state === 'down') {
      return send(req, res, 200, { data: nodeFor(key, 'down', probe.latencyMs) })
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
    return send(req, res, 200, { data: nodeFor(key, action === 'start' ? 'starting' : 'stopping', probe.latencyMs) })
  }

  async function handleMiddleware(req, res) {
    const entries = await Promise.all(Object.keys(middleware).map(async key => ({ key, probe: await probeState(key) })))
    const items = entries.map(({ key, probe }) => nodeFor(key, probe.state, probe.latencyMs))
    const up = items.filter(item => item.state === 'up').length
    send(req, res, 200, {
      data: {
        enabled: true,
        checked_at: nowTime(),
        probe_mode: 'tcp',
        summary: { total: items.length, up, down: items.length - up },
        items,
      },
    })
  }

  // 拉取单个服务最近 traces 并聚合统计（limit 内采样口径）
  function fetchServiceTraces(serviceName, limit = 20) {
    const url = `http://127.0.0.1:16686/api/traces?service=${encodeURIComponent(serviceName)}&limit=${limit}&lookback=24h`
    return fetchJson(url, 4000).then(payload => Array.isArray(payload?.data) ? payload.data : [])
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
      // 采样口径：仅基于 Jaeger 最近 limit 条 trace，非全量 24h 统计
      sample_size: traces.length,
      sample_limit: 20,
      sample_window: '24h',
      p99_basis: 'sampled_recent_traces',
    }
  }

  function traceToRecent(trace) {
    const spans = (trace.spans || []).slice().sort((a, b) => (a.startTime || 0) - (b.startTime || 0))
    const root = spans.find(span => !span.references?.length) || spans[0]
    if (!root) return null
    const totalDuration = Math.max(...spans.map(span => (span.duration || 0))) / 1000
    const startedMs = Math.round((root.startTime || 0) / 1000)
    return {
      time: new Date(startedMs).toLocaleTimeString('zh-CN', { hour12: false }),
      // 排序键：绝对毫秒时间戳，避免用本地时间字符串做跨天字典序比较
      start_time_ms: startedMs,
      trace_id: String(trace.traceID || ''),
      service: spanServiceName(trace, root),
      operation: root.operationName || '-',
      spans: spans.length,
      duration_ms: Math.round(totalDuration * 10) / 10,
      status: spans.some(spanHasError) ? 'error' : 'ok',
    }
  }

  /**
   * 总览聚合：只聚合 wp-bff 已具备真实数据源的部分（中间件 + 链路追踪），
   * 其余数据域通过 gaps 显式列出，由前端按降级策略标注为 Mock，不做静默填充。
   */
  async function handleOverview(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const entries = await Promise.all(Object.keys(middleware).map(async key => ({ key, probe: await probeState(key) })))
    const items = entries.map(({ key, probe }) => nodeFor(key, probe.state, probe.latencyMs))
    const up = items.filter(item => item.state === 'up').length
    const pending = items.filter(item => item.state === 'starting' || item.state === 'stopping').length

    const jaegerProbe = await probeImpl(middleware.jaeger.port)
    let tracing = { enabled: false, services: 0, spans_sampled: 0, recent_errors: 0, p99_ms: 0, p99_basis: 'sampled_recent_traces' }
    if (jaegerProbe.up) {
      const servicesPayload = await fetchJson('http://127.0.0.1:16686/api/services')
      const names = Array.isArray(servicesPayload?.data) ? servicesPayload.data : []
      const settled = await Promise.all(names.map(name =>
        fetchServiceTraces(name).then(traces => ({ name, traces })).catch(() => ({ name, traces: [] })),
      ))
      const stats = settled.map(({ name, traces }) => aggregateTraces(name, traces))
      const recent = settled.flatMap(({ traces }) => traces.map(traceToRecent)).filter(Boolean)
      tracing = {
        enabled: true,
        services: names.length,
        spans_sampled: stats.reduce((sum, stat) => sum + stat.spans_24h, 0),
        recent_errors: recent.filter(item => item.status === 'error').length,
        p99_ms: stats.length ? Math.max(...stats.map(stat => stat.p99_ms)) : 0,
        p99_basis: 'sampled_recent_traces',
      }
    }

    const modelMetrics = await fetchModelMetrics(tenantId)
    send(req, res, 200, {
      data: {
        source: 'wp-bff',
        checked_at: nowTime(),
        model_calls: modelMetrics.model_calls,
        model_runtime: modelMetrics.model_runtime,
        model_metrics_available: modelMetrics.available,
        model_metrics_stale: modelMetrics.stale,
        model_metrics_note: modelMetrics.note,
        observability: {
          middleware: {
            total: items.length,
            up,
            down: items.length - up - pending,
            pending,
            probe_mode: 'tcp',
            items,
          },
          tracing,
        },
        gaps: OVERVIEW_GAPS,
        note: 'observability 为真实数据；gaps 中的数据域仍待 BFF 实现，前端按降级策略标注',
      },
    })
  }

  async function handleTracing(req, res) {
    const probe = await probeImpl(middleware.jaeger.port)
    if (!probe.up) {
      return send(req, res, 200, { data: { enabled: false, ui_url: '', services: [], recent: [], checked_at: nowTime() } })
    }
    // 真实 Jaeger 数据：服务列表 + 每服务最近 20 条 trace 聚合（spans/错误率/P99）
    const servicesPayload = await fetchJson('http://127.0.0.1:16686/api/services')
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
      .sort((a, b) => b.start_time_ms - a.start_time_ms)
      .slice(0, 12)
    send(req, res, 200, {
      data: {
        enabled: true,
        ui_url: 'http://127.0.0.1:16686',
        services,
        recent,
        checked_at: nowTime(),
      },
    })
  }

  /**
   * 躯体层知识统计（R-C03 躯体视图数据源）
   *
   * <p>透传体层 {@code GET /api/body/knowledge/stats}：知识量（文档/切片/向量点）、
   * 检索指标（P99 / 检索命中率 / 缓存命中率）、三层存储可用性与嵌入/重排后端。
   * body-service 未启动时返回 {@code available:false} 并列出未取到的数据域，
   * 由前端标注降级——**不以 0 冒充"知识量为零"**。
   */
  /** 大模型工作状态节点（handleBrain 与 handleOverview 共用，避免两份口径）。 */
  function modelRuntimeNodes(brain, cache, knowledge, cacheStats, chunkCount, llmAvailable) {
    return [
      {
        node: 'llm_gateway',
        state: llmAvailable ? 'healthy' : 'offline',
        backend: (brain && brain.llm && brain.llm.engines && brain.llm.engines[0] && brain.llm.engines[0].name) || 'unknown',
        degraded: !llmAvailable,
        calls: (brain && brain.llm && brain.llm.stats && brain.llm.stats.calls) || 0,
        tokens: (brain && brain.llm && brain.llm.stats && brain.llm.stats.completion_tokens) || 0,
      },
      {
        node: 'semantic_cache',
        state: cache ? 'healthy' : 'offline',
        backend: (cache && cache.backend) || 'unknown',
        degraded: Boolean(cache && cache.degraded),
        hit_rate: (cacheStats && cacheStats.hit_rate) || 0,
      },
      {
        node: 'retrieval',
        state: knowledge ? 'healthy' : 'offline',
        backend: 'body-service',
        degraded: !knowledge,
        chunks: chunkCount,
      },
    ]
  }

  /**
   * 大模型调用监控（`model_calls`）—— 开发设计文档 §10 总览增强驾驶舱。
   *
   * 两条硬规矩（§10.3）：
   *   1. **断流时保留最近点位**并显示「等待校准」，禁止画虚假归零曲线；
   *   2. 成本未接入计量就回 `null` + `cost_basis='not_metered'`，**不编造金额**。
   */
  let lastModelMetrics = null

  async function fetchModelMetrics(tenantId) {
    const headers = { 'X-Tenant-Id': tenantId }
    const [brain, cache, knowledge] = await Promise.all([
      jsonRequest(`${nlpUrl}/api/nlp/brain/health`, { headers }).catch(() => null),
      jsonRequest(`${nlpUrl}/api/nlp/brain/cache/stats`, { headers }).catch(() => null),
      jsonRequest(`${bodyUrl}/api/body/knowledge/stats`, { headers, timeoutMs: RETRIEVE_TIMEOUT_MS }).catch(() => null),
    ])
    if (!brain) {
      return {
        available: false,
        stale: lastModelMetrics !== null,
        model_calls: lastModelMetrics ? lastModelMetrics.model_calls : [],
        model_runtime: lastModelMetrics ? lastModelMetrics.model_runtime : [],
        note: lastModelMetrics ? '指标断流：展示最近一次点位（等待校准）' : '大脑层不可用：模型指标等待校准',
      }
    }
    const llmAvailable = Boolean(brain.llm && brain.llm.available)
    const cacheStats = (cache && cache.stats) || null
    const bodyKnowledge = (knowledge && knowledge.knowledge) || null
    const chunkCount = (bodyKnowledge && bodyKnowledge.chunks) ?? 0
    const stats = (brain.llm && brain.llm.stats) || {}
    const latency = brain.latency || {}
    const point = {
      t: nowTime(),
      calls: Number(stats.calls || 0),
      tokens: Number(stats.completion_tokens || 0),
      prompt_tokens: Number(stats.prompt_tokens || 0),
      p95_ms: Number(latency.p95_ms || 0),
      p99_ms: Number(latency.p99_ms || 0),
      cost_cny: null,
      cost_basis: 'not_metered',
    }
    const modelRuntime = modelRuntimeNodes(brain, cache, knowledge, cacheStats, chunkCount, llmAvailable)
    // 时序只保留最近 30 个点位（滚动窗口），断流时照样能回传最近点位
    const previous = lastModelMetrics ? lastModelMetrics.model_calls : []
    lastModelMetrics = { model_calls: [...previous, point].slice(-30), model_runtime: modelRuntime }
    return {
      available: true,
      stale: false,
      model_calls: lastModelMetrics.model_calls,
      model_runtime: modelRuntime,
      note: 'model_calls 为滚动窗口（最近 30 点）；成本未接入计量，cost_cny 恒为 null',
    }
  }

  /**
   * R-C04 大脑视图聚合：活跃会话 / 意图分布 / 模型指标 / 决策链索引。
   *
   * 三个上游（session / nlp / body）任一不可用都**降级可见**：
   * 对应分片标记 `available=false` 并给出 reason，绝不静默填 0 冒充正常。
   */
  async function handleBrain(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const [brain, cache, knowledge] = await Promise.all([
      jsonRequest(`${nlpUrl}/api/nlp/brain/health`, { headers: { 'X-Tenant-Id': tenantId } }).catch(() => null),
      jsonRequest(`${nlpUrl}/api/nlp/brain/cache/stats`, { headers: { 'X-Tenant-Id': tenantId } }).catch(() => null),
      jsonRequest(`${bodyUrl}/api/body/knowledge/stats`, { headers: { 'X-Tenant-Id': tenantId } }).catch(() => null),
    ])

    const llmAvailable = Boolean(brain && brain.llm && brain.llm.available)
    const cacheStats = (cache && cache.stats) || (brain && brain.semantic_cache && brain.semantic_cache.stats) || null
    // 体层 stats 的**知识量是嵌套的**：`/api/body/knowledge/stats` 返回
    // `{knowledge:{documents,chunks,...}, retrieval:{...}}`，顶层并没有 chunks。
    // 直接取 `knowledge.chunks` 会恒为 0 —— 那是"静默把未知写成 0"，与降级可见原则冲突。
    // 这里按嵌套口径取，取不到再退回 retrieval 分片统计。
    const bodyKnowledge = (knowledge && knowledge.knowledge) || null
    const chunkCount = (bodyKnowledge && bodyKnowledge.chunks) ??
      (knowledge && knowledge.retrieval && knowledge.retrieval.chunks) ?? 0
    const docCount = (bodyKnowledge && bodyKnowledge.documents) ??
      (knowledge && knowledge.retrieval && knowledge.retrieval.documents) ?? 0
    const modelRuntime = modelRuntimeNodes(brain, cache, knowledge, cacheStats, chunkCount, llmAvailable)

    // 会话维度：活跃会话 / 意图分布读 session-manager 的实时汇总（不再只回一个 URL）
    const sessions = await jsonRequest(`${sessionUrl}/api/session/stats`, {
      headers: { 'X-Tenant-Id': tenantId },
    }).catch(() => null)

    return send(req, res, 200, {
      data: {
        source: 'wp-bff',
        tenant_id: tenantId,
        checked_at: nowTime(),
        available: Boolean(brain || cache || knowledge),
        brain_available: Boolean(brain),
        cache_available: Boolean(cache),
        knowledge_available: Boolean(knowledge),
        degraded: !llmAvailable || !cache || !knowledge,
        degraded_reasons: [
          ...(llmAvailable ? [] : ['llm_unavailable']),
          ...(cache ? [] : ['semantic_cache_unavailable']),
          ...(knowledge ? [] : ['retrieval_unavailable']),
        ],
        llm: (brain && brain.llm) || null,
        semantic_cache: cache || (brain && brain.semantic_cache) || null,
        retrieval: knowledge
          ? { backend: 'body-service', degraded: false, chunks: chunkCount, documents: docCount }
          : null,
        model_runtime: modelRuntime,
        sessions: sessions
          ? { available: true, ...sessions }
          : {
              available: false,
              active_sessions: null,
              by_intent: {},
              reason: '会话服务不可用：活跃会话与意图分布无法读取',
              source: `${sessionUrl}/api/session`,
            },
      },
    })
  }

  /**
   * D5/X4 决策链回放：读 **IN3 AuditLog** 真实记录（nlp-service 落 PG）。
   *
   * 语义严格区分三种情况（对齐 `docs/异常流程归纳.md` 的 planned 三态）：
   *   - 大脑层整体不可用 → 200 + `available:false`（**降级**，不是故障也不是未实现）
   *   - 大脑层可用但**查无此 decision** → **404 AGENT_NOT_FOUND**（未找到 ≠ 空链）
   *   - 查到 → 200 + 完整链（含 compliance / attribution / confidence 三卡）
   *
   * 历史教训：上一版这里恒返 `200 + available:true + chain:[]`，注释却写着"未命中返回 404"——
   * 那是把「查不到」伪装成「回放成功」，前端永远无法区分。本版按注释的口径落地。
   */
  async function handleBrainDecision(req, res, decisionId) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    if (!decisionId) return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'decision_id must not be blank')
    let record = null
    let upstreamError = null
    try {
      record = await jsonRequest(`${nlpUrl}/api/nlp/brain/decision/${encodeURIComponent(decisionId)}`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
    } catch (error) {
      upstreamError = error
    }
    if (!record) {
      if (upstreamError && Number(upstreamError.status) === 404) {
        // 大脑层在线但审计里没有这条决策（含跨租户访问）—— 未找到就是 404
        return fail(req, res, 404, 'AGENT_NOT_FOUND', `决策链不存在：${decisionId}`,
          { decision_id: decisionId, source: 'in3-auditlog' })
      }
      return send(req, res, 200, {
        data: {
          available: false,
          decision_id: decisionId,
          reason: '大脑层不可用：无法查询决策链（nlp-service /brain 未启动或返回异常）',
          source: 'in3-auditlog',
        },
      })
    }
    return send(req, res, 200, { data: { available: true, source: 'in3-auditlog', ...record } })
  }

  /** D5 决策索引：最近决策清单（供沙盘选择与大脑视图展示，数据来自 AuditLog）。 */
  async function handleBrainDecisions(req, res, limit) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const payload = await jsonRequest(`${nlpUrl}/api/nlp/brain/decisions?limit=${limit}`, {
      headers: { 'X-Tenant-Id': tenantId },
    }).catch(() => null)
    if (!payload) {
      return send(req, res, 200, {
        data: {
          available: false,
          reason: '大脑层不可用：无法读取决策索引（nlp-service /brain 未启动或返回异常）',
          items: [],
        },
      })
    }
    return send(req, res, 200, { data: { available: true, ...payload } })
  }

  // ─────────── IN-02 记忆图谱（代理 nlp-service）───────────

  async function handleMemoryIngest(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const payload = await readJsonBody(req)
    if (!payload || typeof payload !== 'object' || !String(payload.text || '').trim()) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'text must not be blank')
    }
    const result = await jsonRequest(`${nlpUrl}/api/nlp/brain/memory/ingest`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId },
      body: { text: String(payload.text), tenant_id: tenantId },
      // 记忆抽取走 LLM 级联，必须用生成档；落到读型默认会把「慢」说成「大脑层不可用」
      timeoutMs: GENERATE_TIMEOUT_MS,
    }).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: { available: false, reason: '大脑层不可用：记忆图谱抽取未执行（nlp-service 未启动）' },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  async function handleMemoryCompress(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const payload = await readJsonBody(req)
    if (!payload || typeof payload !== 'object' || !String(payload.text || '').trim()) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'text must not be blank')
    }
    const result = await jsonRequest(`${nlpUrl}/api/nlp/brain/memory/compress`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId },
      body: { text: String(payload.text), tenant_id: tenantId, depth: Number(payload.depth || 2) },
      timeoutMs: GENERATE_TIMEOUT_MS,
    }).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: { available: false, applied: false, reason: '大脑层不可用：上下文压缩未执行（nlp-service 未启动）' },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  async function handleMemorySubgraph(req, res, params) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const root = String(params.get('root') || '').trim()
    if (!root) return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'root must not be blank')
    const query = `?root=${encodeURIComponent(root)}&tenant_id=${encodeURIComponent(tenantId)}&depth=${Number(params.get('depth') || 2)}`
    const result = await jsonRequest(`${nlpUrl}/api/nlp/brain/memory/subgraph${query}`, {
      headers: { 'X-Tenant-Id': tenantId },
    }).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: { available: false, root, entities: [], relations: [], reason: '大脑层不可用：子图未加载' },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  async function handleMemoryStats(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const result = await jsonRequest(
      `${nlpUrl}/api/nlp/brain/memory/stats?tenant_id=${encodeURIComponent(tenantId)}`,
      { headers: { 'X-Tenant-Id': tenantId } },
    ).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: { available: false, reason: '大脑层不可用：记忆图谱统计不可读' },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  // ─────────── 会话链路（代理 session-manager，R4-01/02 + R-C04）───────────

  /**
   * 会话真相在 session-manager（Redis + FSM）。BFF **只做代理**，不落第二份会话状态 ——
   * 上一版这里只把 URL 当字符串丢给前端，导致 FSM/持久化在产品链路上无人调用。
   */
  async function handleSessionCreate(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const result = await jsonRequest(`${sessionUrl}/api/session`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId },
      body: {},
    }).catch(() => null)
    if (!result || !result.session_id) {
      return send(req, res, 200, {
        data: {
          available: false,
          reason: '会话服务不可用：无法创建会话（session-manager 未启动或返回异常）',
          session_url: sessionUrl,
        },
      })
    }
    return send(req, res, 200, { data: { available: true, session_id: result.session_id, ...result } })
  }

  /** 会话问答：走 FSM → 意图 → 大脑层（含来源/缺口/决策链/降级标注）。 */
  async function handleSessionAsk(req, res, sessionId) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const payload = await readJsonBody(req)
    if (!payload || typeof payload !== 'object') {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'request body must be a JSON object')
    }
    const question = String(payload.question || '').trim()
    if (!question) return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'question must not be blank')
    let result = null
    let upstreamError = null
    try {
      result = await jsonRequest(`${sessionUrl}/api/session/${encodeURIComponent(sessionId)}/ask`, {
        method: 'POST',
        headers: { 'X-Tenant-Id': tenantId },
        body: { question },
        // 会话问答内部转调 nlp /brain/ask（LLM 级联），下游最坏是 35s 总预算 —— 用专用 ASK 档（TB-09）
        timeoutMs: ASK_TIMEOUT_MS,
      })
    } catch (error) {
      upstreamError = error
    }
    if (!result) {
      if (upstreamError && Number(upstreamError.status) === 404) {
        return fail(req, res, 404, 'AGENT_NOT_FOUND', `会话不存在：${sessionId}`)
      }
      if (upstreamError && Number(upstreamError.status) === 409) {
        return fail(req, res, 409, 'AGENT_CONFLICT', '会话已关闭，无法继续追问')
      }
      return send(req, res, 200, {
        data: {
          available: false,
          session_id: sessionId,
          question,
          reason: '会话服务不可用：session-manager 未启动或返回异常',
          degraded_reasons: ['session_unavailable'],
        },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  async function handleSessionContext(req, res, sessionId, turns) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const result = await jsonRequest(
      `${sessionUrl}/api/session/${encodeURIComponent(sessionId)}/context?turns=${turns}`,
      { headers: { 'X-Tenant-Id': tenantId } },
    ).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: {
          available: false,
          session_id: sessionId,
          messages: [],
          reason: '会话服务不可用：无法读取多轮上下文',
        },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  async function handleSessionClose(req, res, sessionId) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const result = await jsonRequest(`${sessionUrl}/api/session/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
      headers: { 'X-Tenant-Id': tenantId },
    }).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: { available: false, session_id: sessionId, reason: '会话服务不可用：无法关闭会话' },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  /**
   * R-C04 活跃会话 / 意图分布：数据来自 session-manager 的实时 SCAN 汇总。
   *
   * **不静默填 0** —— session-manager 不可用时回 `available:false` + reason，
   * 由前端标注"会话真相不可读"，而不是把"读不到"画成"0 个活跃会话"。
   */
  async function handleSessionStats(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const result = await jsonRequest(`${sessionUrl}/api/session/stats`, {
      headers: { 'X-Tenant-Id': tenantId },
    }).catch(() => null)
    if (!result) {
      return send(req, res, 200, {
        data: {
          available: false,
          reason: '会话服务不可用：活跃会话与意图分布无法读取（session-manager 未启动）',
          active_sessions: null,
          by_intent: {},
          session_url: sessionUrl,
        },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  /** R4-06 问答代理：前端 → BFF → nlp 大脑层（带降级可见）。 */
  async function handleBrainAsk(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const payload = await readJsonBody(req)
    if (!payload || typeof payload !== 'object') {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'request body must be a JSON object')
    }
    const question = String(payload.question || '').trim()
    if (!question) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'question must not be blank')
    }
    const result = await jsonRequest(`${nlpUrl}/api/nlp/brain/ask`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId },
      body: {
        question,
        session_id: String(payload.session_id || ''),
        tenant_id: tenantId,
        intent: String(payload.intent || ''),
        context: Array.isArray(payload.context) ? payload.context : [],
        use_cache: payload.use_cache !== false,
      },
      // 大脑问答是 LLM 生成型链路（级联最坏 22s），必须用生成档
      timeoutMs: GENERATE_TIMEOUT_MS,
    }).catch(() => null)
    if (!result || typeof result !== 'object') {
      return send(req, res, 200, {
        data: {
          available: false,
          question,
          reason: '大脑层不可用：nlp-service /api/nlp/brain/ask 未启动或返回异常',
        },
      })
    }
    return send(req, res, 200, { data: { available: true, ...result } })
  }

  async function handleKnowledge(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const stats = await jsonRequest(`${bodyUrl}/api/body/knowledge/stats`, {
      headers: { 'X-Tenant-Id': tenantId },
    })
    if (!stats || typeof stats !== 'object') {
      return send(req, res, 200, {
        data: {
          available: false,
          tenant_id: tenantId,
          checked_at: nowTime(),
          body_url: bodyUrl,
          reason: '体层不可用：body-service 未启动或接口异常（知识量/检索指标无法上报）',
          gaps: ['knowledge', 'retrieval', 'storage', 'embedding', 'reranker', 'vector_store'],
        },
      })
    }
    send(req, res, 200, {
      data: {
        available: true,
        checked_at: nowTime(),
        body_url: bodyUrl,
        ...stats,
        note: '检索 P99/命中率口径为「最近 500 次采样」，非全量历史',
      },
    })
  }

  /**
   * 检索测试（R-C03 检索测试面板 + 命中片段高亮）
   *
   * <p>请求：{@code { query, top_k?, use_cache? }}；改造前为 Phase 2 的本地全文匹配，
   * 现直连体层语义检索（缓存优先 → 向量召回 → 重排），返回高亮词与截窗片段。
   */
  async function handleKnowledgeSearch(req, res) {
    let payload
    try {
      payload = await readJsonBody(req)
    } catch (error) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', String(error.message || error))
    }
    const query = String(payload.query || '').trim()
    if (!query) return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'query 不能为空')
    const topK = Math.min(Math.max(Number(payload.top_k) || 5, 1), 20)
    const tenantId = String(req.headers['x-tenant-id'] || payload.tenant_id || 'default')
    const startedAt = Date.now()
    const hits = await jsonRequest(`${bodyUrl}/api/body/retrieve`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId },
      body: { query, top_k: topK, use_cache: payload.use_cache !== false },
      timeoutMs: RETRIEVE_TIMEOUT_MS,
    })
    const latencyMs = Date.now() - startedAt
    if (!Array.isArray(hits)) {
      return send(req, res, 200, {
        data: {
          available: false,
          tenant_id: tenantId,
          query,
          top_k: topK,
          hits: [],
          latency_ms: latencyMs,
          checked_at: nowTime(),
          reason: '体层检索不可用：body-service 未启动或接口异常',
        },
      })
    }
    const terms = queryTerms(query)
    const items = hits.map((hit, index) => {
      const { snippet, matched_terms } = buildSnippet(hit.content, terms)
      return {
        rank: index + 1,
        chunk_id: hit.chunk_id,
        doc_id: hit.doc_id,
        title: hit.title,
        heading: hit.heading,
        chunk_index: hit.chunk_index,
        score: hit.score,
        rerank_score: hit.rerank_score,
        source: hit.source,
        ingest_time_iso: hit.ingest_time_iso,
        snippet,
        matched_terms,
      }
    })
    send(req, res, 200, {
      data: {
        available: true,
        tenant_id: tenantId,
        query,
        top_k: topK,
        parsed_terms: terms,
        hits: items,
        hit_count: items.length,
        latency_ms: latencyMs,
        checked_at: nowTime(),
        highlight_note: 'matched_terms 由 BFF 计算，前端据此高亮 snippet',
      },
    })
  }

  /**
   * 知识入库（R3-09 知识管理 API 的**写路径** · 工作平台侧唯一入口）
   *
   * <p>代理体层 {@code POST /api/body/knowledge}（单篇）与 {@code POST /api/body/knowledge/batch}（批量导入）。
   * 改造前躯体视图只有"读 + 检索"，文档只能绕过工作平台直连 8083 入库——
   * 演示 §7「上传文档 → 提问 → 高亮命中」链路因此断裂，本端点即补上该缺口。
   *
   * <p>请求体：{@code { doc_id?, title, content, source?, format? }} 单篇；
   * {@code { documents: [...], source? }} 批量。
   * {@code format} 支持 {@code auto/md/text/html}（体层 {@code DocumentParser}），
   * pdf/docx 由体层显式拒绝并回传原因（DEBT-013），BFF 原样透传不吞错。
   */
  async function handleKnowledgeIngest(req, res) {
    // 写路径鉴权（与 /middleware/{key}/start|stop 同级）：来源白名单 + 控制令牌。
    // 注意 `/knowledge` 的 GET 是只读、按设计免令牌；但同一路径的 POST 会真实改动
    // 知识库（写入向量与元数据），若沿用只读豁免就形成"无鉴权写入后门"。
    // 开发环境下 Vite 代理对 /api/wp/* 统一注入令牌，因此前端无需额外处理。
    const auth = authorizeControl(req)
    if (!auth.ok) {
      audit('REJECT_AUTH', 'knowledge', `ingest ${auth.message}`)
      return fail(req, res, auth.status, auth.code, auth.message, auth.details)
    }
    let payload
    try {
      payload = await readJsonBody(req, 2 * 1024 * 1024)
    } catch (error) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', String(error.message || error))
    }
    const tenantId = String(req.headers['x-tenant-id'] || payload.tenant_id || 'default')
    const isBatch = Array.isArray(payload.documents)
    if (isBatch && payload.documents.length === 0) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'documents 不能为空数组', { field: 'documents' })
    }
    if (!isBatch && !String(payload.content ?? '').trim()) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'content 不能为空', { field: 'content' })
    }
    const target = isBatch ? '/api/body/knowledge/batch' : '/api/body/knowledge'
    const outbound = isBatch
      ? { documents: payload.documents.map(pickDocumentFields), source: payload.source }
      : pickDocumentFields(payload)
    const startedAt = Date.now()
    const result = await jsonRequest(`${bodyUrl}${target}`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId },
      body: outbound,
      // 入库要跑嵌入（下游读超时 30s）。原为写死的 30000 —— 与下游最坏耗时相等、
      // 余量为 0，且魔法数字不受登记表锚点约束，故改用命名档位（TB-04）
      timeoutMs: WRITE_TIMEOUT_MS,
    })
    const latencyMs = Date.now() - startedAt
    if (!result || typeof result !== 'object') {
      return send(req, res, 200, {
        data: {
          available: false,
          tenant_id: tenantId,
          mode: isBatch ? 'batch' : 'single',
          latency_ms: latencyMs,
          checked_at: nowTime(),
          reason: '体层不可用：body-service 未启动或入库接口异常',
        },
      })
    }
    // 体层错误经统一错误码返回（AGENT_*），不得当作入库成功
    if (result.code) {
      return send(req, res, 200, {
        data: {
          available: false,
          tenant_id: tenantId,
          mode: isBatch ? 'batch' : 'single',
          latency_ms: latencyMs,
          checked_at: nowTime(),
          reason: `入库被体层拒绝：${result.message || result.code}`,
          error_code: result.code,
        },
      })
    }
    send(req, res, 200, {
      data: {
        available: true,
        tenant_id: tenantId,
        mode: isBatch ? 'batch' : 'single',
        latency_ms: latencyMs,
        checked_at: nowTime(),
        ...result,
      },
    })
  }

  function pickDocumentFields(source) {
    const document = {}
    for (const key of ['doc_id', 'title', 'content', 'source', 'format']) {
      if (source[key] !== undefined && source[key] !== null) document[key] = source[key]
    }
    return document
  }

  // ─────────── R-C05(预) 执行视图：四肢层工具（代理 tool-executor）───────────
  //
  // 语义约定（与大脑层端点保持同一口径）：
  //   - 四肢层不可达        → HTTP 200 + `available:false` + reason + `reason_code`（**降级**，不是故障）
  //   - 到达工具层但执行失败 → `available:true` + `success:false` + `error_code`
  //     （守卫拦截 / 参数非法 / 沙箱拒绝 / 超时）—— 这是**已落审计的真实调用结果**，
  //     与"服务不可用"是两回事，不能混为一谈地丢掉。
  //   - 任何情况下都**不用 0 / 空数组冒充**「没有工具」「零次调用」。
  //   - **「慢」不得写成「没有」**：取不到分片时按传输层原因细分
  //     （`*_timeout` / `*_missing` / `*_unavailable`），并附 `partial_note` 说明。
  //     历史教训见 §httpRequestJsonMeta 注释。

  /** 执行视图待取到的数据域，四肢层不可用时透传给前端做降级标注 */
  const TOOL_GAPS = ['tools', 'registry', 'metrics', 'sandbox', 'audit']

  /**
   * 工具域子请求超时预算。默认 5s 对齐其余域的 `httpRequestJson` 默认值；
   * `/api/tool/sandbox` 需要探测 Docker 守护进程与镜像（冷探测在 Windows 上实测 5.7s），
   * 因此单独给更宽的预算 —— 预算是**按域的真实最坏耗时**定的，不是一刀切。
   */
  const TOOL_SUBREQUEST_TIMEOUT_MS = Number(process.env.WP_BFF_TOOL_TIMEOUT_MS || 5000)
  const TOOL_SANDBOX_TIMEOUT_MS = Number(process.env.WP_BFF_TOOL_SANDBOX_TIMEOUT_MS || 9000)

  /** 分片 → 通用降级码（原因细分在此后缀上做替换，保持既有契约词根不变） */
  const TOOL_SLICE_CODES = {
    registry: 'registry_unavailable',
    metrics: 'metrics_unavailable',
    sandbox: 'sandbox_unavailable',
    audit_stats: 'audit_stats_unavailable',
    audit: 'audit_unavailable',
  }

  /** 传输层原因 → 人话（用于 reason 文案，避免把「慢」说成「没有」） */
  const PROBE_REASON_TEXT = {
    timeout: '读取超时，对端未在预算内返回（服务可能在冷启动 / 负载过高）—— 这是「慢」，不是「没有」',
    endpoint_missing: '端点不存在（HTTP 404），对端可能是旧版本服务，缺少本端点',
    http_error: '对端返回非 2xx 状态码',
    bad_json: '对端返回不是合法 JSON',
    unreachable: '对端未启动或连接被拒绝',
  }

  /** 失败 outcome → 分片降级码；成功返回 null */
  function sliceDegradeCode(slice, outcome) {
    if (outcome.ok) return null
    const base = TOOL_SLICE_CODES[slice]
    if (outcome.reason === 'timeout') return base.replace('_unavailable', '_timeout')
    if (outcome.reason === 'endpoint_missing') return base.replace('_unavailable', '_missing')
    return base
  }

  function toolDown(reason, extra = {}) {
    return { available: false, reason, tool_url: toolUrl, gaps: TOOL_GAPS, ...extra }
  }

  /** 四肢层整体不可达时的统一信封：把传输层原因如实翻成人话 */
  function toolDownFromProbe(pathLabel, outcome, extra = {}) {
    const detail = PROBE_REASON_TEXT[outcome.reason] || PROBE_REASON_TEXT.unreachable
    return toolDown(`四肢层不可用：${pathLabel} ${detail}（工具调用记录与沙箱状态无法上报）`, {
      reason_code: outcome.reason,
      ...extra,
    })
  }

  /** 执行视图聚合（R-C05 预）：一次取齐工具清单 / 注册表 / 指标 / 沙箱 / 审计。 */
  async function handleTools(req, res) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const headers = { 'X-Tenant-Id': tenantId }
    const call = (path, timeoutMs) =>
      jsonRequestMeta(`${toolUrl}${path}`, { headers, timeoutMs }).catch(() => ({ ok: false, data: null, reason: 'unreachable' }))
    const [list, registry, metrics, sandbox, auditStats, auditPage] = await Promise.all([
      call('/api/tool/list', TOOL_SUBREQUEST_TIMEOUT_MS),
      call('/api/tool/registry', TOOL_SUBREQUEST_TIMEOUT_MS),
      call('/api/tool/metrics', TOOL_SUBREQUEST_TIMEOUT_MS),
      call('/api/tool/sandbox', TOOL_SANDBOX_TIMEOUT_MS),
      call('/api/tool/audit/stats', TOOL_SUBREQUEST_TIMEOUT_MS),
      call('/api/tool/audit?limit=20', TOOL_SUBREQUEST_TIMEOUT_MS),
    ])
    if (!list.ok || typeof list.data !== 'object' || list.data === null) {
      return send(req, res, 200, {
        data: toolDownFromProbe('工具清单', list, {
          tenant_id: tenantId,
          checked_at: nowTime(),
          probe_budget_ms: { default: TOOL_SUBREQUEST_TIMEOUT_MS, sandbox: TOOL_SANDBOX_TIMEOUT_MS },
        }),
      })
    }
    const slices = { registry, metrics, sandbox, audit_stats: auditStats, audit: auditPage }
    const partialReasons = Object.entries(slices)
      .map(([slice, outcome]) => sliceDegradeCode(slice, outcome))
      .filter(Boolean)
    const timedOut = partialReasons.filter(code => code.endsWith('_timeout'))
    const missing = partialReasons.filter(code => code.endsWith('_missing'))
    const note = []
    if (timedOut.length) {
      note.push(`其中 ${timedOut.length} 项为**超时**（${timedOut.join(' / ')}）：服务被调用时未在预算内返回，不代表该能力缺失`)
    }
    if (missing.length) {
      note.push(`其中 ${missing.length} 项端点不存在（${missing.join(' / ')}）：对端版本可能旧于本 BFF`)
    }
    send(req, res, 200, {
      data: {
        available: true,
        tenant_id: tenantId,
        checked_at: nowTime(),
        tool_url: toolUrl,
        tools: Array.isArray(list.data.tools) ? list.data.tools : [],
        total: Number(list.data.total || 0),
        registry_backend: list.data.registry_backend || 'unknown',
        // 分片失败一律 null —— 不给 {} 让前端渲染成 0
        registry: registry.ok ? registry.data : null,
        metrics: metrics.ok ? metrics.data : null,
        sandbox: sandbox.ok ? sandbox.data : null,
        audit_stats: auditStats.ok ? auditStats.data : null,
        audit: auditPage.ok ? auditPage.data : null,
        // 分片级降级：整体可用但个别分片取不到时如实标注，不让前端把"缺"读成"零"
        partial: partialReasons.length > 0,
        partial_reasons: partialReasons,
        partial_note: note.length ? note.join('；') : null,
        probe_budget_ms: { default: TOOL_SUBREQUEST_TIMEOUT_MS, sandbox: TOOL_SANDBOX_TIMEOUT_MS },
        note: 'metrics.success_rate 口径为「最近窗口采样」，非全量历史；audit 为最近 20 条',
      },
    })
  }

  /** 通用四肢层 GET 代理：不可用时统一降级信封（含传输层原因细分）。 */
  async function proxyTool(req, res, path, pick, timeoutMs = TOOL_SUBREQUEST_TIMEOUT_MS) {
    const tenantId = String(req.headers['x-tenant-id'] || 'default')
    const outcome = await jsonRequestMeta(`${toolUrl}${path}`, {
      headers: { 'X-Tenant-Id': tenantId },
      timeoutMs,
    }).catch(() => ({ ok: false, data: null, reason: 'unreachable' }))
    if (!outcome.ok || !outcome.data || typeof outcome.data !== 'object') {
      return send(req, res, 200, {
        data: toolDownFromProbe(path, outcome, { tenant_id: tenantId, checked_at: nowTime() }),
      })
    }
    return send(req, res, 200, { data: { available: true, tenant_id: tenantId, checked_at: nowTime(), ...pick(outcome.data) } })
  }

  const handleToolsRegistry = (req, res) =>
    proxyTool(req, res, '/api/tool/registry', p => ({
      summary: p.summary || null,
      change_log: Array.isArray(p.change_log) ? p.change_log : [],
      semver_policy: p.semver_policy,
      deprecation_policy: p.deprecation_policy,
    }))

  const handleToolsAudit = (req, res, limit) =>
    proxyTool(req, res, `/api/tool/audit?limit=${limit}`, p => ({
      items: Array.isArray(p.items) ? p.items : [],
      total: Number(p.total || 0),
      audit_backend: p.audit_backend,
      degraded: Boolean(p.degraded),
    }))

  const handleToolsAuditStats = (req, res) =>
    proxyTool(req, res, '/api/tool/audit/stats', p => ({ audit: p.audit || null, kafka: p.kafka || null }))

  const handleToolsMetrics = (req, res) =>
    proxyTool(req, res, '/api/tool/metrics', p => ({
      metrics: p.metrics || null,
      circuit_breakers: Array.isArray(p.circuit_breakers) ? p.circuit_breakers : [],
    }))

  const handleToolsSandbox = (req, res) =>
    // 沙箱端点要走 Docker 守护进程 + 镜像探测，预算单独放宽（见 TOOL_SANDBOX_TIMEOUT_MS）
    proxyTool(req, res, '/api/tool/sandbox', p => ({ sandbox: p }), TOOL_SANDBOX_TIMEOUT_MS)

  const handleToolDetail = (req, res, name) =>
    proxyTool(req, res, `/api/tool/${encodeURIComponent(name)}`, p => ({ tool: p }))

  const handleToolImpact = (req, res, name) =>
    proxyTool(req, res, `/api/tool/${encodeURIComponent(name)}/impact`, p => ({ impact: p }))

  /**
   * 工具执行（写路径）：来源白名单 + 控制令牌，与 `/middleware/{key}/start`、
   * `/knowledge` 的 POST 同级。执行会真实产生副作用（网络请求 / 沙箱进程 / 审计落库），
   * 未鉴权即暴露等于给出一条"免令牌触发工具"的后门。
   */
  async function handleToolExecute(req, res) {
    const auth = authorizeControl(req)
    if (!auth.ok) {
      audit('REJECT_AUTH', 'tools', `execute ${auth.message}`)
      return fail(req, res, auth.status, auth.code, auth.message, auth.details)
    }
    let payload
    try {
      payload = await readJsonBody(req, 256 * 1024)
    } catch (error) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', String(error.message || error))
    }
    const toolName = String(payload.tool_name || '').trim()
    if (!toolName) {
      return fail(req, res, 400, 'AGENT_BAD_REQUEST', 'tool_name 不能为空', { field: 'tool_name' })
    }
    const tenantId = String(req.headers['x-tenant-id'] || payload.tenant_id || 'default')
    const requestedBy = String(req.headers['x-requested-by'] || 'work-platform')
    const args = (payload.arguments && typeof payload.arguments === 'object') ? payload.arguments : {}
    const startedAt = Date.now()
    const result = await jsonRequest(`${toolUrl}/api/tool/execute`, {
      method: 'POST',
      headers: { 'X-Tenant-Id': tenantId, 'X-Requested-By': requestedBy },
      body: { tool_name: toolName, arguments: args, call_id: payload.call_id },
      timeoutMs: EXECUTE_TIMEOUT_MS,
    }).catch(() => null)
    const bffLatencyMs = Date.now() - startedAt
    if (!result || typeof result !== 'object') {
      return send(req, res, 200, {
        data: {
          available: false,
          tenant_id: tenantId,
          tool_name: toolName,
          bff_latency_ms: bffLatencyMs,
          checked_at: nowTime(),
          reason: '四肢层不可用：tool-executor 未启动或执行接口异常，本次调用未生效',
        },
      })
    }
    // 统一错误信封（AGENT_*）：工具被真实拒绝（守卫/参数/沙箱/超时），已落审计 ——
    // 回 available:true + success:false，让执行视图把它当成一条真实调用记录渲染。
    if (result.code) {
      return send(req, res, 200, {
        data: {
          available: true,
          tenant_id: tenantId,
          tool_name: toolName,
          success: false,
          error_code: String(result.code),
          error_message: String(result.message || ''),
          details: result.details || {},
          audit_id: (result.details && result.details.audit_id) || null,
          bff_latency_ms: bffLatencyMs,
          checked_at: nowTime(),
        },
      })
    }
    audit('TOOL_EXEC', toolName, `tenant=${tenantId} success=${result.success !== false} bff=${bffLatencyMs}ms`)
    return send(req, res, 200, {
      data: { available: true, tenant_id: tenantId, bff_latency_ms: bffLatencyMs, checked_at: nowTime(), ...result },
    })
  }

  function handleHealthz(req, res) {
    send(req, res, 200, {
      data: {
        status: 'up',
        service: 'wp-bff',
        checked_at: nowTime(),
        control: {
          token_source: typeof controlToken === 'string' ? 'injected' : controlToken.source,
          token_file: typeof controlToken === 'string' ? null : controlToken.tokenFile,
          allowed_origins: [...allowedOrigins],
          guard: 'origin-whitelist + control-token',
        },
        middleware_keys: Object.keys(middleware),
      },
    })
  }

  return http.createServer((req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${req.socket.localPort || DEFAULT_PORT}`)
    const match = url.pathname.match(/^\/api\/wp\/middleware\/([a-z0-9-]+)\/(start|stop)$/)
    if (req.method === 'OPTIONS') {
      const verdict = originVerdict(req, allowedOrigins)
      if (verdict === false) {
        return fail(req, res, 403, 'AGENT_FORBIDDEN', '请求来源不在白名单内（Origin/Referer 校验失败）',
          { guard: 'origin-whitelist' })
      }
      return send(req, res, 204, {})
    }
    if (req.method === 'GET' && url.pathname === '/api/wp/healthz') return handleHealthz(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/overview') return handleOverview(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/middleware') return handleMiddleware(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/tracing') return handleTracing(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/knowledge') return handleKnowledge(req, res)
    if (req.method === 'POST' && url.pathname === '/api/wp/knowledge') return handleKnowledgeIngest(req, res)
    if (req.method === 'POST' && url.pathname === '/api/wp/knowledge/search') return handleKnowledgeSearch(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/brain') return handleBrain(req, res)
    if (req.method === 'POST' && url.pathname === '/api/wp/brain/ask') return handleBrainAsk(req, res)
    // /brain/decisions 必须排在 /brain/{decision_id} 之前：后者是宽松正则，会把 "decisions" 也吃掉
    if (req.method === 'GET' && url.pathname === '/api/wp/brain/decisions') {
      return handleBrainDecisions(req, res, Number(url.searchParams.get('limit') || 10))
    }
    if (req.method === 'POST' && url.pathname === '/api/wp/brain/memory/ingest') return handleMemoryIngest(req, res)
    if (req.method === 'POST' && url.pathname === '/api/wp/brain/memory/compress') return handleMemoryCompress(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/brain/memory/subgraph') return handleMemorySubgraph(req, res, url.searchParams)
    if (req.method === 'GET' && url.pathname === '/api/wp/brain/memory/stats') return handleMemoryStats(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/session/stats') return handleSessionStats(req, res)
    if (req.method === 'POST' && url.pathname === '/api/wp/session') return handleSessionCreate(req, res)
    // R-C05(预) 执行视图。固定子路径必须排在 /tools/{name} 之前，否则会被宽松参数正则吃掉
    if (req.method === 'GET' && url.pathname === '/api/wp/tools') return handleTools(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/tools/registry') return handleToolsRegistry(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/tools/audit/stats') return handleToolsAuditStats(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/tools/audit') {
      return handleToolsAudit(req, res, Number(url.searchParams.get('limit') || 50))
    }
    if (req.method === 'GET' && url.pathname === '/api/wp/tools/metrics') return handleToolsMetrics(req, res)
    if (req.method === 'GET' && url.pathname === '/api/wp/tools/sandbox') return handleToolsSandbox(req, res)
    if (req.method === 'POST' && url.pathname === '/api/wp/tools/execute') return handleToolExecute(req, res)
    const toolImpact = url.pathname.match(/^\/api\/wp\/tools\/([a-z0-9-]{1,64})\/impact$/)
    if (req.method === 'GET' && toolImpact) return handleToolImpact(req, res, toolImpact[1])
    const toolOne = url.pathname.match(/^\/api\/wp\/tools\/([a-z0-9-]{1,64})$/)
    if (req.method === 'GET' && toolOne && !TOOL_RESERVED_SEGMENTS.has(toolOne[1])) {
      return handleToolDetail(req, res, toolOne[1])
    }
    const sessionAsk = url.pathname.match(/^\/api\/wp\/session\/([a-z0-9-]{8,64})\/ask$/)
    if (req.method === 'POST' && sessionAsk) return handleSessionAsk(req, res, sessionAsk[1])
    const sessionCtx = url.pathname.match(/^\/api\/wp\/session\/([a-z0-9-]{8,64})\/context$/)
    if (req.method === 'GET' && sessionCtx) {
      return handleSessionContext(req, res, sessionCtx[1], Number(url.searchParams.get('turns') || 10))
    }
    const sessionOne = url.pathname.match(/^\/api\/wp\/session\/([a-z0-9-]{8,64})$/)
    if (req.method === 'DELETE' && sessionOne) return handleSessionClose(req, res, sessionOne[1])
    const decisionMatch = url.pathname.match(/^\/api\/wp\/brain\/([a-z0-9-]{8,64})$/)
    if (req.method === 'GET' && decisionMatch) return handleBrainDecision(req, res, decisionMatch[1])
    if (req.method === 'POST' && match) return handleControl(req, res, match[1], match[2])
    // 路由层分流：路径存在但方法不对 → 405（附 Allow 头）；路径不存在 → 404。
    // 二者必须可区分 —— 前端据 404 判定「端点未实现（planned）」，据 5xx 判定「服务故障」。
    const allowed = ROUTE_GUARD.allowedMethods(url.pathname)
    if (allowed) {
      res.setHeader('Allow', allowed.join(', '))
      return fail(req, res, 405, 'AGENT_METHOD_NOT_ALLOWED',
        `请求方法不被支持：${req.method}，允许 [${allowed.join(', ')}]`,
        { method: req.method, supported: allowed.join(', ') })
    }
    return fail(req, res, 404, 'AGENT_NOT_FOUND', `接口不存在：${url.pathname}`,
      { path: url.pathname })
  })
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

function startServer(options = {}) {
  const controlToken = options.controlToken || resolveControlToken({ tokenFile: options.tokenFile })
  const server = createServer({ ...options, controlToken })
  const port = Number(options.port || DEFAULT_PORT)
  const host = options.host || '127.0.0.1'
  const tokenValue = typeof controlToken === 'string' ? controlToken : controlToken.token
  server.listen(port, host, () => {
    const tokenSource = typeof controlToken === 'string' ? 'injected' : controlToken.source
    process.stdout.write(
      `[wp-bff] listening on ${host}:${port} · control-token-source=${tokenSource}\n` +
      (tokenSource === 'generated'
        ? `[wp-bff] control token (generated, keep secret): ${tokenValue}\n[wp-bff] token file: ${controlToken.tokenFile || '(write failed)'}\n`
        : '[wp-bff] control token loaded from WP_BFF_CONTROL_TOKEN\n'),
    )
  })
  return server
}

if (require.main === module) startServer()

module.exports = {
  MIDDLEWARE,
  WHITELIST,
  IMPLEMENTED_ENDPOINTS,
  ROUTE_GUARD,
  TOOL_RESERVED_SEGMENTS,
  OVERVIEW_GAPS,
  DEFAULT_ALLOWED_ORIGINS,
  DEFAULT_BODY_URL,
  DEFAULT_TOOL_URL,
  createServer,
  startServer,
  resolveControlToken,
  resolveAllowedOrigins,
  timingSafeEqual,
  originVerdict,
  // 以下为纯函数/传输层，导出以便单测直接覆盖（无需起 HTTP 服务）
  queryTerms,
  buildSnippet,
  readJsonBody,
  httpRequestJson,
  httpRequestJsonMeta,
  // 超时档位（权威登记表：contracts/timeout-budget.yaml）。
  // 导出给单测用于**镜像生产默认值** —— 注入替身若不补默认值，读型调用会录到 undefined，
  // 用例就只能断言「有没有传」，而断言不了「档位对不对」。
  TIMEOUT_TIERS: {
    READ: READ_TIMEOUT_MS,
    RETRIEVE: RETRIEVE_TIMEOUT_MS,
    GENERATE: GENERATE_TIMEOUT_MS,
    ASK: ASK_TIMEOUT_MS,
    WRITE: WRITE_TIMEOUT_MS,
    EXECUTE: EXECUTE_TIMEOUT_MS,
  },
}
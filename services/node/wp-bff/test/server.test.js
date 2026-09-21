'use strict'
/**
 * wp-bff 控制面安全回归测试（Node 内置 test runner，零依赖）
 *
 * 覆盖：身份与来源校验、CORS 收紧、key 白名单、固定命令形态、只读端点契约。
 * 运行：node --test services/node/wp-bff/test/
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const { EventEmitter } = require('node:events')

const {
  createServer, APP_SERVICES, MIDDLEWARE, IMPLEMENTED_ENDPOINTS, ROUTE_GUARD, TOOL_RESERVED_SEGMENTS,
  MODEL_RESERVED_SEGMENTS, OVERVIEW_GAPS,
  resolveAllowedOrigins, queryTerms, buildSnippet,
  httpRequestJson, httpRequestJsonMeta, httpRequestJsonDetailed, TIMEOUT_TIERS,
} = require('../server')

const TOKEN = 'test-control-token-0123456789'
const ALLOWED_ORIGIN = 'http://127.0.0.1:3001'
const OS_TMP = process.env.TEMP || process.env.TMPDIR || '/tmp'

/**
 * 写路径（POST /api/wp/knowledge）与 /middleware/:key/start|stop 同级鉴权：
 * 来源白名单 + 控制令牌。开发环境下这一步由 Vite 代理自动注入，浏览器无感。
 */
const CONTROL_HEADERS = { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN }

function request(server, { method = 'GET', path = '/', headers = {}, body = null } = {}) {
  const { port } = server.address()
  const payload = body == null ? null : Buffer.from(JSON.stringify(body), 'utf8')
  // 写方法默认携带控制面鉴权头（等价于开发环境 Vite 代理的注入行为）。
  // 一旦用例显式给出 origin 或 x-wp-control-token，就不再注入 ——
  // 这样「缺令牌 / 来源越权」的鉴权失败用例仍能保持其原始语义。
  const isWrite = method === 'POST' || method === 'PUT' || method === 'PATCH' || method === 'DELETE'
  const explicitAuth = 'origin' in headers || 'x-wp-control-token' in headers
  const authHeaders = isWrite && !explicitAuth ? CONTROL_HEADERS : {}
  const finalHeaders = payload
    ? { 'Content-Type': 'application/json', 'Content-Length': payload.length, ...authHeaders, ...headers }
    : { ...authHeaders, ...headers }
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path, headers: finalHeaders }, res => {
      let responseBody = ''
      res.on('data', chunk => { responseBody += chunk })
      res.on('end', () => {
        let json = null
        try { json = JSON.parse(responseBody) } catch { /* 非 JSON 响应 */ }
        resolve({ status: res.statusCode, headers: res.headers, body: responseBody, json })
      })
    })
    req.on('error', reject)
    if (payload) req.write(payload)
    req.end()
  })
}

/** 启动一个注入了假探针/假 spawn 的服务器，执行 run 后关闭 */
async function withServer(run, overrides = {}) {
  const spawnCalls = []
  const server = createServer({
    controlToken: TOKEN,
    allowedOrigins: resolveAllowedOrigins(ALLOWED_ORIGIN),
    repoRoot: OS_TMP,
    auditPath: `${OS_TMP}/wp-bff-test-audit.log`,
    silent: true,
    probeImpl: async () => ({ up: false, latencyMs: 1 }),
    fetchJson: async () => ({ data: [] }),
    spawnImpl: (cmd, args, opts) => {
      spawnCalls.push({ cmd, args, opts })
      const child = new EventEmitter()
      child.stdout = new EventEmitter()
      child.stderr = new EventEmitter()
      child.kill = () => {}
      process.nextTick(() => child.emit('close', 0))
      return child
    },
    ...overrides,
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    return await run({ server, spawnCalls })
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
}

test('只读端点：GET /api/wp/middleware 无需令牌并返回 8 个中间件', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/middleware' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.items.length, 8)
    assert.equal(res.json.data.summary.total, 8)
    assert.equal(res.json.data.probe_mode, 'tcp')
    assert.deepEqual(res.json.data.items.map(item => item.key).sort(), Object.keys(MIDDLEWARE).sort())
  })
})

test('健康自检端点不泄露控制令牌明文', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/healthz' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.status, 'up')
    assert.ok(!res.body.includes(TOKEN), 'healthz 响应不应包含控制令牌')
    assert.equal(res.json.data.control.token_source, 'injected')
    assert.deepEqual(res.json.data.control.allowed_origins, [ALLOWED_ORIGIN])
  })
})
test('GET /metrics exposes Prometheus text metrics', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/metrics' })
    assert.equal(res.status, 200)
    assert.match(res.body, /lifeform_wp_bff_up 1/)
    assert.match(res.body, /lifeform_wp_bff_uptime_seconds/)
    assert.match(res.body, /lifeform_wp_bff_resident_memory_bytes/)
  })
})

test('控制端点缺少令牌 -> 401，且不触发任何进程', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/middleware/redis/start', headers: { origin: ALLOWED_ORIGIN },
    })
    assert.equal(res.status, 401)
    assert.equal(res.json.code, 'AGENT_UNAUTHORIZED')
    assert.match(res.json.message, /控制令牌/)
    assert.equal(spawnCalls.length, 0)
  })
})

test('控制端点令牌错误 -> 401', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/middleware/redis/start',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': 'wrong-token-value' },
    })
    assert.equal(res.status, 401)
    assert.equal(spawnCalls.length, 0)
  })
})

test('控制端点来源不在白名单 -> 403（CSRF 防护）', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/middleware/redis/start',
      headers: { origin: 'http://evil.example.com', 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 403)
    assert.equal(res.json.code, 'AGENT_FORBIDDEN')
    assert.match(res.json.message, /来源/)
    assert.equal(spawnCalls.length, 0)
  })
})

test('控制端点合法来源 + 合法令牌 -> 200 且命令形态固定为 compose up -d <key>', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/middleware/redis/start',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.state, 'starting')
    assert.equal(spawnCalls.length, 1)
    assert.equal(spawnCalls[0].cmd, 'docker')
    assert.deepEqual(spawnCalls[0].args, ['compose', 'up', '-d', 'redis'])
  })
})

test('stop 动作命令形态为 compose stop <key>（进程仍在运行才下发停止）', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/middleware/kafka/stop',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.state, 'stopping')
    assert.deepEqual(spawnCalls[0].args, ['compose', 'stop', 'kafka'])
  }, { probeImpl: async () => ({ up: true, latencyMs: 1 }) })
})

test('目标态幂等：已停止的服务再次 stop 不下发命令', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/middleware/kafka/stop',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.state, 'down')
    assert.equal(spawnCalls.length, 0)
  })
})

test('非白名单 key：即使令牌与来源合法也返回 403', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/middleware/evil/start',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 403)
    assert.equal(spawnCalls.length, 0)
  })
})

test('CORS 只对白名单来源回显，不使用通配符', async () => {
  await withServer(async ({ server }) => {
    const allowed = await request(server, { path: '/api/wp/middleware', headers: { origin: ALLOWED_ORIGIN } })
    assert.equal(allowed.headers['access-control-allow-origin'], ALLOWED_ORIGIN)

    const foreign = await request(server, { path: '/api/wp/middleware', headers: { origin: 'http://evil.example.com' } })
    assert.equal(foreign.headers['access-control-allow-origin'], undefined)
    assert.equal(foreign.headers['access-control-allow-methods'], undefined)
  })
})

test('预检请求：越权来源 403，白名单来源 204', async () => {
  await withServer(async ({ server }) => {
    const denied = await request(server, {
      method: 'OPTIONS',
      path: '/api/wp/middleware/redis/start',
      headers: { origin: 'http://evil.example.com', 'access-control-request-method': 'POST' },
    })
    assert.equal(denied.status, 403)

    const ok = await request(server, {
      method: 'OPTIONS',
      path: '/api/wp/middleware/redis/start',
      headers: { origin: ALLOWED_ORIGIN, 'access-control-request-method': 'POST' },
    })
    assert.equal(ok.status, 204)
    assert.ok(ok.headers['access-control-allow-headers'].toLowerCase().includes('x-wp-control-token'))
  })
})

test('Jaeger 未启动时 tracing 端点返回 enabled=false 而不是报错', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tracing' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.enabled, false)
    assert.deepEqual(res.json.data.services, [])
  })
})

test('未知路由 -> 404', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/not-exist' })
    assert.equal(res.status, 404)
  })
})

test('tracing 聚合标注采样口径（sample_size / p99_basis）', async () => {
  const traces = [{
    traceID: 'a'.repeat(16),
    spans: [{
      spanID: 's1', operationName: 'GET /x', startTime: 1_700_000_000_000_000, duration: 5_000, processID: 'p1', tags: [],
    }],
    processes: { p1: { serviceName: 'session-manager' } },
  }]
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tracing' })
    const stat = res.json.data.services.find(item => item.name === 'session-manager')
    assert.ok(stat, '应包含 session-manager 聚合项')
    assert.equal(stat.sample_size, 1)
    assert.equal(stat.p99_basis, 'sampled_recent_traces')
    assert.equal(stat.p99_ms, 5)
  }, {
    probeImpl: async () => ({ up: true, latencyMs: 1 }),
    fetchJson: async url => (url.includes('/api/services')
      ? { data: ['session-manager'] }
      : { data: traces }),
  })
})

// ─────────── R-C03 躯体视图（/api/wp/knowledge）───────────

test('体层不可用时 /knowledge 返回 available=false 而不是伪造 0 知识量', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/knowledge' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.ok(res.json.data.reason.includes('体层不可用'))
    assert.ok(Array.isArray(res.json.data.gaps) && res.json.data.gaps.includes('knowledge'))
    assert.equal(res.json.data.knowledge, undefined, '不得返回伪造的知识量字段')
  }, { jsonRequest: async () => null })
})

test('/knowledge 透传体层统计（知识量 / 检索指标 / 三层存储）', async () => {
  const stats = {
    tenant_id: 'tenant-a',
    knowledge: { documents: 3, chunks: 12, metadata_backend: 'pg', vector_points: 12 },
    retrieval: { latency_p99_ms: 42, search_hit_rate: 0.83, cache_hit_rate: 0.4, sample_size: 30 },
    storage: { hot: { available: true }, warm: { available: true }, cold: { available: true } },
  }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/knowledge', headers: { 'X-Tenant-Id': 'tenant-a' } })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.knowledge.documents, 3)
    assert.equal(res.json.data.retrieval.latency_p99_ms, 42)
    assert.equal(res.json.data.checked_at !== undefined, true)
    assert.ok(res.json.data.note.includes('采样'))
  }, { jsonRequest: async (url, options) => {
    assert.ok(url.endsWith('/api/body/knowledge/stats'), '应代理到体层 stats 端点')
    assert.equal(options.headers['X-Tenant-Id'], 'tenant-a', '租户须透传')
    return stats
  } })
})

test('检索测试：空 query -> 400', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/knowledge/search', headers: { 'Content-Type': 'application/json' },
    })
    assert.equal(res.status, 400)
  }, { jsonRequest: async () => null })
})

test('检索测试：返回命中片段与高亮词，且租户透传体层', async () => {
  const hits = [{
    chunk_id: 'doc-1#0', doc_id: 'doc-1', title: '躯体层设计', heading: '存储分层', chunk_index: 0,
    content: '热层用 Redis 缓存高频问题，温层用 Qdrant 向量库，冷层用 PostgreSQL 保存元数据真相。',
    score: 0.81, rerank_score: 0.93, source: 'manual', ingest_time_iso: '2026-09-16T10:00:00Z',
  }]
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge/search',
      headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': 'tenant-b' },
      body: { query: 'Redis 缓存优先' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.hit_count, 1)
    const hit = res.json.data.hits[0]
    assert.equal(hit.rank, 1)
    assert.equal(hit.doc_id, 'doc-1')
    assert.ok(hit.matched_terms.includes('Redis'), '高亮词应包含命中实体')
    assert.ok(hit.matched_terms.includes('缓存'), '中文 2-gram 也应参与高亮')
    assert.ok(hit.snippet.includes('Redis'))
    assert.equal(typeof res.json.data.latency_ms, 'number')
  }, { jsonRequest: async (url, options) => {
    assert.ok(url.endsWith('/api/body/retrieve'), '应代理到体层检索端点')
    assert.equal(options.headers['X-Tenant-Id'], 'tenant-b')
    assert.equal(options.body.use_cache, true, '默认走缓存优先策略')
    return hits
  } })
})

test('检索测试：查询词通过请求体下发时也能解析（含 top_k 上界收敛）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge/search',
      headers: { 'Content-Type': 'application/json' },
      body: { query: '语义检索链路', top_k: 999 },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.top_k, 20, 'top_k 须收敛到上界 20')
    assert.equal(res.json.data.available, false, '体层不可用时应诚实标注')
  }, { jsonRequest: async () => null })
})

// ─────────── R3-09 知识入库（/api/wp/knowledge 写路径）───────────
//
// 写路径鉴权用例：`/knowledge` 的 GET 是只读免令牌，POST 会真实改动知识库，
// 因此必须与 /middleware 控制端点同级校验。两条反向用例 + 一条"合法令牌放行"守住边界。

test('知识入库写路径：缺少控制令牌 -> 401，且不调用体层（无鉴权写入后门回归）', async () => {
  let called = false
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', origin: ALLOWED_ORIGIN },
      body: { title: '未授权写入', content: '试图直连 8090 写入知识库。' },
    })
    assert.equal(res.status, 401)
    assert.equal(res.json.code, 'AGENT_UNAUTHORIZED')
    assert.match(res.json.message, /控制令牌/)
  }, { jsonRequest: async () => { called = true; return null } })
  assert.equal(called, false, '鉴权失败时绝不能触达体层 — 否则即为无鉴权写入后门')
})

test('知识入库写路径：来源不在白名单 -> 403（CSRF 防护），且不调用体层', async () => {
  let called = false
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', origin: 'http://evil.example.com', 'x-wp-control-token': TOKEN },
      body: { title: '跨站写入', content: 'CSRF 尝试。' },
    })
    assert.equal(res.status, 403)
    assert.equal(res.json.code, 'AGENT_FORBIDDEN')
    assert.match(res.json.message, /来源/)
  }, { jsonRequest: async () => { called = true; return null } })
  assert.equal(called, false, '来源非法时不应调用体层')
})

test('知识入库：代理体层单篇端点并透传 doc_id/format', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': 'tenant-a', ...CONTROL_HEADERS },
      body: { doc_id: 'doc-9', title: '躯体层设计', content: '<h1>存储分层</h1><p>热层 Redis。</p>', format: 'html' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.mode, 'single')
    assert.equal(res.json.data.doc_id, 'doc-9')
    assert.equal(res.json.data.chunk_count, 2)
  }, { jsonRequest: async (url, options) => {
    assert.ok(url.endsWith('/api/body/knowledge'), '单篇应代理到体层 /api/body/knowledge')
    assert.equal(options.method, 'POST')
    assert.equal(options.headers['X-Tenant-Id'], 'tenant-a')
    assert.equal(options.body.format, 'html', 'format 必须透传，否则体层无法按 HTML 解析')
    assert.equal(options.body.doc_id, 'doc-9')
    return { doc_id: 'doc-9', chunk_count: 2, status: 'INDEXED', vector_backend: 'hash-ngram-768', success: true }
  } })
})

test('知识入库：content 为空 -> 400，不发起体层请求', async () => {
  let called = false
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', ...CONTROL_HEADERS },
      body: { title: '空文档', content: '   ' },
    })
    assert.equal(res.status, 400)
  }, { jsonRequest: async () => { called = true; return null } })
  assert.equal(called, false, '参数非法时不应调用体层')
})

test('知识入库：体层不可用 -> available=false，不伪造成功', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', ...CONTROL_HEADERS },
      body: { title: '文档', content: '正文内容。' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.equal(res.json.data.success, undefined, '不得返回伪造的 success')
    assert.match(String(res.json.data.reason), /体层不可用/)
  }, { jsonRequest: async () => null })
})

test('知识入库：体层统一错误码（如 pdf 被拒）原样上报，不算入库成功', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', ...CONTROL_HEADERS },
      body: { title: 'PDF 文档', content: '%PDF-1.7 binary', format: 'pdf' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.equal(res.json.data.error_code, 'AGENT_BAD_REQUEST')
    assert.match(String(res.json.data.reason), /入库被体层拒绝/)
  }, { jsonRequest: async () => ({ code: 'AGENT_BAD_REQUEST', message: '暂不支持 format=pdf（DEBT-013）' }) })
})

test('知识入库：批量导入代理到 /knowledge/batch 并保留逐条结果', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/knowledge',
      headers: { 'Content-Type': 'application/json', ...CONTROL_HEADERS },
      body: { documents: [{ doc_id: 'd1', title: 'A', content: '甲' }, { doc_id: 'd2', title: 'B', content: '乙' }] },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.mode, 'batch')
    assert.equal(res.json.data.total, 2)
    assert.equal(res.json.data.failed, 1, '逐条失败信息应保留')
  }, { jsonRequest: async (url, options) => {
    assert.ok(url.endsWith('/api/body/knowledge/batch'), '批量应代理到体层 batch 端点')
    assert.equal(options.body.documents.length, 2)
    assert.equal(options.body.documents[0].doc_id, 'd1')
    return { total: 2, succeeded: 1, failed: 1, results: [{ doc_id: 'd1', success: true }, { doc_id: 'd2', success: false }] }
  } })
})

test('queryTerms：中文无空格查询也能产出 2-gram 高亮词', () => {
  const terms = queryTerms('语义检索链路')
  assert.ok(terms.includes('语义检索链路'))
  assert.ok(terms.includes('语义'))
  assert.ok(terms.includes('检索'))
  assert.ok(terms.every(term => term.length >= 2))
})

test('buildSnippet：命中词在尾部时返回带省略号的窗口片段', () => {
  const content = `${'前置无关文本'.repeat(60)}Redis 缓存优先策略`
  const { snippet, matched_terms } = buildSnippet(content, ['Redis'])
  assert.ok(snippet.startsWith('…'), '截窗前应有省略号')
  assert.ok(snippet.includes('Redis'))
  assert.deepEqual(matched_terms, ['Redis'])
})

test('buildSnippet：无命中词时退化为头部片段且不虚报命中', () => {
  const { snippet, matched_terms } = buildSnippet('无关内容'.repeat(50), ['不存在的词'])
  assert.equal(matched_terms.length, 0)
  assert.ok(snippet.length > 0)
})

// ---------------------------------------------------------------- 路由层错误语义
// 2026-09-18：与 Java 三服务 GlobalExceptionHandler 对齐 —— 路径不存在 404、
// 路径存在但方法不支持 405（附 Allow 头），且错误体统一为 {code,message,details}。

test('未映射路由 -> 404 且错误体为 {code,message,details} 信封', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/not-exist' })
    assert.equal(res.status, 404)
    assert.equal(res.json.code, 'AGENT_NOT_FOUND')
    assert.match(res.json.message, /接口不存在/)
    assert.deepEqual(res.json.details, { path: '/api/wp/not-exist' })
    assert.equal(res.json.error, undefined, '旧 error 字段不应再出现（全平台统一信封）')
  })
})

test('路径存在但方法不支持 -> 405 且带 Allow 头（必须与 404 区分）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'GET', path: '/api/wp/knowledge/search' })
    assert.equal(res.status, 405)
    assert.equal(res.json.code, 'AGENT_METHOD_NOT_ALLOWED')
    assert.equal(res.headers.allow, 'POST')
    assert.deepEqual(res.json.details, { method: 'GET', supported: 'POST' })
  })
})

test('参数化路径方法不支持 -> 405（同一分流逻辑覆盖 {key} 路由）', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'GET', path: '/api/wp/middleware/redis/start', headers: { origin: ALLOWED_ORIGIN },
    })
    assert.equal(res.status, 405)
    assert.equal(res.json.code, 'AGENT_METHOD_NOT_ALLOWED')
    assert.equal(res.headers.allow, 'POST')
    assert.equal(spawnCalls.length, 0)
  })
})

test('ROUTE_GUARD 由 IMPLEMENTED_ENDPOINTS 派生（不立第三份端点真相）', () => {
  for (const { method, path } of IMPLEMENTED_ENDPOINTS) {
    // 通用替换所有占位符（不止 {key}，还有 {decision_id} 等）
    const concrete = `/api/wp${path.replace(/\{[^}]+\}/g, 'sample-id')}`
    const allowed = ROUTE_GUARD.allowedMethods(concrete)
    assert.ok(allowed, `已声明端点应可被路由守卫识别：${method} ${path}`)
    assert.ok(allowed.includes(method), `${method} ${path} 的方法应被允许，实得 ${JSON.stringify(allowed)}`)
  }
  assert.equal(ROUTE_GUARD.allowedMethods('/api/wp/not-exist'), null, '未声明路径应返回 null（→ 404）')
})

test('鉴权失败不触达下游，且错误体带统一错误码', async () => {
  let called = false
  const cases = [
    { headers: { 'Content-Type': 'application/json', origin: ALLOWED_ORIGIN }, status: 401, code: 'AGENT_UNAUTHORIZED' },
    {
      headers: { 'Content-Type': 'application/json', origin: 'http://evil.example.com', 'x-wp-control-token': TOKEN },
      status: 403, code: 'AGENT_FORBIDDEN',
    },
  ]
  for (const item of cases) {
    await withServer(async ({ server }) => {
      const res = await request(server, {
        method: 'POST', path: '/api/wp/knowledge', headers: item.headers, body: { content: '探测写入' },
      })
      assert.equal(res.status, item.status)
      assert.equal(res.json.code, item.code)
    }, { jsonRequest: async () => { called = true; return null } })
  }
  assert.equal(called, false, '鉴权失败时绝不能触达体层 — 否则即为无鉴权写入后门')
})

// ─────────── Phase 4 大脑端点（R-C04 / D5 / R4-06 代理） ───────────

test('GET /api/wp/brain 上游全不可用时降级可见（不静默填 0）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain', headers: { 'x-tenant-id': 'default' } })
    assert.equal(res.status, 200)
    const data = res.json.data
    assert.equal(data.brain_available, false)
    assert.equal(data.cache_available, false)
    assert.equal(data.knowledge_available, false)
    assert.equal(data.degraded, true)
    assert.deepEqual(data.degraded_reasons.sort(),
      ['llm_unavailable', 'retrieval_unavailable', 'semantic_cache_unavailable'])
    assert.equal(data.model_runtime.length, 3)
    assert.equal(data.sessions.available, false, '会话服务不可用必须明示，不能编造活跃会话数')
    assert.equal(data.sessions.active_sessions, null, '读不到就是 null，不能填 0 冒充「没有活跃会话」')
    assert.ok(data.sessions.reason.includes('会话服务不可用'))
    assert.ok(data.sessions.source.includes('/api/session'))
  }, { jsonRequest: async () => null })
})

test('GET /api/wp/brain 上游可用时回传模型与缓存指标', async () => {
  const brainHealth = {
    llm: { available: true, engines: [{ name: 'template', level: 'L1', available: true }],
      stats: { calls: 7, completion_tokens: 210 } },
    semantic_cache: { backend: 'redis', degraded: false },
  }
  const cacheStats = { backend: 'redis', degraded: false, stats: { lookups: 10, hits: 4, hit_rate: 0.4 } }
  const knowledge = { chunks: 12, documents: 8 }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain', headers: { 'x-tenant-id': 'default' } })
    const data = res.json.data
    assert.equal(data.degraded, false, '全部上游可用时不应标降级')
    assert.equal(data.llm.available, true)
    assert.equal(data.semantic_cache.stats.hit_rate, 0.4)
    const gateway = data.model_runtime.find(n => n.node === 'llm_gateway')
    assert.equal(gateway.state, 'healthy')
    assert.equal(gateway.calls, 7)
  }, {
    jsonRequest: async url => (String(url).includes('/cache/stats') ? cacheStats
      : String(url).includes('/knowledge/stats') ? knowledge : brainHealth),
  })
})

test('POST /api/wp/brain/ask 空问题返回 400 统一信封', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/brain/ask', body: { question: '   ' },
    })
    assert.equal(res.status, 400)
    assert.equal(res.json.code, 'AGENT_BAD_REQUEST')
    assert.ok(res.json.message)
  })
})

test('POST /api/wp/brain/ask 代理大脑层并回传来源与决策链', async () => {
  const answer = {
    answer: '模板回答', generator: 'template', degraded: true,
    degraded_reasons: ['llm_template_backend'], sources: [{ title: '设计文档' }],
    chain: [{ step: 'plan' }, { step: 'generate' }], decision_id: 'abc123',
  }
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/brain/ask', body: { question: '躯体期有哪些能力' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.answer, '模板回答')
    assert.equal(res.json.data.sources.length, 1)
    assert.equal(res.json.data.decision_id, 'abc123')
  }, { jsonRequest: async () => answer })
})

test('POST /api/wp/brain/ask 大脑层不可用时标 available=false', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/brain/ask', body: { question: '问题' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.ok(res.json.data.reason.includes('大脑层不可用'))
  }, { jsonRequest: async () => null })
})

test('GET /api/wp/brain/{decision_id} 查无此决策 → 404（不得用 200+空链冒充成功）', async () => {
  class NotFound extends Error {
    constructor() { super('404'); this.status = 404 }
  }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain/deadbeef' })
    assert.equal(res.status, 404, '审计里没有这条决策就是 404，绝不能返 200 + chain:[]')
    assert.equal(res.json.code, 'AGENT_NOT_FOUND')
    assert.equal(res.json.details.decision_id, 'deadbeef')
    assert.equal(res.json.details.source, 'in3-auditlog')
  }, { jsonRequest: async () => { throw new NotFound() } })
})

test('GET /api/wp/brain/{decision_id} 命中审计记录 → 200 且含六步链与三卡', async () => {
  const record = {
    decision_id: 'b6c3bd94',
    question: '躯体期有哪些能力',
    chain: [
      { step: 'intent' }, { step: 'plan' }, { step: 'retrieve' },
      { step: 'generate' }, { step: 'verify' }, { step: 'annotate' },
    ],
    compliance: { grounded: true }, attribution: { cited: 3 }, confidence: 0.72,
  }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain/b6c3bd94' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.chain.length, 6, '决策链必须含 verify 自校验，共 6 步')
    assert.ok(res.json.data.chain.some(n => n.step === 'verify'))
    assert.ok(res.json.data.compliance && res.json.data.attribution)
  }, { jsonRequest: async () => record })
})

test('GET /api/wp/brain/{decision_id} 大脑层整体不可用 → 200 + available:false（降级而非 404）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain/b6c3bd94' })
    assert.equal(res.status, 200, '上游不可用是降级，不是「查不到」，不能混用 404')
    assert.equal(res.json.data.available, false)
    assert.ok(res.json.data.reason.includes('大脑层不可用'))
  }, { jsonRequest: async () => null })
})

test('GET /api/wp/brain/decisions 返回最近决策索引', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain/decisions?limit=5' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.items.length, 1)
  }, { jsonRequest: async () => ({ items: [{ decision_id: 'b6c3bd94' }] }) })
})

// ─────────── 会话链路代理（B1 · R4-01/02） ───────────

test('POST /api/wp/session 会话服务不可用时不伪造 session_id', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'POST', path: '/api/wp/session', body: {} })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.equal(res.json.data.session_id, undefined, '不可用时必须不给 id，不能生成假会话')
    assert.ok(res.json.data.reason.includes('会话服务不可用'))
  }, { jsonRequest: async () => null })
})

test('POST /api/wp/session 可用时透传真实 session_id', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'POST', path: '/api/wp/session', body: {} })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.session_id, 'sess-0001')
  }, { jsonRequest: async () => ({ session_id: 'sess-0001', status: 'NEW' }) })
})

test('POST /api/wp/session/{id}/ask 空问题 → 400，已关闭会话 → 409', async () => {
  class Conflict extends Error {
    constructor() { super('409'); this.status = 409 }
  }
  await withServer(async ({ server }) => {
    const blank = await request(server, {
      method: 'POST', path: '/api/wp/session/sess-0001/ask', body: { question: '  ' },
    })
    assert.equal(blank.status, 400)
    assert.equal(blank.json.code, 'AGENT_BAD_REQUEST')
  }, { jsonRequest: async () => ({ answer: 'x' }) })

  await withServer(async ({ server }) => {
    const closed = await request(server, {
      method: 'POST', path: '/api/wp/session/sess-0001/ask', body: { question: '再问一句' },
    })
    assert.equal(closed.status, 409, '已关闭会话继续追问必须 409，不能静默新建')
    assert.equal(closed.json.code, 'AGENT_CONFLICT')
  }, { jsonRequest: async () => { throw new Conflict() } })
})

test('GET /api/wp/session/{id}/context 透传多轮上下文', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/session/sess-0001/context?turns=5' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.messages.length, 4)
  }, { jsonRequest: async () => ({ session_id: 'sess-0001', messages: [1, 2, 3, 4] }) })
})

test('DELETE /api/wp/session/{id} 关闭会话透传终态', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'DELETE', path: '/api/wp/session/sess-0001' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.status, 'CLOSED')
  }, { jsonRequest: async () => ({ session_id: 'sess-0001', status: 'CLOSED' }) })
})

test('GET /api/wp/session/stats 不可用时 active_sessions 为 null（不静默填 0）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/session/stats' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.equal(res.json.data.active_sessions, null)
  }, { jsonRequest: async () => null })
})

// ─────────── IN-02 记忆图谱代理 ───────────

test('POST /api/wp/brain/memory/ingest 空文本 → 400；可用时透传实体关系', async () => {
  await withServer(async ({ server }) => {
    const bad = await request(server, { method: 'POST', path: '/api/wp/brain/memory/ingest', body: { text: ' ' } })
    assert.equal(bad.status, 400)
  }, { jsonRequest: async () => ({}) })

  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/brain/memory/ingest', body: { text: '大脑层依赖检索服务' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.entities.length, 2)
    assert.equal(res.json.data.relations.length, 1)
  }, { jsonRequest: async () => ({ entities: ['大脑层', '检索服务'], relations: [['大脑层', '依赖', '检索服务']] }) })
})

test('GET /api/wp/brain/memory/subgraph 缺 root → 400；命中时透传子图', async () => {
  await withServer(async ({ server }) => {
    const bad = await request(server, { path: '/api/wp/brain/memory/subgraph' })
    assert.equal(bad.status, 400)
  }, { jsonRequest: async () => ({}) })

  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain/memory/subgraph?root=%E5%A4%A7%E8%84%91%E5%B1%82&depth=2' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.ok(res.json.data.entities.length >= 1)
  }, { jsonRequest: async () => ({ entities: [{ name: '大脑层' }], relations: [] }) })
})

// ─────────── 总览驾驶舱（C3） ───────────

test('GET /api/wp/overview 携带 model_calls / model_runtime 驾驶舱指标', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/overview' })
    assert.equal(res.status, 200)
    const data = res.json.data
    assert.ok(Array.isArray(data.model_calls), '驾驶舱需要模型调用次数序列')
    assert.ok(Array.isArray(data.model_runtime), '驾驶舱需要模型节点运行时状态')
    assert.equal(data.model_runtime.length >= 3, true)
    assert.ok(data.model_runtime.every(n => n.node && n.state))
  }, {
    jsonRequest: async url => String(url).includes('/brain/health')
      ? { llm: { available: true, stats: { calls: 3 } }, semantic_cache: { backend: 'redis', degraded: false } }
      : null,
  })
})

test('GET /api/wp/brain 用不支持的方法 → 405 且带 Allow 头', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'DELETE', path: '/api/wp/brain' })
    assert.equal(res.status, 405)
    assert.equal(res.json.code, 'AGENT_METHOD_NOT_ALLOWED')
    assert.equal(res.headers.allow, 'GET')
  })
})

test('GET /api/wp/brain 知识量按体层嵌套口径取（不能静默填 0）', async () => {
  // 体层 /api/body/knowledge/stats 的知识量嵌在 knowledge.knowledge 下；
  // 按顶层取会恒为 0 —— 那是「把未知写成 0」，会让大脑视图显示错误的知识量。
  const brainHealth = { llm: { available: true, engines: [], stats: { calls: 1 } }, semantic_cache: { backend: 'redis' } }
  const cacheStats = { backend: 'redis', degraded: false, stats: { lookups: 2, hits: 1, hit_rate: 0.5 } }
  const knowledge = { knowledge: { documents: 7, chunks: 11 }, retrieval: { chunks: 11, documents: 7 } }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain', headers: { 'x-tenant-id': 'default' } })
    const data = res.json.data
    assert.equal(data.retrieval.chunks, 11, 'chunks 必须取自嵌套的 knowledge.knowledge')
    assert.equal(data.retrieval.documents, 7)
    const retrievalNode = data.model_runtime.find(n => n.node === 'retrieval')
    assert.equal(retrievalNode.chunks, 11, '模型运行时节点不得显示 0')
  }, {
    jsonRequest: async url => (String(url).includes('/cache/stats') ? cacheStats
      : String(url).includes('/knowledge/stats') ? knowledge : brainHealth),
  })
})

test('GET /api/wp/brain 体层不可用时 retrieval 为 null（不伪造知识量）', async () => {
  const brainHealth = { llm: { available: true, engines: [], stats: { calls: 0 } }, semantic_cache: { backend: 'redis' } }
  const cacheStats = { backend: 'redis', degraded: false, stats: { lookups: 0, hits: 0, hit_rate: 0 } }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/brain', headers: { 'x-tenant-id': 'default' } })
    const data = res.json.data
    assert.equal(data.retrieval, null)
    assert.equal(data.knowledge_available, false)
    assert.deepEqual(data.degraded_reasons, ['retrieval_unavailable'])
  }, {
    jsonRequest: async url => (String(url).includes('/cache/stats') ? cacheStats
      : String(url).includes('/knowledge/stats') ? null : brainHealth),
  })
})

// ─────────── Phase 5 四肢层执行视图（R-C05 预 / R5-08 / IN-06 代理） ───────────

const TOOL_LIST = {
  total: 3,
  registry_backend: 'in-memory',
  tools: [
    { name: 'calculator', version: '1.0.0', sandbox_required: false, timeout_ms: 3000, deprecated: false },
    { name: 'http', version: '1.0.0', sandbox_required: false, timeout_ms: 8000, deprecated: false },
    { name: 'code', version: '1.0.0', sandbox_required: true, timeout_ms: 10000, deprecated: false },
  ],
}
const TOOL_REGISTRY = {
  summary: { tool_count: 3, deprecated_count: 0, change_count: 3 },
  change_log: [{ tool_name: 'http', version: '1.0.0', action: 'register' }],
  semver_policy: 'schema 变更即触发 L1 契约测试',
  deprecation_policy: '废弃期 30 天',
}
const TOOL_METRICS = {
  metrics: { total_calls: 12, total_failures: 2, blocked_calls: 1, success_rate: 0.8333, p50_ms: 7, p95_ms: 40, p99_ms: 61, calls_by_tool: { calculator: 9 } },
  circuit_breakers: [{ tool_name: 'http', state: 'closed', consecutive_failures: 0 }],
}
const TOOL_SANDBOX = {
  enabled: true, active_backend: 'docker', isolated: true, degraded: false,
  docker_available: true, process_fallback_available: true, timeout_ms: 10000, memory_mb: 256,
}
const TOOL_AUDIT = {
  total: 1, audit_backend: 'postgres', degraded: false,
  items: [{ audit_id: 'a1', tool_name: 'calculator', success: true, latency_ms: 5, sandboxed: false }],
}
const TOOL_AUDIT_STATS = { audit: { backend: 'postgres', total: 12, success: 9, blocked: 1, sandboxed: 3 }, kafka: { available: true } }

/** 按 URL 分派假上游：未列出的路径返回 null（模拟不可达） */
function fakeToolsUpstream(overrides = {}) {
  const table = {
    '/api/tool/list': TOOL_LIST,
    '/api/tool/registry': TOOL_REGISTRY,
    '/api/tool/metrics': TOOL_METRICS,
    '/api/tool/sandbox': TOOL_SANDBOX,
    '/api/tool/audit/stats': TOOL_AUDIT_STATS,
    '/api/tool/audit?limit=50': TOOL_AUDIT,
    '/api/tool/audit?limit=20': TOOL_AUDIT,
    ...overrides,
  }
  return async url => table[String(url).split('8084').pop()] ?? null
}

test('GET /api/wp/tools 四肢层全不可用时降级可见（不伪造工具清单）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools', headers: { 'x-tenant-id': 'default' } })
    assert.equal(res.status, 200, '四肢层不可用是降级，不是 5xx 故障')
    const data = res.json.data
    assert.equal(data.available, false)
    assert.ok(data.reason.includes('四肢层不可用'))
    assert.equal(data.tools, undefined, '不可用时不得凭空给出工具清单')
    assert.deepEqual(data.gaps, ['tools', 'registry', 'metrics', 'sandbox', 'audit'])
    assert.ok(data.tool_url.includes('8084'))
  }, { jsonRequest: async () => null })
})

test('GET /api/wp/tools 四肢层可用时聚合工具/注册表/指标/沙箱/审计', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools', headers: { 'x-tenant-id': 'default' } })
    const data = res.json.data
    assert.equal(data.available, true)
    assert.equal(data.total, 3)
    assert.equal(data.tools.length, 3)
    assert.equal(data.partial, false, '分片全取到时不标 partial')
    assert.deepEqual(data.partial_reasons, [])
    assert.equal(data.metrics.metrics.success_rate, 0.8333)
    assert.equal(data.sandbox.isolated, true)
    assert.equal(data.audit.items.length, 1)
    assert.equal(data.audit_stats.audit.blocked, 1)
    assert.equal(data.registry.summary.tool_count, 3)
  }, { jsonRequest: fakeToolsUpstream() })
})

test('GET /api/wp/tools 分片缺失时标 partial 而非把缺失读成零', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools', headers: { 'x-tenant-id': 'default' } })
    const data = res.json.data
    assert.equal(data.available, true)
    assert.equal(data.partial, true)
    assert.ok(data.partial_reasons.includes('metrics_unavailable'))
    assert.equal(data.metrics, null, '取不到就是 null，不能给 {} 让前端渲染成 0')
  }, { jsonRequest: fakeToolsUpstream({ '/api/tool/metrics': null }) })
})

test('GET /api/wp/tools/sandbox 透传沙箱状态并标注隔离边界', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/sandbox' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.sandbox.active_backend, 'docker')
    assert.equal(res.json.data.sandbox.degraded, false)
  }, { jsonRequest: fakeToolsUpstream() })
})

test('GET /api/wp/tools/audit 返回真实调用记录（含沙箱与耗时）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/audit?limit=50' })
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.items[0].tool_name, 'calculator')
    assert.equal(res.json.data.audit_backend, 'postgres')
  }, { jsonRequest: fakeToolsUpstream() })
})

test('GET /api/wp/tools/metrics 回传分位耗时与熔断状态', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/metrics' })
    assert.equal(res.json.data.metrics.p99_ms, 61)
    assert.equal(res.json.data.circuit_breakers[0].state, 'closed')
  }, { jsonRequest: fakeToolsUpstream() })
})

test('GET /api/wp/tools/registry 回传注册表摘要与变更历史（IN-06）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/registry' })
    assert.equal(res.json.data.summary.tool_count, 3)
    assert.equal(res.json.data.change_log.length, 1)
    assert.ok(res.json.data.deprecation_policy.includes('30 天'))
  }, { jsonRequest: fakeToolsUpstream() })
})

test('GET /api/wp/tools/{name} 与 /impact 走参数路由而不被固定子路径吞掉', async () => {
  await withServer(async ({ server }) => {
    const detail = await request(server, { path: '/api/wp/tools/http' })
    assert.equal(detail.json.data.tool.name, 'http')
    const impact = await request(server, { path: '/api/wp/tools/http/impact' })
    assert.equal(impact.json.data.impact.tool_name, 'http')
  }, {
    jsonRequest: async url => {
      const path = String(url).split('8084').pop()
      if (path === '/api/tool/http') return { name: 'http', version: '1.0.0' }
      if (path === '/api/tool/http/impact') return { tool_name: 'http', affected_agents: ['agent-a'] }
      return null
    },
  })
})

test('POST /api/wp/tools/execute 未带控制令牌 → 401 且绝不触达四肢层', async () => {
  let called = false
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/tools/execute',
      headers: { origin: ALLOWED_ORIGIN }, body: { tool_name: 'calculator' },
    })
    assert.equal(res.status, 401)
    assert.equal(res.json.code, 'AGENT_UNAUTHORIZED')
  }, { jsonRequest: async () => { called = true; return null } })
  assert.equal(called, false, '鉴权失败时绝不能触达四肢层 — 否则即为免令牌执行后门')
})

test('POST /api/wp/tools/execute 缺少 tool_name → 400', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/tools/execute',
      headers: CONTROL_HEADERS, body: { arguments: {} },
    })
    assert.equal(res.status, 400)
    assert.equal(res.json.code, 'AGENT_BAD_REQUEST')
  }, { jsonRequest: async () => TOOL_LIST })
})

test('POST /api/wp/tools/execute 成功 → 回执行结果与审计号', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/tools/execute',
      headers: CONTROL_HEADERS, body: { tool_name: 'calculator', arguments: { expression: '1+1' } },
    })
    assert.equal(res.status, 200)
    const data = res.json.data
    assert.equal(data.available, true)
    assert.equal(data.success, true)
    assert.equal(data.audit_id, 'a-100')
    assert.equal(data.call_id, 'call-1')
    assert.equal(data.tenant_id, 'default')
  }, {
    jsonRequest: async () => ({
      call_id: 'call-1', tool_name: 'calculator', success: true, output: { result: 2 },
      latency_ms: 5, sandboxed: false, degraded: false, audit_id: 'a-100',
    }),
  })
})

test('POST /api/wp/tools/execute 工具被守卫拒绝 → available:true + success:false（真实记录，非服务不可用）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/tools/execute',
      headers: CONTROL_HEADERS, body: { tool_name: 'code', arguments: { source: 'os.system("rm -rf /")' } },
    })
    assert.equal(res.status, 200)
    const data = res.json.data
    assert.equal(data.available, true, '工具被拦是已落审计的真实结果，不能混同为「四肢层不可用」')
    assert.equal(data.success, false)
    assert.equal(data.error_code, 'AGENT_TOOL_ARGS_BLOCKED')
    assert.equal(data.audit_id, 'a-101')
  }, {
    jsonRequest: async () => ({
      code: 'AGENT_TOOL_ARGS_BLOCKED', message: '参数命中危险模式',
      details: { audit_id: 'a-101', call_id: 'call-2' },
    }),
  })
})

test('POST /api/wp/tools/execute 四肢层不可达 → available:false 且明示本次调用未生效', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/tools/execute',
      headers: CONTROL_HEADERS, body: { tool_name: 'calculator', arguments: {} },
    })
    assert.equal(res.status, 200)
    const data = res.json.data
    assert.equal(data.available, false)
    assert.ok(data.reason.includes('本次调用未生效'))
  }, { jsonRequest: async () => null })
})

// ─────────── 「慢」不得写成「没有」：失败原因细分回归 ───────────
//
// 背景（Phase 5 实测缺陷）：传输层把「超时 / 连接拒绝 / 404 / 非 JSON」折叠成一个 null，
// 上游只能表达成「不可用」。`/api/tool/sandbox` 冷探测 5.7s 超过 5s 预算后，
// `/api/wp/tools` 长期宣称「沙箱不可用」——与服务侧真相相反，且单测/契约都拦不住。
// 以下用例把「超时 ≠ 缺失」这条不变量钉住。

const okOutcome = data => ({ ok: true, data, reason: null })
const failOutcome = reason => ({ ok: false, data: null, reason })

/** 按 URL 分派带原因的假上游；未列出的路径 = 连接被拒 */
function fakeToolsUpstreamMeta(overrides = {}) {
  const table = {
    '/api/tool/list': okOutcome(TOOL_LIST),
    '/api/tool/registry': okOutcome(TOOL_REGISTRY),
    '/api/tool/metrics': okOutcome(TOOL_METRICS),
    '/api/tool/sandbox': okOutcome(TOOL_SANDBOX),
    '/api/tool/audit/stats': okOutcome(TOOL_AUDIT_STATS),
    '/api/tool/audit?limit=20': okOutcome(TOOL_AUDIT),
    ...overrides,
  }
  return async url => table[String(url).split('8084').pop()] ?? failOutcome('unreachable')
}

test('GET /api/wp/tools 沙箱分片超时 → 降级码是 *_timeout，不写成「沙箱不可用」', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools' })
    const data = res.json.data
    assert.equal(data.available, true, '只是分片慢，整体仍是可用')
    assert.equal(data.partial, true)
    assert.deepEqual(data.partial_reasons, ['sandbox_timeout'])
    assert.equal(data.sandbox, null, '超时取不到就是 null，不能给 {} 让前端渲染成 0')
    assert.ok(data.partial_note && data.partial_note.includes('超时'), 'partial_note 必须把「超时」说出口')
    assert.ok(data.partial_note.includes('不代表该能力缺失'))
    assert.deepEqual(data.probe_budget_ms, { default: 5000, sandbox: 9000 },
      '预算按域而定：沙箱要走 docker 探测，预算必须比其余分片宽')
  }, { jsonRequestMeta: fakeToolsUpstreamMeta({ '/api/tool/sandbox': failOutcome('timeout') }) })
})

test('GET /api/wp/tools 分片 404 → 降级码是 *_missing（对端版本旧于 BFF，而非服务不可用）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools' })
    const data = res.json.data
    assert.deepEqual(data.partial_reasons, ['registry_missing'])
    assert.ok(data.partial_note.includes('端点不存在'))
    assert.ok(data.partial_note.includes('版本可能旧于本 BFF'))
  }, { jsonRequestMeta: fakeToolsUpstreamMeta({ '/api/tool/registry': failOutcome('endpoint_missing') }) })
})

test('GET /api/wp/tools 工具清单超时 → available:false 且 reason_code=timeout、文案明说「慢」', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools' })
    assert.equal(res.status, 200, '超时也是降级，不是 5xx')
    const data = res.json.data
    assert.equal(data.available, false)
    assert.equal(data.reason_code, 'timeout')
    assert.ok(data.reason.includes('四肢层不可用'), '保留既有降级信封词根')
    assert.ok(data.reason.includes('超时'))
    assert.ok(data.reason.includes('不是「没有」'), '不能把慢说成没有')
    assert.ok(!data.reason.includes('未启动'), '超时不得被笼统说成未启动')
    assert.deepEqual(data.gaps, ['tools', 'registry', 'metrics', 'sandbox', 'audit'])
  }, { jsonRequestMeta: async () => failOutcome('timeout') })
})

test('GET /api/wp/tools/sandbox 超时 → 单端点代理同样标注 reason_code（不合并语义）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/sandbox' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, false)
    assert.equal(res.json.data.reason_code, 'timeout')
    assert.ok(res.json.data.reason.includes('超时'))
  }, { jsonRequestMeta: async () => failOutcome('timeout') })
})

test('注入式假传输不假装知道原因 → 退回通用降级码词根（既有契约不被改写）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools' })
    const data = res.json.data
    assert.deepEqual(data.partial_reasons, ['metrics_unavailable'],
      '假传输只能给「有/没有」，不得凭空捏造 timeout/missing')
    assert.equal(data.partial_note, null, '没有超时证据就不产出超时说明')
  }, { jsonRequest: fakeToolsUpstream({ '/api/tool/metrics': null }) })
})

test('httpRequestJsonMeta：上游不响应 → reason=timeout（不是笼统的不可用）', async () => {
  const hang = http.createServer(() => { /* 故意永不响应 */ })
  await new Promise(resolve => hang.listen(0, '127.0.0.1', resolve))
  try {
    const url = `http://127.0.0.1:${hang.address().port}/api/tool/sandbox`
    const outcome = await httpRequestJsonMeta(url, { timeoutMs: 150 })
    assert.equal(outcome.ok, false)
    assert.equal(outcome.reason, 'timeout')
    assert.equal(outcome.data, null)
  } finally {
    await new Promise(resolve => hang.close(resolve))
  }
})

test('httpRequestJsonMeta：404 → endpoint_missing；500 → http_error；非 JSON → bad_json', async () => {
  const fake = http.createServer((req, res) => {
    if (req.url === '/missing') { res.writeHead(404, { 'Content-Type': 'application/json' }); return res.end('{"code":"NOT_FOUND"}') }
    if (req.url === '/boom') { res.writeHead(500); return res.end('') }
    res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end('not json at all')
  })
  await new Promise(resolve => fake.listen(0, '127.0.0.1', resolve))
  try {
    const base = `http://127.0.0.1:${fake.address().port}`
    assert.equal((await httpRequestJsonMeta(`${base}/missing`)).reason, 'endpoint_missing')
    assert.equal((await httpRequestJsonMeta(`${base}/boom`)).reason, 'http_error')
    assert.equal((await httpRequestJsonMeta(`${base}/plain`)).reason, 'bad_json')
    assert.equal((await httpRequestJsonMeta('http://127.0.0.1:1/none')).reason, 'unreachable')
  } finally {
    await new Promise(resolve => fake.close(resolve))
  }
})

test('httpRequestJsonMeta 成功路径与 httpRequestJson 行为一致（同一套传输，无口径分叉）', async () => {
  const upstream = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"total":3}')
  })
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve))
  try {
    const url = `http://127.0.0.1:${upstream.address().port}/api/tool/list`
    assert.deepEqual(await httpRequestJson(url), { total: 3 })
    assert.deepEqual((await httpRequestJsonMeta(url)).data, { total: 3 })
    assert.deepEqual(await httpRequestJson('http://127.0.0.1:1/none'), null, '薄封装仍以 null 表达失败')
  } finally {
    await new Promise(resolve => upstream.close(resolve))
  }
})

test('执行视图端点已登记进 IMPLEMENTED_ENDPOINTS（契约对账用）', () => {
  const registered = IMPLEMENTED_ENDPOINTS.map(item => `${item.method} ${item.path}`)
  for (const expected of [
    'GET /tools', 'GET /tools/registry', 'GET /tools/audit', 'GET /tools/audit/stats',
    'GET /tools/metrics', 'GET /tools/sandbox', 'POST /tools/execute',
    'GET /tools/{name}', 'GET /tools/{name}/impact',
  ]) {
    assert.ok(registered.includes(expected), `缺少端点登记：${expected}`)
  }
})

test('路由守卫把 /tools 固定子路径与参数路径分成两条（405 需可区分）', () => {
  assert.deepEqual(ROUTE_GUARD.allowedMethods('/api/wp/tools/registry'), ['GET'])
  assert.deepEqual(ROUTE_GUARD.allowedMethods('/api/wp/tools/execute'), ['POST'])
  assert.deepEqual(ROUTE_GUARD.allowedMethods('/api/wp/tools/http'), ['GET'])
  assert.equal(ROUTE_GUARD.allowedMethods('/api/wp/tools/nope/nope'), null)
})

// 语义回归：固定子路径不得被参数路由吃掉，否则 GET /tools/execute 会返回 200 而非 405
test('GET /api/wp/tools/execute → 405 且带 Allow 头（不被 /tools/{name} 参数路由吞掉）', async () => {
  let called = false
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/execute' })
    assert.equal(res.status, 405, '路径存在但方法不对必须是 405，不能被当成工具详情返回 200')
    assert.equal(res.json.code, 'AGENT_METHOD_NOT_ALLOWED')
    assert.equal(res.headers.allow, 'POST')
  }, { jsonRequest: async () => { called = true; return { name: 'execute' } } })
  assert.equal(called, false, '不得把 execute 当作工具名去代理上游（那正是本用例要防的静默语义改写）')
})

test('固定子路径段集合由 IMPLEMENTED_ENDPOINTS 派生（无第三份端点真相）', () => {
  for (const seg of ['registry', 'audit', 'metrics', 'sandbox', 'execute']) {
    assert.ok(TOOL_RESERVED_SEGMENTS.has(seg), '缺少固定段：' + seg)
  }
  assert.ok(!TOOL_RESERVED_SEGMENTS.has('http'), '参数段不应被当成固定段')
  assert.ok(!TOOL_RESERVED_SEGMENTS.has('{name}'))
})

test('真实工具名仍走参数路由（calculator / code 不受固定段影响）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/tools/code' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.tool.name, 'code')
  }, { jsonRequest: async () => ({ name: 'code', version: '1.0.0' }) })
})

// ─────────── 跨服务超时预算不变量（权威登记表：contracts/timeout-budget.yaml）───────────
//
// 为什么单独守这组不变量：
//   超时错配**不触发任何功能断言** —— 单测全绿、契约 0 FAIL，只在运行期表现为
//   「把一个只是慢的接口叙述成不可用」。2026-09-19 沙箱探测那条正是此形状：
//   冷探测 5.7s 撞上 5s 统一预算，视图长期宣称「沙箱不可用」，而直连该端点是 200。
//
// 本组用例与 `scripts/timeout-budget-check.py` 互补，缺任一方都有盲区：
//   · 脚本校验「登记表数字 ↔ 代码常量」—— 防的是「表里写 A、代码是 B」；
//   · 用例校验「代码行为 ↔ 操作类型」—— 防的是「改行为却忘了常量/分档被合并」。
//
// 断言一律用下限/上限而非精确值，这样运维通过环境变量调档不会误伤用例。

/**
 * 记录下游调用实参的 jsonRequest 替身。
 *
 * 关键点：**必须镜像生产默认值**（`httpRequestJson` 的默认 `timeoutMs = READ_TIMEOUT_MS`）。
 * 否则未显式传超时的读型调用会被录成 `undefined`，用例只能断言「有没有传」，
 * 断言不了「档位对不对」—— 而这组用例的全部意义就在于后者。
 */
function recordingJsonRequest(payloads = {}) {
  const calls = []
  const jsonRequest = async (url, opts = {}) => {
    calls.push({
      url: String(url),
      timeoutMs: opts.timeoutMs === undefined ? TIMEOUT_TIERS.READ : opts.timeoutMs,
      explicit: opts.timeoutMs !== undefined,
      method: opts.method || 'GET',
    })
    for (const key of Object.keys(payloads)) {
      if (String(url).includes(key)) return payloads[key]
    }
    return { ok: true }
  }
  return { calls, jsonRequest }
}

test('生成型链路（brain/ask）使用生成档预算，不落到读型默认值', async () => {
  const rec = recordingJsonRequest({ '/brain/ask': { answer: 'ok', generator: 'template' } })
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/brain/ask',
      headers: CONTROL_HEADERS, body: { question: 'ping' },
    })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls.find(c => c.url.includes('/brain/ask'))
  assert.ok(call, '应代理到 nlp-service /brain/ask')
  assert.ok(
    call.timeoutMs >= 30000,
    `生成型预算应 >= 30000ms（下游 LLM 级联最坏 22s），实际 ${call.timeoutMs}ms —— ` +
    '退化成读型默认值时，LLM 稍慢就会被误报为「大脑层不可用」',
  )
})

test('生成型链路（session/{id}/ask）使用专用 ASK 档预算 —— 覆盖 session 35s 总预算（TB-09 / GAP-06）', async () => {
  const rec = recordingJsonRequest({ '/ask': { answer: 'ok' } })
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/session/sess-12345678/ask',
      headers: CONTROL_HEADERS, body: { question: 'ping' },
    })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls.find(c => c.url.includes('/ask'))
  assert.ok(call, '应代理到 session-manager /ask')
  assert.equal(call.explicit, true, '会话问答必须显式声明预算档')
  assert.ok(
    call.timeoutMs >= TIMEOUT_TIERS.ASK,
    `会话问答预算应 >= ASK 档 ${TIMEOUT_TIERS.ASK}ms（下游 ASK_TOTAL_BUDGET_MS=35s 的 1.5x），实际 ${call.timeoutMs}ms`,
  )
  // ASK 档必须严格大于 GENERATE 档 —— 它的存在意义就是比共享生成档更宽；
  // 若被改回 GENERATE，53s 与 45s 合并，TB-09 的余量比会跌回 1.29x。
  assert.ok(
    TIMEOUT_TIERS.ASK > TIMEOUT_TIERS.GENERATE,
    `ASK 档 ${TIMEOUT_TIERS.ASK}ms 必须大于 GENERATE 档 ${TIMEOUT_TIERS.GENERATE}ms —— 两档合并即 GAP-06 复发`,
  )
})

test('生成型链路（memory/ingest）使用生成档预算 —— 记忆抽取同样走 LLM', async () => {
  const rec = recordingJsonRequest({ '/memory/ingest': { entities: [], relations: [] } })
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/brain/memory/ingest',
      headers: CONTROL_HEADERS, body: { text: 'some text' },
    })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls.find(c => c.url.includes('/memory/ingest'))
  assert.ok(call, '应代理到 nlp-service memory/ingest')
  assert.ok(call.timeoutMs >= 30000, `生成型预算应 >= 30000ms，实际 ${call.timeoutMs}ms`)
})

test('检索型链路（body retrieve）预算覆盖下游读超时 10s', async () => {
  const rec = recordingJsonRequest({ '/body/retrieve': [] })
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/knowledge/search',
      headers: CONTROL_HEADERS, body: { query: 'docker' },
    })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls.find(c => c.url.includes('/body/retrieve'))
  assert.ok(call, '应代理到 body-service /api/body/retrieve')
  assert.ok(
    call.timeoutMs >= 15000,
    `检索型预算应 >= 15000ms（下游 body 读超时 10s 的 1.5 倍），实际 ${call.timeoutMs}ms —— ` +
    '上游预算等于下游读超时时余量为 0，下游一走到边界上游必然先放弃',
  )
})

test('读型端点必须保持短预算 —— 不得为修「慢」把所有超时一刀切拉长', async () => {
  const rec = recordingJsonRequest({})
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/session/stats', headers: CONTROL_HEADERS })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls.find(c => c.url.includes('/session/stats'))
  assert.ok(call, '应代理到 session-manager /session/stats')
  assert.equal(call.explicit, false, '读型端点不应显式传预算（走 READ 默认档即可）')
  assert.ok(
    call.timeoutMs <= TIMEOUT_TIERS.READ,
    `读型预算必须保持 <= READ 档 ${TIMEOUT_TIERS.READ}ms，实际 ${call.timeoutMs}ms —— ` +
    '把读型也拉到生成档会让真实故障等 45s 才暴露降级',
  )
})

test('生成档与读型档必须真的分开（防止两档被合并成同一个值）', async () => {
  const rec = recordingJsonRequest({ '/brain/ask': { answer: 'ok' } })
  await withServer(async ({ server }) => {
    await request(server, { path: '/api/wp/session/stats', headers: CONTROL_HEADERS })
    await request(server, {
      method: 'POST', path: '/api/wp/brain/ask',
      headers: CONTROL_HEADERS, body: { question: 'ping' },
    })
  }, { jsonRequest: rec.jsonRequest })

  const read = rec.calls.find(c => c.url.includes('/session/stats'))
  const gen = rec.calls.find(c => c.url.includes('/brain/ask'))
  assert.ok(read && gen, '两类调用都应发生')
  assert.ok(
    gen.timeoutMs > read.timeoutMs,
    `生成档 ${gen.timeoutMs}ms 必须大于读型档 ${read.timeoutMs}ms —— 两者相等意味着分档已失效`,
  )
})

// ═══════════════════ WB-10 模型接入配置（/api/wp/models）═══════════════════
//
// 覆盖三类风险：
//   1. **安全边界**：读路径免令牌，写路径必须来源白名单 + 控制令牌。
//      给只读路径加写方法却不补校验 = 一条「任何人可改线上模型指向」的后门。
//   2. **语义正确**：`/models/reload` 是固定段，不能被 `/models/{model_id}` 吃掉。
//   3. **诚实降级**：读路径可 200 + available:false；写路径必须 503，绝不 200 假装成功。

/** 记录完整出入参（含 body）的假传输 —— 模型域需要断言真实转发体 */
function recordingModelsRequest(handler) {
  const calls = []
  const jsonRequest = async (url, opts = {}) => {
    const call = {
      url: String(url),
      method: opts.method || 'GET',
      body: opts.body,
      headers: opts.headers || {},
      timeoutMs: opts.timeoutMs,
    }
    calls.push(call)
    return handler(call)
  }
  return { calls, jsonRequest }
}

/** 构造一个带 HTTP 状态与响应体的上游错误（模拟 httpRequestJsonDetailed 的失败态） */
function upstreamError(status, payload) {
  const error = new Error(`upstream ${status}`)
  error.status = status
  error.payload = payload
  return error
}

test('/api/wp/models 读路径：大脑层可用 → 200 + available:true 且清单原样透传', async () => {
  const payload = {
    items: [{ id: 1, config_key: 'generate', name: '主模型', api_key_hint: 'sk-***mnop', enabled: true }],
    by_role: { generate: [{ id: 1 }] },
    total: 1,
    enabled: 1,
    roles: [{ key: 'generate', label: '内容生成' }],
  }
  const rec = recordingModelsRequest(() => payload)
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/models', headers: { 'x-tenant-id': 'acme' } })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.total, 1)
    assert.equal(res.json.data.items[0].api_key_hint, 'sk-***mnop', '脱敏值应原样透传')
    assert.equal(res.json.data.tenant_id, 'acme')
  }, { jsonRequest: rec.jsonRequest })

  assert.equal(rec.calls.length, 1)
  assert.ok(rec.calls[0].url.endsWith('/api/nlp/models'), '读路径应代理到 nlp-service /api/nlp/models')
  assert.equal(rec.calls[0].headers['X-Tenant-Id'], 'acme', '租户应透传给上游')
})

test('/api/wp/models 读路径：大脑层不可达 → 200 + available:false（不把读不到渲染成「没有配置」）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/models' })
    assert.equal(res.status, 200, '读路径不可达走降级，不是故障码')
    assert.equal(res.json.data.available, false)
    assert.deepEqual(res.json.data.items, [])
    assert.ok(String(res.json.data.reason).includes('大脑层不可用'), '必须说明是大脑层不可用')
  }, { jsonRequest: async () => null })
})

test('/api/wp/models/usage 读路径：窗口参数透传 + 租户头带上，用量原样回传', async () => {
  const payload = {
    tenant_id: 'acme',
    window: { days: 3, start: '2026-09-18', end: '2026-09-20' },
    totals: { calls: 12, failures: 1, prompt_tokens: 900, completion_tokens: 300, tokens: 1200 },
    by_role: { generate: { calls: 12 } },
    by_token_source: { provider: 9, estimated: 3 },
    estimated_share: 0.25,
    daily: [{ date: '2026-09-20', calls: 12 }],
  }
  const rec = recordingModelsRequest(() => payload)
  await withServer(async ({ server }) => {
    const res = await request(server, {
      path: '/api/wp/models/usage?days=3', headers: { 'x-tenant-id': 'acme' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.totals.tokens, 1200)
    // `estimated_share` 必须一路透到前端：它是判断"这行数字能不能当账单看"的唯一依据
    assert.equal(res.json.data.estimated_share, 0.25)
  }, { jsonRequest: rec.jsonRequest })

  assert.equal(rec.calls.length, 1)
  assert.ok(rec.calls[0].url.includes('/api/nlp/models/usage?days=3'),
    `窗口参数应透传到上游，实际 ${rec.calls[0].url}`)
  assert.equal(rec.calls[0].headers['X-Tenant-Id'], 'acme', '用量必须按租户取，否则多租户看板是假维度')
})

test('/api/wp/models/usage 窗口参数越界 → 收敛到 [1, 90]，不把非法值转给上游', async () => {
  const rec = recordingModelsRequest(() => ({ totals: {} }))
  await withServer(async ({ server }) => {
    await request(server, { path: '/api/wp/models/usage?days=9999' })
    await request(server, { path: '/api/wp/models/usage?days=-5' })
    await request(server, { path: '/api/wp/models/usage' })
  }, { jsonRequest: rec.jsonRequest })
  assert.ok(rec.calls[0].url.endsWith('/usage?days=90'), `上界应收敛，实际 ${rec.calls[0].url}`)
  assert.ok(rec.calls[1].url.endsWith('/usage?days=1'), `下界应收敛，实际 ${rec.calls[1].url}`)
  assert.ok(rec.calls[2].url.endsWith('/usage?days=7'), `缺省应为 7 天，实际 ${rec.calls[2].url}`)
})

test('/api/wp/models/usage 读路径：大脑层不可达 → 200 + available:false（不把读不到渲染成「零用量」）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/models/usage' })
    assert.equal(res.status, 200, '读路径不可达走降级，不是故障码')
    assert.equal(res.json.data.available, false)
    assert.deepEqual(res.json.data.daily, [])
    assert.ok(String(res.json.data.reason).includes('大脑层不可用'), '必须说明是大脑层不可用')
    // 关键：降级时**不回** `totals: {calls: 0}` —— 那会被页面渲染成"这周没人用"，
    // 与"读不到"是两件事。前端据 `available` 决定显示降级提示还是数字。
    assert.ok(!('calls' in (res.json.data.totals || {})), '降级不得伪造零用量')
  }, { jsonRequest: async () => null })
})

test('/api/wp/models 写路径缺控制令牌 → 401，且绝不触达上游', async () => {
  const rec = recordingModelsRequest(() => ({ item: { id: 1 } }))
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models',
      headers: { origin: ALLOWED_ORIGIN },
      body: { config_key: 'generate', name: 'x', enabled: true },
    })
    assert.equal(res.status, 401)
    assert.equal(res.json.code, 'AGENT_UNAUTHORIZED')
    assert.equal(res.json.details.guard, 'control-token')
  }, { jsonRequest: rec.jsonRequest })
  assert.equal(rec.calls.length, 0, '鉴权失败必须在下游之前拦住，不能先写库再报 401')
})

test('/api/wp/models 写路径来源不在白名单 → 403，且绝不触达上游', async () => {
  const rec = recordingModelsRequest(() => ({ item: { id: 1 } }))
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models',
      headers: { origin: 'http://evil.example.com', 'x-wp-control-token': TOKEN },
      body: { config_key: 'generate', name: 'x', enabled: true },
    })
    assert.equal(res.status, 403)
    assert.equal(res.json.code, 'AGENT_FORBIDDEN')
    assert.equal(res.json.details.guard, 'origin-whitelist')
  }, { jsonRequest: rec.jsonRequest })
  assert.equal(rec.calls.length, 0, '来源非法必须在下游之前拦住')
})

test('POST /api/wp/models 带令牌 → 转发请求体并回传 reload 摘要', async () => {
  const created = { id: 7, config_key: 'generate', name: '主模型', enabled: true }
  const rec = recordingModelsRequest(() => ({ item: created, reload: { ok: true, roles: ['generate'], count: 1 } }))
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models',
      headers: { 'x-tenant-id': 'acme', 'x-actor': 'alice', ...CONTROL_HEADERS },
      body: {
        config_key: 'generate', name: '主模型',
        base_url: 'https://api.deepseek.com/v1', model: 'deepseek-chat',
        api_key: 'sk-secret', enabled: true,
      },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.item.id, 7)
    assert.equal(res.json.data.reload.count, 1, '必须回传 reload 摘要，否则「配了没生效」无从判断')
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls[0]
  assert.equal(call.method, 'POST')
  assert.ok(call.url.endsWith('/api/nlp/models'), '创建应打到 /api/nlp/models')
  assert.equal(call.body.api_key, 'sk-secret', '凭据应透传给上游（由上游加密落库）')
  assert.equal(call.headers['X-Tenant-Id'], 'acme')
  assert.equal(call.headers['X-Actor'], 'alice', '操作者应透传，便于审计归属')
})

test('PATCH /api/wp/models/{id} 只转发明示字段（BFF 不补默认值，否则会清空 base_url）', async () => {
  const rec = recordingModelsRequest(() => ({ item: { id: 12, enabled: false } }))
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'PATCH', path: '/api/wp/models/12',
      headers: CONTROL_HEADERS,
      body: { enabled: false },
    })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls[0]
  assert.equal(call.method, 'PATCH')
  assert.ok(call.url.endsWith('/api/nlp/models/12'), `应打到 nlp 配置详情，实际 ${call.url}`)
  assert.deepEqual(Object.keys(call.body), ['enabled'],
    '局部更新必须原样转发；BFF 若补上 base_url/model 默认值会把既有配置清空')
})

test('POST /api/wp/models/reload 必须走固定段，不能被 {model_id} 参数路由当成 id=reload', async () => {
  const rec = recordingModelsRequest(() => ({ reload: { ok: true }, llm: {} }))
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models/reload', headers: CONTROL_HEADERS, body: {},
    })
    assert.equal(res.status, 200)
  }, { jsonRequest: rec.jsonRequest })

  assert.equal(rec.calls.length, 1)
  assert.ok(rec.calls[0].url.endsWith('/api/nlp/models/reload'),
    `重载必须打到 /models/reload，实际 ${rec.calls[0].url} —— 被参数路由吃掉会发出一个必然失败的更新请求`)
})

test('POST /api/wp/models/{id}/test 透传 timeout_ms 并使用探测档预算', async () => {
  const rec = recordingModelsRequest(() => ({ supported: true, ok: true, latency_ms: 42 }))
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models/12/test?timeout_ms=3000',
      headers: CONTROL_HEADERS, body: {},
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.latency_ms, 42)
  }, { jsonRequest: rec.jsonRequest })

  const call = rec.calls[0]
  assert.ok(call.url.includes('/api/nlp/models/12/test?timeout_ms=3000'), `探测参数未透传：${call.url}`)
  assert.ok(call.timeoutMs > 3000, 'BFF 出站预算应大于上游探测预算，否则本地先超时而上游还在正常等待')
})

test('上游业务拒绝（409 角色内重名）原样透传 409 + code，不压成 503', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models',
      headers: CONTROL_HEADERS,
      body: { config_key: 'generate', name: '主模型', enabled: true },
    })
    assert.equal(res.status, 409, '业务拒绝必须保留原状态码，前端才能区分「重名」与「服务挂了」')
    assert.equal(res.json.code, 'AGENT_DUPLICATE')
    assert.equal(res.json.details.source, 'nlp-service')
  }, {
    jsonRequest: async () => {
      throw upstreamError(409, {
        code: 'AGENT_DUPLICATE',
        message: '同一角色下已存在同名配置：主模型',
        details: { config_key: 'generate' },
      })
    },
  })
})

test('写路径上游不可达 → 503（绝不 200 假装配置成功）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models',
      headers: CONTROL_HEADERS,
      body: { config_key: 'generate', name: '主模型', enabled: true },
    })
    assert.equal(res.status, 503, '写路径静默返回 200 会让「配了没生效」变成不可见的故障')
    assert.equal(res.json.code, 'AGENT_BUS_UNAVAILABLE')
    assert.equal(res.json.details.source, 'nlp-service')
  }, { jsonRequest: async () => null })
})

test('GET /api/wp/models/{id} → 405 且 Allow 头只列出 PATCH/DELETE', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/models/12' })
    assert.equal(res.status, 405, '路径存在但方法不支持 → 405（不是 404）')
    assert.equal(res.json.code, 'AGENT_METHOD_NOT_ALLOWED')
    assert.equal(res.headers.allow, 'PATCH, DELETE')
  }, { jsonRequest: async () => ({}) })
})

test('ROUTE_GUARD 与固定段派生：/models/reload 只允许 POST，且被登记为保留段', () => {
  assert.deepEqual(ROUTE_GUARD.allowedMethods('/api/wp/models/reload'), ['POST'])
  assert.ok(MODEL_RESERVED_SEGMENTS.has('reload'), 'reload 必须登记为保留段，否则会被参数路由吃掉')
  // `/models/usage` 是**只读固定段**，与 reload 同一类陷阱：不登记为保留段时，
  // `POST /models/usage` 会被当作"更新 id=usage 的配置"发出去，而 ROUTE_GUARD 判 405。
  assert.deepEqual(ROUTE_GUARD.allowedMethods('/api/wp/models/usage'), ['GET'])
  assert.ok(MODEL_RESERVED_SEGMENTS.has('usage'), 'usage 必须登记为保留段，否则会被参数路由吃掉')
})

test('OVERVIEW_GAPS 不再声称 models 未实现（/models 已落地，留着就是失真）', () => {
  assert.ok(!OVERVIEW_GAPS.includes('models'),
    '/overview 的 gaps 用于告诉前端「该域仍是 Mock」；/models 已实现却仍登记，等于让前端白标降级')
})

// ─────────── 写端点统一鉴权回归（2026-09-20） ───────────
// 背景：此前 19 个 POST 端点中只有 2 个自带鉴权校验，模型配置 / 记忆写入等写路径可被
// 「简单请求」绕过（CSRF）。现在鉴权收敛到路由层唯一入口，以下用例锁定该不变量。

test('路由层统一鉴权：所有写方法在鉴权前不匹配路由（未带令牌 -> 401 而非 404）', async () => {
  await withServer(async ({ server }) => {
    // 路径根本不存在，仍必须先被鉴权拦下 —— 证明鉴权发生在路由匹配之前，不泄露路由存在性
    const res = await request(server, {
      method: 'POST', path: '/api/wp/definitely-not-a-route', headers: { origin: ALLOWED_ORIGIN },
    })
    assert.equal(res.status, 401)
    assert.equal(res.json.code, 'AGENT_UNAUTHORIZED')
  }, { jsonRequest: async () => ({ ok: true }) })
})

test('路由层统一鉴权：代表性写端点未带控制令牌时全部被拒（不得出现无鉴权写入后门）', async () => {
  const writePaths = [
    '/api/wp/knowledge',
    '/api/wp/knowledge/search',
    '/api/wp/brain/ask',
    '/api/wp/brain/memory/ingest',
    '/api/wp/brain/memory/compress',
    '/api/wp/session',
    '/api/wp/session/sess-1/ask',
    '/api/wp/models',
    '/api/wp/models/reload',
    '/api/wp/models/m-1/test',
    '/api/wp/tools/execute',
    '/api/wp/middleware/redis/start',
  ]
  await withServer(async ({ server }) => {
    for (const path of writePaths) {
      const res = await request(server, {
        method: 'POST', path, body: {}, headers: { origin: ALLOWED_ORIGIN },
      })
      assert.equal(res.status, 401, path + ' 未带控制令牌必须 401')
      assert.equal(res.json.code, 'AGENT_UNAUTHORIZED', path + ' 错误码必须为 AGENT_UNAUTHORIZED')
    }
  }, { jsonRequest: async () => ({ ok: true }) })
})

test('路由层统一鉴权：来源越权时同样在路由前被拒（403）', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/models', body: {},
      headers: { origin: 'http://evil.example.com', 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 403)
    assert.equal(res.json.code, 'AGENT_FORBIDDEN')
  }, { jsonRequest: async () => ({ ok: true }) })
})

test('GET /api/wp/services 返回应用服务目录与控制开关', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'GET', path: '/api/wp/services' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.enabled, true)
    assert.equal(res.json.data.control_enabled, true)
    assert.equal(res.json.data.items.length, 9)
    assert.equal(res.json.data.items.find(item => item.key === 'sense-service').port, 8082)
    assert.equal(res.json.data.items.find(item => item.key === 'wp-bff').controllable, false)
  })
})

test('应用服务 start 只执行目录内固定命令', async () => {
  const appServices = {
    demo: { name: 'Demo', role: '测试服务', port: 65531, kind: 'node', cwd: ['.'], args: ['-e', 'setTimeout(() => {}, 1000)'], can_control: true },
  }
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/services/demo/start',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.state, 'starting')
    assert.equal(spawnCalls.length, 1)
    assert.deepEqual(spawnCalls[0].args, appServices.demo.args)
  }, { appServices })
})

test('外部启动的服务拒绝由 BFF 停止（不猜 PID）', async () => {
  const appServices = {
    demo: { name: 'Demo', role: '测试服务', port: 65532, kind: 'node', cwd: ['.'], args: ['server.js'], can_control: true },
  }
  await withServer(async ({ server }) => {
    const res = await request(server, {
      method: 'POST',
      path: '/api/wp/services/demo/stop',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
    assert.equal(res.status, 409)
    assert.equal(res.json.code, 'AGENT_CONFLICT')
    assert.equal(res.json.details.reason, 'externally_managed')
  }, { appServices, probeImpl: async () => ({ up: true, latencyMs: 1 }) })
})

test('wp-bff 自身不允许通过控制页停止', async () => {
  const res = await withServer(async ({ server }) => {
    return request(server, {
      method: 'POST',
      path: '/api/wp/services/wp-bff/start',
      headers: { origin: ALLOWED_ORIGIN, 'x-wp-control-token': TOKEN },
    })
  })
  assert.equal(res.status, 403)
  assert.equal(res.json.code, 'AGENT_FORBIDDEN')
})

test('GET /api/wp/collab/domains 代理协作域列表', async () => {
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'GET', path: '/api/wp/collab/domains' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.total, 1)
    assert.equal(res.json.data.items[0].domain_id, 'dom-a')
  }, { jsonRequestMeta: async () => ({ ok: true, data: { data: { total: 1, items: [{ domain_id: 'dom-a' }] } } }) })
})

test('GET /api/wp/collab/{domain_id} 映射真实聚合为协作视图', async () => {
  const upstream = {
    data: {
      domain_id: 'dom-a', name: 'demo', state: 'active', concurrency_limit: 4,
      progress: 60, member_count: 4, stale_count: 1, total_weight: 4,
      updated_at: '2026-09-20T06:00:03Z',
      members: [
        { member_id: 'agent-a', progress: 90, state: 'working', weight: 1, reported_at: '2026-09-20T06:00:01Z' },
        { member_id: 'agent-b', progress: 30, state: 'stale', weight: 1, reported_at: '2026-09-20T05:59:00Z' },
        { member_id: 'agent-c', progress: 45, state: 'blocked', weight: 1, reported_at: '2026-09-20T06:00:02Z' },
        { member_id: 'agent-d', progress: 0, state: 'idle', weight: 1, reported_at: '2026-09-20T06:00:03Z' },
      ],
    },
  }
  await withServer(async ({ server }) => {
    const res = await request(server, { method: 'GET', path: '/api/wp/collab/dom-a' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.domain_id, 'dom-a')
    assert.equal(res.json.data.progress, 60)
    assert.equal(res.json.data.agents.length, 4)
    assert.equal(res.json.data.messages.length, 4)
    assert.equal(res.json.data.agents[1].state, 'blocked')
    assert.equal(res.json.data.agents[2].state, 'blocked')
    assert.equal(res.json.data.agents[3].state, 'waiting')
    assert.equal(res.json.data.data_quality.source, 'collab-bus-heartbeat')
    assert.ok(res.json.data.data_quality.real_fields.includes('agents.progress'))
    assert.ok(res.json.data.data_quality.synthetic_fields.includes('artifacts'))
  }, { jsonRequestMeta: async () => ({ ok: true, data: upstream }) })
})

test('collab-bus 不可用时协作域代理降级可见', async () => {
  await withServer(async ({ server }) => {
    const list = await request(server, { method: 'GET', path: '/api/wp/collab/domains' })
    assert.equal(list.status, 200)
    assert.equal(list.json.data.available, false)
    assert.equal(list.json.data.reason, 'unreachable')
    const one = await request(server, { method: 'GET', path: '/api/wp/collab/dom-a' })
    assert.equal(one.status, 200)
    assert.equal(one.json.data.available, false)
  }, { jsonRequestMeta: async () => ({ ok: false, reason: 'unreachable', data: null }) })
})

test('Alertmanager webhook 使用独立令牌落盘并可查询最近告警', async () => {
  const alertPath = `${OS_TMP}/wp-bff-alert-test-${Date.now()}.jsonl`
  const payload = {
    status: 'firing',
    groupKey: 'test-group',
    commonLabels: { alertname: 'LifeformAlertDeliveryCheck', severity: 'info' },
    alerts: [{
      status: 'firing',
      fingerprint: 'fp-test-1',
      startsAt: '2026-09-21T10:00:00Z',
      labels: { alertname: 'LifeformAlertDeliveryCheck', severity: 'info' },
      annotations: { summary: 'real local alert delivery' },
    }],
  }
  await withServer(async ({ server }) => {
    const denied = await request(server, { method: 'POST', path: '/api/wp/alerts/alertmanager', body: payload })
    assert.equal(denied.status, 401)
    assert.equal(denied.json.code, 'AGENT_UNAUTHORIZED')

    const accepted = await request(server, {
      method: 'POST', path: '/api/wp/alerts/alertmanager', body: payload,
      headers: { authorization: 'Bearer test-alert-token' },
    })
    assert.equal(accepted.status, 200)
    assert.equal(accepted.json.data.accepted, true)
    assert.equal(accepted.json.data.alert_count, 1)
    assert.equal(accepted.json.data.sink, 'wp-bff-jsonl')

    const recent = await request(server, { path: '/api/wp/alerts?limit=5' })
    assert.equal(recent.status, 200)
    assert.equal(recent.json.data.total, 1)
    assert.equal(recent.json.data.items[0].alerts[0].labels.alertname, 'LifeformAlertDeliveryCheck')
  }, { alertPath, alertToken: 'test-alert-token' })
})

test('Phase 6 Blocking：在线 Agent 从真实协作心跳派生', async () => {
  const jsonRequestMeta = async (url, opts = {}) => {
    const parsed = new URL(url)
    if (parsed.pathname === '/api/collab/domains') {
      return { ok: true, data: { total: 1, items: [{ domain_id: 'dom-a', name: 'demo', state: 'active', progress: 60 }] } }
    }
    if (parsed.pathname === '/api/collab/domains/dom-a' && (!opts.method || opts.method === 'GET')) {
      return {
        ok: true,
        data: {
          data: {
            domain_id: 'dom-a', name: 'demo', state: 'active', progress: 60, member_count: 2,
            members: [
              { member_id: 'agent-a', progress: 90, state: 'working', weight: 1 },
              { member_id: 'agent-b', progress: 30, state: 'stale', weight: 1 },
            ],
          },
        },
      }
    }
    throw new Error('unexpected upstream: ' + parsed.pathname)
  }
  await withServer(async ({ server }) => {
    const res = await request(server, { path: '/api/wp/agents/online' })
    assert.equal(res.status, 200)
    assert.equal(res.json.data.available, true)
    assert.equal(res.json.data.total, 2)
    assert.equal(res.json.data.items[0].state, 'run')
    assert.equal(res.json.data.items[1].state, 'wait')
    assert.equal(res.json.data.items[0].task, 'dom-a')
  }, { jsonRequestMeta })
})

test('Phase 6 Blocking：任务列表/创建/详情与结果按协作域真实映射', async () => {
  const domain = {
    domain_id: 'dom-a', name: '真实任务', state: 'active', progress: 60, member_count: 2,
    updated_at: '2026-09-21T10:00:00Z',
    members: [
      { member_id: 'agent-a', progress: 60, state: 'working', weight: 1 },
      { member_id: 'agent-b', progress: 60, state: 'working', weight: 1 },
    ],
  }
  const jsonRequestMeta = async (url, opts = {}) => {
    const parsed = new URL(url)
    if (parsed.pathname === '/api/collab/domains' && (!opts.method || opts.method === 'GET')) {
      return { ok: true, data: { total: 1, items: [domain] } }
    }
    if (parsed.pathname === '/api/collab/domains' && opts.method === 'POST') {
      return { ok: true, data: { data: { ...domain, domain_id: 'dom-created', name: opts.body.name } } }
    }
    if (parsed.pathname === '/api/collab/domains/dom-a') return { ok: true, data: { data: domain } }
    throw new Error('unexpected upstream: ' + parsed.pathname)
  }
  await withServer(async ({ server }) => {
    const list = await request(server, { path: '/api/wp/tasks?state=running' })
    assert.equal(list.status, 200)
    assert.equal(list.json.data.items[0].task_id, 'dom-a')
    assert.equal(list.json.data.items[0].state, 'running')

    const created = await request(server, { method: 'POST', path: '/api/wp/tasks', body: { title: '新任务' } })
    assert.equal(created.status, 201)
    assert.equal(created.json.data.task_id, 'dom-created')
    assert.equal(created.json.data.title, '新任务')

    const detail = await request(server, { path: '/api/wp/tasks/dom-a' })
    assert.equal(detail.status, 200)
    assert.equal(detail.json.data.task.task_id, 'dom-a')
    assert.equal(detail.json.data.agents.length, 2)

    const results = await request(server, { path: '/api/wp/results/dom-a' })
    assert.equal(results.status, 200)
    assert.equal(results.json.data.items.length, 0)
    assert.equal(results.json.data.artifact_source_connected, false)
    assert.equal(results.json.data.reason, 'phase6_artifact_store_not_connected')
  }, { jsonRequestMeta })
})

test('Phase 6 Blocking：专家/审批注册表未接入时不伪造成功', async () => {
  await withServer(async ({ server }) => {
    const experts = await request(server, { path: '/api/wp/experts' })
    assert.equal(experts.status, 200)
    assert.equal(experts.json.data.available, false)
    assert.equal(experts.json.data.reason, 'phase6_expert_registry_not_connected')

    const approvals = await request(server, { path: '/api/wp/approvals' })
    assert.equal(approvals.status, 200)
    assert.equal(approvals.json.data.available, false)
    assert.equal(approvals.json.data.reason, 'phase6_approval_registry_not_connected')

    const decision = await request(server, {
      method: 'POST', path: '/api/wp/approvals/A-1/decision', body: { decision: 'approved' },
    })
    assert.equal(decision.status, 503)
    assert.equal(decision.json.code, 'AGENT_UPSTREAM_UNAVAILABLE')
    assert.equal(decision.json.details.side_effects, false)
  })
})

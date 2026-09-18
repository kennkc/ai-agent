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

const { createServer, MIDDLEWARE, resolveAllowedOrigins, queryTerms, buildSnippet } = require('../server')

const TOKEN = 'test-control-token-0123456789'
const ALLOWED_ORIGIN = 'http://127.0.0.1:3001'
const OS_TMP = process.env.TEMP || process.env.TMPDIR || '/tmp'

function request(server, { method = 'GET', path = '/', headers = {}, body = null } = {}) {
  const { port } = server.address()
  const payload = body == null ? null : Buffer.from(JSON.stringify(body), 'utf8')
  const finalHeaders = payload
    ? { 'Content-Type': 'application/json', 'Content-Length': payload.length, ...headers }
    : headers
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

test('控制端点缺少令牌 -> 401，且不触发任何进程', async () => {
  await withServer(async ({ server, spawnCalls }) => {
    const res = await request(server, {
      method: 'POST', path: '/api/wp/middleware/redis/start', headers: { origin: ALLOWED_ORIGIN },
    })
    assert.equal(res.status, 401)
    assert.match(res.json.error, /控制令牌/)
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
    assert.match(res.json.error, /来源/)
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
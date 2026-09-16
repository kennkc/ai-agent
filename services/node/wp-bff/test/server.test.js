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

const { createServer, MIDDLEWARE, resolveAllowedOrigins } = require('../server')

const TOKEN = 'test-control-token-0123456789'
const ALLOWED_ORIGIN = 'http://127.0.0.1:3001'
const OS_TMP = process.env.TEMP || process.env.TMPDIR || '/tmp'

function request(server, { method = 'GET', path = '/', headers = {} } = {}) {
  const { port } = server.address()
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path, headers }, res => {
      let body = ''
      res.on('data', chunk => { body += chunk })
      res.on('end', () => {
        let json = null
        try { json = JSON.parse(body) } catch { /* 非 JSON 响应 */ }
        resolve({ status: res.statusCode, headers: res.headers, body, json })
      })
    })
    req.on('error', reject)
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
'use strict'

const path = require('node:path')
const fs = require('node:fs')

const APP_SERVICES = {
  'gateway-service': { name: 'Gateway', role: 'API 网关 · JWT / 路由', port: 8080, kind: 'maven', cwd: ['services', 'java'], args: ['-pl', 'gateway-service', 'spring-boot:run'], can_control: true },
  'session-manager': { name: 'Session Manager', role: '会话状态机 · 编排入口', port: 8081, kind: 'maven', cwd: ['services', 'java'], args: ['-pl', 'session-manager', 'spring-boot:run'], can_control: true },
  'sense-service': { name: 'Sense Service', role: '五感渠道 · 采集', port: 8082, kind: 'maven', cwd: ['services', 'java'], args: ['-pl', 'sense-service', 'spring-boot:run'], can_control: true },
  'nlp-service': { name: 'NLP Service', role: '意图 / 大脑 / RAG', port: 8000, kind: 'python', cwd: ['services', 'python', 'nlp-service'], args: ['-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8000'], can_control: true },
  'body-service': { name: 'Body Service', role: '知识库 · 检索 · 重排', port: 8083, kind: 'maven', cwd: ['services', 'java'], args: ['-pl', 'body-service', 'spring-boot:run'], can_control: true },
  'tool-executor': { name: 'Tool Executor', role: '工具执行 · 沙箱 · 审计', port: 8084, kind: 'maven', cwd: ['services', 'java'], args: ['-pl', 'tool-executor', 'spring-boot:run'], can_control: true },
  'collab-bus': { name: 'Collab Bus', role: '多 Agent 协作总线', port: 8085, kind: 'maven', cwd: ['services', 'java'], args: ['-pl', 'collab-bus', 'spring-boot:run'], can_control: true },
  'wp-bff': { name: 'WP BFF', role: '工作平台控制面', port: 8090, kind: 'node', cwd: ['services', 'node', 'wp-bff'], args: ['server.js'], can_control: false },
  'work-platform': { name: 'Work Platform', role: 'Vue 3 前台', port: 3001, kind: 'npm', cwd: ['web', 'work-platform'], args: ['run', 'dev'], can_control: true },
}

function createAppServiceController(options) {
  const repoRoot = options.repoRoot
  const spawnImpl = options.spawnImpl
  const probeImpl = options.probeImpl
  const audit = options.audit
  const nowTime = options.nowTime
  const appServices = options.appServices || APP_SERVICES
  const appProcesses = new Map()
  const controlEnabled = process.env.WP_BFF_APP_CONTROL !== 'false'

  function commandFor(meta) {
    if (meta.kind === 'maven') return process.platform === 'win32' ? 'mvn.cmd' : 'mvn'
    if (meta.kind === 'npm') return process.platform === 'win32' ? 'npm.cmd' : 'npm'
    if (meta.kind === 'python') {
      const venv = process.platform === 'win32'
        ? path.join(repoRoot, 'services', 'python', 'venv', 'Scripts', 'python.exe')
        : path.join(repoRoot, 'services', 'python', 'venv', 'bin', 'python')
      return fs.existsSync(venv) ? venv : 'python'
    }
    if (meta.kind === 'node') return process.execPath
    throw new Error('unsupported service kind: ' + meta.kind)
  }

  function nodeFor(key, probe) {
    const meta = appServices[key]
    const controlled = appProcesses.has(key)
    const controlStatus = !meta.can_control || !controlEnabled ? 'disabled' : controlled ? 'controlled' : 'external'
    return {
      key: key,
      name: meta.name,
      role: meta.role,
      port: meta.port,
      state: probe.state,
      controllable: Boolean(meta.can_control && controlEnabled),
      controlled: controlled,
      control_status: controlStatus,
      pid: controlled ? appProcesses.get(key).pid : null,
      metrics: [
        { label: '探针', value: probe.latencyMs + 'ms' },
        { label: '端口', value: String(meta.port) },
        { label: '控制', value: controlStatus === 'controlled' ? '由 BFF 启动' : controlStatus === 'external' ? '外部进程 · 只读' : '只监控' },
      ],
      last_check: nowTime(),
    }
  }

  async function probe(key) {
    const meta = appServices[key]
    const result = await probeImpl(meta.port)
    const tracked = appProcesses.get(key)
    if (tracked && tracked.child.exitCode !== null) appProcesses.delete(key)
    return { state: result.up ? 'up' : 'down', latencyMs: result.latencyMs }
  }

  function start(key) {
    const meta = appServices[key]
    const command = commandFor(meta)
    const cwd = path.resolve(repoRoot, ...meta.cwd)
    const logDir = path.join(repoRoot, 'logs', 'services')
    fs.mkdirSync(logDir, { recursive: true })
    const logPath = path.join(logDir, key + '.log')
    const fd = fs.openSync(logPath, 'a')
    const child = spawnImpl(command, meta.args, {
      cwd: cwd,
      windowsHide: true,
      detached: process.platform !== 'win32',
      stdio: ['ignore', fd, fd],
    })
    try { fs.closeSync(fd) } catch (ignored) { /* child inherited fd */ }
    appProcesses.set(key, { child: child, pid: child.pid, startedAt: Date.now(), logPath: logPath })
    child.on('close', () => {
      if (appProcesses.get(key) && appProcesses.get(key).child === child) appProcesses.delete(key)
    })
    child.on('error', error => audit('SERVICE_SPAWN_FAIL', key, String(error)))
    audit('SERVICE_START', key, command + ' ' + meta.args.join(' '))
    return child
  }

  async function stop(key) {
    const tracked = appProcesses.get(key)
    if (!tracked || !tracked.pid) return false
    const pid = tracked.pid
    if (process.platform === 'win32') {
      spawnImpl('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true })
    } else {
      try { process.kill(-pid, 'SIGTERM') } catch (ignored) { try { process.kill(pid, 'SIGTERM') } catch (ignoredAgain) { /* already gone */ } }
    }
    appProcesses.delete(key)
    audit('SERVICE_STOP', key, 'pid=' + pid)
    return true
  }

  async function servicesResponse() {
    const keys = Object.keys(appServices)
    const entries = await Promise.all(keys.map(async key => ({ key: key, probe: await probe(key) })))
    const items = entries.map(entry => nodeFor(entry.key, entry.probe))
    const up = items.filter(item => item.state === 'up').length
    return {
      enabled: true,
      control_enabled: controlEnabled,
      checked_at: nowTime(),
      probe_mode: 'tcp',
      summary: { total: items.length, up: up, down: items.length - up },
      items: items,
    }
  }

  async function control(key, action) {
    if (!appServices[key]) {
      return { status: 403, error: { code: 'AGENT_FORBIDDEN', message: 'service "' + key + '" 不在白名单内', details: { key: key } } }
    }
    const meta = appServices[key]
    if (!meta.can_control) {
      return { status: 403, error: { code: 'AGENT_FORBIDDEN', message: meta.name + ' 只允许监控，不允许由 wp-bff 停止', details: { key: key } } }
    }
    if (!controlEnabled) {
      return { status: 403, error: { code: 'AGENT_FORBIDDEN', message: '应用服务控制已关闭（WP_BFF_APP_CONTROL=false）', details: { key: key } } }
    }
    const probeState = await probe(key)
    if (action === 'start' && probeState.state === 'up') return { status: 200, data: nodeFor(key, probeState) }
    if (action === 'stop' && probeState.state === 'down') return { status: 200, data: nodeFor(key, probeState) }
    if (action === 'start') {
      start(key)
      return { status: 200, data: nodeFor(key, { state: 'starting', latencyMs: probeState.latencyMs }) }
    }
    const stopped = await stop(key)
    if (!stopped) {
      return { status: 409, error: { code: 'AGENT_CONFLICT', message: '该服务不是由 wp-bff 启动，无法安全推断 PID；请使用原启动终端或系统服务管理器停止', details: { key: key, pid: null, reason: 'externally_managed' } } }
    }
    return { status: 200, data: nodeFor(key, { state: 'stopping', latencyMs: probeState.latencyMs }) }
  }

  return { servicesResponse: servicesResponse, control: control, nodeFor: nodeFor }
}

module.exports = { APP_SERVICES: APP_SERVICES, createAppServiceController: createAppServiceController }

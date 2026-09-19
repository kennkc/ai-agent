import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '..', '..')
const wpBffTokenFile = path.join(repoRoot, 'services', 'node', 'wp-bff', 'logs', 'wp-bff-control-token')

/**
 * 读取 wp-bff 控制令牌（仅服务端代理使用，绝不进入浏览器 bundle）。
 * 优先环境变量 WP_BFF_CONTROL_TOKEN，其次读取 wp-bff 启动时落盘的令牌文件。
 */
function readControlToken(): string {
  const fromEnv = (process.env.WP_BFF_CONTROL_TOKEN || '').trim()
  if (fromEnv) return fromEnv
  try {
    return fs.readFileSync(wpBffTokenFile, 'utf8').trim()
  } catch {
    return ''
  }
}

const BFF_TARGET = (process.env.WP_BFF_URL || 'http://127.0.0.1:8090').replace(/\/+$/, '')
const GATEWAY_TARGET = (process.env.WP_GATEWAY_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '')

/** 已告警过的目标（启动探活与代理错误共用，避免同一问题刷屏） */
const warnedTargets = new Set<string>()

/**
 * 代理目标可达性自检。
 *
 * 为什么需要它：`WP_BFF_URL`（前端代理目标）与 `WP_BFF_PORT`（wp-bff 监听端口）是**两个
 * 互不校验的环境变量**。一旦对不上，页面不会报错 —— 请求 500 后被 `api/provider.ts` 的
 * safe() 兜住，逐个 scope 登记降级并回落到 Mock，结果是「满屏演示数据 + 顶栏一堆降级」，
 * 从页面上根本看不出这是环境问题。Phase 5 实测踩过：BFF 落在 8090，代理却指向别处。
 *
 * 本插件把这种静默失败搬到终端：启动探一次 + 每次代理出错时告警（每条目标只响一次，不刷屏）。
 */
function bffReachabilityGate(): Plugin {
  function probe(target: string, label: string, pathname: string) {
    return new Promise<void>(resolve => {
      const req = http.get(`${target}${pathname}`, { timeout: 1500 }, res => {
        res.resume()
        if (res.statusCode && res.statusCode < 500) {
          console.log(`  \x1b[32m✔\x1b[0m ${label} 可达: ${target} (HTTP ${res.statusCode})`)
        } else {
          warn(`${label} 返回 HTTP ${res.statusCode}`, target)
        }
        resolve()
      })
      req.on('timeout', () => { req.destroy(); warn(`${label} 探活超时`, target); resolve() })
      req.on('error', () => { warn(`${label} 不可达`, target); resolve() })
    })
  }

  function warn(what: string, target: string) {
    if (warnedTargets.has(target)) return
    warnedTargets.add(target)
    console.warn(
      `\n\x1b[33m⚠ ${what}: ${target}\x1b[0m\n` +
      `  前端 API 模式下所有请求都会失败、兜底回落 Mock —— 页面顶栏会显示「API · 降级 N」，\n` +
      `  但那是**环境问题**，不是数据本身如此。请确认服务已启动，或改代理目标：\n` +
      `    WP_BFF_URL   -> wp-bff      (默认 8090)  cd services/node/wp-bff && WP_BFF_PORT=8090 node server.js\n` +
      `    WP_GATEWAY_URL -> gateway   (默认 8080)\n`,
    )
  }

  return {
    name: 'wp-bff-reachability-gate',
    apply: 'serve',
    configureServer(server) {
      server.httpServer?.once('listening', () => {
        void probe(BFF_TARGET, 'wp-bff', '/api/wp/healthz')
        void probe(GATEWAY_TARGET, 'gateway-service', '/actuator/health')
      })
    },
  }
}

/** 从代理错误里提取目标，用于告警去重 */
function targetOf(err: unknown, fallback: string): string {
  const anyErr = err as { address?: string; port?: number; code?: string; message?: string } | undefined
  if (anyErr?.address && anyErr?.port) return `http://${anyErr.address}:${anyErr.port}`
  const matched = /^([a-z]+:\/\/[^/]+)/i.exec(anyErr?.message || '')
  return matched ? matched[1] : fallback
}

export default defineConfig({
  plugins: [vue(), bffReachabilityGate()],
  server: {
    // host: true => 监听 ::（双栈），localhost 的 IPv6 (::1) 与 IPv4 (127.0.0.1) 均可达
    host: true,
    allowedHosts: true,
    // 开发代理目标可覆盖：换机 / 改端口时无需改代码（默认对齐 docker-compose 与本地脚本端口）
    port: Number(process.env.WP_DEV_PORT || 3001),
    proxy: {
      '/api/wp': {
        target: BFF_TARGET,
        changeOrigin: true,
        configure: proxy => {
          proxy.on('proxyReq', proxyReq => {
            // wp-bff 控制端点要求 Origin 白名单 + 控制令牌；
            // Origin 由浏览器原样带到 wp-bff 校验，代理只负责注入令牌。
            // 通过非 127.0.0.1/localhost 访问开发服务器时，需把该来源加入 WP_BFF_ALLOWED_ORIGINS。
            const token = readControlToken()
            if (token) proxyReq.setHeader('X-WP-Control-Token', token)
          })
          // 代理失败不能只在浏览器里表现成一次静默降级 —— 终端必须同时喊出来
          proxy.on('error', err => {
            const target = targetOf(err, BFF_TARGET)
            if (warnedTargets.has(target)) return
            warnedTargets.add(target)
            console.warn(
              `\n\x1b[33m⚠ 代理 /api/wp 请求失败（目标 ${target}）\x1b[0m\n` +
              `  原因: ${(err as Error)?.message || err}\n` +
              `  前端将回落 Mock 并在顶栏标注降级；请确认 wp-bff 已启动在 ${target}。\n`,
            )
          })
        },
      },
      '/api': {
        target: GATEWAY_TARGET,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue'],
          axios: ['axios'],
        },
      },
    },
  },
})
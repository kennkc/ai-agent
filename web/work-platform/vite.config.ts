import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
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

export default defineConfig({
  plugins: [vue()],
  server: {
    // host: true => 监听 ::（双栈），localhost 的 IPv6 (::1) 与 IPv4 (127.0.0.1) 均可达
    host: true,
    allowedHosts: true,
    // 开发代理目标可覆盖：换机 / 改端口时无需改代码（默认对齐 docker-compose 与本地脚本端口）
    port: Number(process.env.WP_DEV_PORT || 3001),
    proxy: {
      '/api/wp': {
        target: process.env.WP_BFF_URL || 'http://127.0.0.1:8090',
        changeOrigin: true,
        configure: proxy => {
          proxy.on('proxyReq', proxyReq => {
            // wp-bff 控制端点要求 Origin 白名单 + 控制令牌；
            // Origin 由浏览器原样带到 wp-bff 校验，代理只负责注入令牌。
            // 通过非 127.0.0.1/localhost 访问开发服务器时，需把该来源加入 WP_BFF_ALLOWED_ORIGINS。
            const token = readControlToken()
            if (token) proxyReq.setHeader('X-WP-Control-Token', token)
          })
        },
      },
      '/api': {
        target: process.env.WP_GATEWAY_URL || 'http://127.0.0.1:8080',
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
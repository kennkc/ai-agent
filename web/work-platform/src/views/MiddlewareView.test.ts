import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const provider = vi.hoisted(() => ({
  getMiddleware: vi.fn(),
  startMiddleware: vi.fn(),
  stopMiddleware: vi.fn(),
}))

vi.mock('../api/provider', () => ({ dataProvider: provider }))

import MiddlewareView from './MiddlewareView.vue'

const liveOverview = {
  enabled: true,
  checked_at: '2026-09-20 15:30:00',
  data_source: 'live',
  summary: { up: 1, down: 1, total: 2 },
  items: [
    { key: 'redis', name: 'Redis', role: '会话热存储', port: 6379, state: 'up', metrics: [], last_check: '刚刚' },
    { key: 'nats', name: 'NATS', role: '神经总线', port: 4222, state: 'down', metrics: [], last_check: '刚刚' },
  ],
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: MiddlewareView },
      { path: '/overview', component: { template: '<div>overview</div>' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(MiddlewareView, {
    global: { plugins: [createPinia(), ElementPlus, router] },
  })
}

describe('MiddlewareView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders live middleware cards and health summary', async () => {
    provider.getMiddleware.mockResolvedValue(liveOverview)
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('中间件监控')
    expect(wrapper.text()).toContain('Redis')
    expect(wrapper.text()).toContain('NATS')
    expect(wrapper.text()).toContain('运行中')
    expect(wrapper.text()).toContain('50%')
  })

  it('renders explicit disabled state when observation service is unavailable', async () => {
    provider.getMiddleware.mockResolvedValue({ enabled: false, data_source: 'mock', items: [] })
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('中间件观测服务未启用')
    expect(wrapper.text()).toContain('演示数据 · BFF 未连接')
  })
})
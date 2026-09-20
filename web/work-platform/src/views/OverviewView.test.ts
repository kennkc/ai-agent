import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const provider = vi.hoisted(() => ({
  mode: 'api',
  getOverview: vi.fn(),
  applySuggestion: vi.fn(),
}))

vi.mock('../api/provider', () => ({ dataProvider: provider }))

import OverviewView from './OverviewView.vue'

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: OverviewView },
      { path: '/overview', component: { template: '<div>overview</div>' } },
      { path: '/middleware', component: { template: '<div>middleware</div>' } },
      { path: '/tracing', component: { template: '<div>tracing</div>' } },
      { path: '/vitals', component: { template: '<div>vitals</div>' } },
      { path: '/collab', component: { template: '<div>collab</div>' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(OverviewView, {
    global: { plugins: [createPinia(), ElementPlus, router] },
  })
}

describe('OverviewView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders today summary, model monitoring and observability from the API payload', async () => {
    provider.getOverview.mockResolvedValue({
      today_summary: {
        date: '2026-09-20',
        headline: '今日真实摘要：协作域已接入 BFF',
        running_index: 91,
        metrics: [{ label: '模型调用', value: '1,280', unit: '次', trend: '+12%', tone: 'success' }],
        events: [{ time: '16:00', title: '真实协作域已选中', desc: 'dom-real', type: 'model' }],
        attention: ['检查 collab-bus 心跳'],
      },
      observability: {
        source: 'wp-bff',
        checked_at: '2026-09-20T08:00:00Z',
        middleware: { up: 5, total: 8, pending: 0, probe_mode: 'tcp' },
        tracing: { enabled: true, services: 6, spans_sampled: 32, recent_errors: 0, p99_ms: 84, p99_basis: 'sampled_recent_traces' },
      },
      gaps: ['models', 'collaboration_metrics'],
    })
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('今日摘要')
    expect(wrapper.text()).toContain('今日真实摘要：协作域已接入 BFF')
    expect(wrapper.text()).toContain('大模型调用监控')
    expect(wrapper.text()).toContain('大模型工作状态')
    expect(wrapper.text()).toContain('OPS 观测区实时摘要 · 真实数据')
    expect(wrapper.text()).toContain('2 项')
    expect(wrapper.text()).toContain('真实协作域已选中')
    wrapper.unmount()
  })

  it('keeps the last available snapshot and marks the load failure explicitly', async () => {
    provider.getOverview.mockRejectedValue(new Error('BFF unavailable'))
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('总览数据加载失败，当前显示最近一次可用快照')
    expect(wrapper.text()).toContain('生命体总览')
    wrapper.unmount()
  })
})

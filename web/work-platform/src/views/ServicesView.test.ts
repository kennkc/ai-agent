import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const provider = vi.hoisted(() => ({
  getServices: vi.fn(),
  startService: vi.fn(),
  stopService: vi.fn(),
}))

vi.mock('../api/provider', () => ({ dataProvider: provider }))

import ServicesView from './ServicesView.vue'

const overview = {
  enabled: true,
  control_enabled: true,
  checked_at: '15:35:00',
  data_source: 'live',
  probe_mode: 'tcp',
  summary: { total: 2, up: 1, down: 1 },
  items: [
    {
      key: 'gateway-service', name: 'Gateway', role: 'API 网关', port: 8080, state: 'up',
      controllable: true, controlled: true, control_status: 'controlled', pid: 1234,
      metrics: [], last_check: '刚刚',
    },
    {
      key: 'work-platform', name: 'Work Platform', role: 'Vue 3 前台', port: 3001, state: 'up',
      controllable: false, controlled: false, control_status: 'external', pid: null,
      metrics: [], last_check: '刚刚',
    },
  ],
}

describe('ServicesView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders real service state and controlled/external distinction', async () => {
    provider.getServices.mockResolvedValue(overview)
    const wrapper = mount(ServicesView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('后台服务控制台')
    expect(wrapper.text()).toContain('Gateway')
    expect(wrapper.text()).toContain('PID 1234')
    expect(wrapper.text()).toContain('外部进程 · 只能监控')
  })

  it('renders read-only state when BFF unavailable', async () => {
    provider.getServices.mockResolvedValue({
      enabled: false, control_enabled: false, checked_at: '', data_source: 'mock',
      summary: { total: 1, up: 0, down: 1 },
      items: [{ key: 'gateway-service', name: 'Gateway', role: 'API 网关', port: 8080, state: 'down', controllable: false, controlled: false, control_status: 'disabled', metrics: [], last_check: '—' }],
    })
    const wrapper = mount(ServicesView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前显示的是服务目录降级数据')
    expect(wrapper.text()).toContain('只读')
  })
})
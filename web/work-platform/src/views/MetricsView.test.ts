import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const provider = vi.hoisted(() => ({ getMetricsOverview: vi.fn() }))
vi.mock('../api/provider', () => ({ dataProvider: provider }))

import MetricsView from './MetricsView.vue'

const overview = {
  available: true,
  window: '15m',
  selected_job: 'all',
  sources: { prometheus: { available: true }, targets: { available: true, total: 8, up: 8 }, alerts: { available: true, active: 0 } },
  summary: {
    targets_up: 8, targets_total: 8, active_alerts: 0, qps: 12.5, error_rate: 0.01,
    avg_latency_ms: 120, max_latency_ms: 800, jvm_heap_used_bytes: 512 * 1024 * 1024,
    jvm_heap_max_bytes: 1024 * 1024 * 1024, jvm_heap_used_ratio: 0.5, jvm_threads: 80,
    gc_pause_avg_ms: 3, gc_pause_max_ms: 12, hikari_active: 4, hikari_max: 10, hikari_pending: 0,
    llm_qps: 0.5, llm_failure_rate: 0, llm_degraded_qps: 0, collab_domains: 2,
    heartbeat_pending: 0, tool_qps: 0.2, tool_failure_rate: 0, tool_circuit_open_qps: 0,
  },
  services: [{ job: 'gateway-service', health: 'up', qps: 10, error_rate: 0, avg_latency_ms: 20, max_latency_ms: 80, heap_used_bytes: 100, heap_max_bytes: 200, heap_used_ratio: 0.5, threads: 20, hikari_active: 2, hikari_max: 5, hikari_pending: 0 }],
  targets: [{ job: 'gateway-service', instance: 'host:8080', health: 'up', scrape_url: '', last_error: '', last_scrape_at: null }],
  alerts: [],
  support: { http_p95: false, http_p95_reason: 'HTTP histogram bucket 未暴露', gc_p95: false, gc_p95_reason: 'GC histogram bucket 未暴露', metric_scope: 'system' },
}

describe('MetricsView', () => {
  beforeEach(() => { vi.clearAllMocks(); provider.getMetricsOverview.mockResolvedValue(overview) })

  it('renders real Prometheus and Micrometer metric panels', async () => {
    const wrapper = mount(MetricsView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('指标监控')
    expect(wrapper.text()).toContain('HTTP 指标')
    expect(wrapper.text()).toContain('JVM / Micrometer')
    expect(wrapper.text()).toContain('数据库连接池')
    expect(wrapper.text()).toContain('Prometheus Targets')
    expect(wrapper.text()).toContain('8/8')
    expect(wrapper.text()).toContain('HTTP P95：未启用')
  })

  it('shows an explicit unavailable state when Prometheus cannot be read', async () => {
    provider.getMetricsOverview.mockResolvedValue({ ...overview, available: false, reason: 'Prometheus 不可达' })
    const wrapper = mount(MetricsView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('指标监控不可读')
    expect(wrapper.text()).toContain('Prometheus 不可达')
  })
})

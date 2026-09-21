import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const provider = vi.hoisted(() => ({
  getModels: vi.fn(),
  getModelUsage: vi.fn(),
  getModelRuntime: vi.fn(),
  saveModel: vi.fn(),
  deleteModel: vi.fn(),
  reloadModels: vi.fn(),
  testModel: vi.fn(),
}))

vi.mock('../api/provider', () => ({ dataProvider: provider }))

import ModelsView from './ModelsView.vue'

const unavailableList = {
  available: false,
  reason: 'nlp-service unavailable',
  items: [],
  by_role: {},
  roles: [],
  providers: [],
  storage: null,
}

const availableList = {
  available: true,
  items: [{
    id: 1,
    config_key: 'generate',
    name: 'OpenRouter Free',
    provider: 'custom',
    base_url: 'https://openrouter.ai/api/v1',
    model: 'openrouter/free',
    api_key_hint: 'sk-***test',
    tier: 'L2',
    max_tokens: 256,
    temperature: 0,
    timeout_ms: 30000,
    routing_weight: 100,
    enabled: true,
  }],
  by_role: { generate: [{
    id: 1,
    config_key: 'generate',
    name: 'OpenRouter Free',
    provider: 'custom',
    base_url: 'https://openrouter.ai/api/v1',
    model: 'openrouter/free',
    api_key_hint: 'sk-***test',
    tier: 'L2',
    max_tokens: 256,
    temperature: 0,
    timeout_ms: 30000,
    routing_weight: 100,
    enabled: true,
  }] },
  roles: [
    { key: 'generate', label: '内容生成', default_tier: 'L2', default_timeout_ms: 20000, description: '最终答案生成' },
  ],
  providers: [{ key: 'custom', default_base_url: '' }],
  storage: { backend: 'postgres', degraded: false },
  llm: { engines: { generate: [{ name: 'generate:OpenRouter Free' }] } },
}

describe('ModelsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    provider.getModelUsage.mockResolvedValue({ available: true, daily: [], by_role: {}, by_model: {} })
    provider.getModelRuntime.mockResolvedValue({
      available: true,
      window: { days: 7 },
      sources: {
        config: { available: true, backend: 'postgres', degraded: false },
        usage: { available: true, degraded: false },
        prometheus: { available: true, complete: true, availability: {}, by_role: {} },
      },
      items: [{
        config_id: 1, config_key: 'generate', role_label: '内容生成', name: 'OpenRouter Free',
        provider: 'custom', model: 'openrouter/free', tier: 'L2', enabled: true, routing_weight: 100,
        runtime_state: 'active',
        probe: { state: 'passed', ok: true, at: '2026-09-21T10:00:00Z', latency_ms: 123, error: '' },
        usage: { calls: 8, failures: 1, attempts: 9, success_rate: 8 / 9, tokens: 1200, avg_latency_ms: 310, share: 1 },
        prometheus: { qps: 0.5, failure_rate: 0.1, p95_latency_ms: 900 },
      }],
      routing: [{
        role: 'generate', role_label: '内容生成', mode: 'role_weight_fallback', primary_config_id: 1,
        candidates: [{ config_id: 1, name: 'OpenRouter Free', provider: 'custom', model: 'openrouter/free', routing_weight: 100, probe_state: 'passed' }],
        usage: { calls: 8, failures: 1, attempts: 9, avg_latency_ms: 310 },
      }],
      unsupported_fields: {
        cost: { available: false, reason: 'price_metadata_not_configured' },
        quality: { available: false, reason: 'evaluation_not_connected' },
        quota: { available: false, reason: 'provider_quota_not_connected' },
        queue: { available: false, reason: 'runtime_gauge_not_connected' },
      },
    })
  })

  it('renders a clear unavailable state instead of pretending there are no configs', async () => {
    provider.getModels.mockResolvedValue(unavailableList)
    const wrapper = mount(ModelsView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('模型接入配置')
    expect(wrapper.text()).toContain('大脑层不可用：模型配置读不到')
    expect(wrapper.text()).toContain('不代表“尚未配置模型”')
  })

  it('renders a configured OpenRouter-compatible model and assembled role count', async () => {
    provider.getModels.mockResolvedValue(availableList)
    const wrapper = mount(ModelsView, { global: { plugins: [createPinia(), ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('[data-testid="model-create"]').text()).toContain('新增模型配置')
    expect(wrapper.text()).toContain('OpenRouter Free')
    expect(wrapper.text()).toContain('openrouter/free')
    expect(wrapper.text()).toContain('已装配 1 个引擎')
    expect(wrapper.text()).toContain('模型接入运行态')
    expect(wrapper.text()).toContain('已启用 · 探测通过')
    expect(wrapper.text()).toContain('未接入价格元数据')
    expect(wrapper.text()).not.toContain('演示数据（BFF 未提供该数据域）')
  })
})
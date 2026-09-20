import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const provider = vi.hoisted(() => ({
  getModels: vi.fn(),
  getModelUsage: vi.fn(),
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

    expect(wrapper.text()).toContain('OpenRouter Free')
    expect(wrapper.text()).toContain('openrouter/free')
    expect(wrapper.text()).toContain('已装配 1 个引擎')
  })
})
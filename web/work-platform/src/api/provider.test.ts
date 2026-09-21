import { beforeEach, describe, expect, it, vi } from 'vitest'

const client = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('axios', () => ({
  default: { create: () => client },
}))

describe('dataProvider collaboration wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.resetModules()
    vi.stubEnv('VITE_DATA_SOURCE', 'api')
    client.get.mockImplementation(async (path: string) => {
      if (path === '/collab/domains') {
        return { data: { data: { available: true, total: 1, items: [{ domain_id: 'dom-real' }] } } }
      }
      if (path === '/experts') return { data: { data: { available: false, total: 0, items: [], reason: 'phase6_expert_registry_not_connected' } } }
      if (path === '/approvals') return { data: { data: { available: false, total: 0, items: [], reason: 'phase6_approval_registry_not_connected' } } }
      if (path === '/agents/online') return { data: { data: { available: false, total: 0, items: [], reason: 'collab-bus unavailable' } } }
      if (path === '/collab/dom-real') {
        // BFF 已完成 collab-bus -> 工作平台视图映射；provider 只消费已映射契约。
        return { data: { data: {
          available: true,
          domain_id: 'dom-real',
          name: '真实协作域',
          state: 'active',
          progress: 66,
          stale_count: 0,
          concurrency_limit: 4,
          updated_at: '2026-09-20T06:00:00Z',
          agents: [{
            agent_id: 'agent-a',
            name: 'agent-a',
            progress: 66,
            state: 'running',
          }],
          messages: [],
          dag: { nodes: [], edges: [] },
          artifacts: [],
          gates: [],
          data_quality: {
            source: 'collab-bus-heartbeat',
            real_fields: ['agents.progress'],
            synthetic_fields: ['artifacts'],
          },
        } } }
      }
      return { data: { data: null } }
    })
  })

  it('uses the first real domain instead of the fixed DOM-2048 demo id', async () => {
    const { dataProvider } = await import('./provider')
    const result = await dataProvider.getWorkbenchData()
    expect(client.get).toHaveBeenCalledWith('/collab/domains')
    expect(client.get).toHaveBeenCalledWith('/collab/dom-real')
    expect(result.collaboration.domain_id).toBe('dom-real')
    expect(result.collaboration.progress).toBe(66)
    expect(result.collaboration.agents[0].agent_id).toBe('agent-a')
    expect(result.collaboration.data_quality.synthetic_fields).toContain('artifacts')
    expect(result.experts).toEqual([])
    expect(result.approvals).toEqual([])
    expect(result.online_agents).toEqual([])
  })

  it('keeps the mock collaboration fallback without requesting the fixed demo domain when no real domain exists', async () => {
    client.get.mockImplementation(async (path: string) => {
      if (path === '/collab/domains') {
        return { data: { data: { available: true, total: 0, items: [] } } }
      }
      return { data: { data: null } }
    })

    const { dataProvider } = await import('./provider')
    const result = await dataProvider.getWorkbenchData()

    expect(client.get).toHaveBeenCalledWith('/collab/domains')
    expect(client.get).not.toHaveBeenCalledWith('/collab/DOM-2048')
    expect(result.collaboration.domain_id).toBe('DOM-2048')
  })

  it('reads the sidebar Agent roster from /agents/online and keeps unavailable explicit', async () => {
    const { dataProvider } = await import('./provider')
    const unavailable = await dataProvider.getOnlineAgents()
    expect(unavailable.available).toBe(false)
    expect(unavailable.items).toEqual([])

    client.get.mockImplementation(async (path: string) => {
      if (path === '/agents/online') {
        return { data: { data: { available: true, total: 1, items: [{ agent_id: 'agent-real', name: '真实 Agent', role: 'Collaboration Member', state: 'run', task: 'dom-1', model: '-', latency_ms: 0 }] } } }
      }
      return { data: { data: null } }
    })
    const available = await dataProvider.getOnlineAgents()
    expect(available.available).toBe(true)
    expect(available.items[0].agent_id).toBe('agent-real')
  })
  it('loads the real model runtime aggregate through /models/runtime', async () => {
    client.get.mockImplementation(async (path: string) => {
      if (path === '/models/runtime') {
        return { data: { data: {
          available: true,
          items: [{ config_id: 1, runtime_state: 'active' }],
          routing: [{ role: 'generate' }],
          sources: { prometheus: { available: true, complete: true, availability: {}, by_role: {} } },
        } } }
      }
      return { data: { data: null } }
    })
    const { dataProvider } = await import('./provider')
    const result = await dataProvider.getModelRuntime(7)
    expect(client.get).toHaveBeenCalledWith('/models/runtime', { params: { days: 7 } })
    expect(result.available).toBe(true)
    expect(result.items[0].config_id).toBe(1)
    expect(result.routing[0].role).toBe('generate')
  })

  it('loads Prometheus metric overview through the BFF whitelist contract', async () => {
    client.get.mockImplementation(async (path: string) => {
      if (path === '/metrics/overview') {
        return { data: { data: { available: true, window: '15m', selected_job: 'all', summary: { targets_up: 8, targets_total: 8, active_alerts: 0 }, services: [], targets: [], alerts: [], support: { http_p95: false, http_p95_reason: 'bucket missing', metric_scope: 'system' } } } }
      }
      return { data: { data: null } }
    })
    const { dataProvider } = await import('./provider')
    const result = await dataProvider.getMetricsOverview('', '15m')
    expect(client.get).toHaveBeenCalledWith('/metrics/overview', { params: { job: '', window: '15m' } })
    expect(result.available).toBe(true)
    expect(result.summary.targets_up).toBe(8)
  })

})

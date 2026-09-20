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
})

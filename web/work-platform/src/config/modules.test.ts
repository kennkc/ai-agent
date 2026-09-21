import { describe, expect, it } from 'vitest'
import { modules } from './modules'

describe('work-platform menu metadata', () => {
  it('keeps the high-frequency entry set small and stable', () => {
    expect(modules.filter(item => item.pinned).map(item => item.id)).toEqual(['overview', 'tasks', 'collab', 'services'])
  })

  it('does not advertise planned capabilities as ready', () => {
    const planned = modules.filter(item => item.status === 'planned').map(item => item.id)
    expect(planned).toEqual(expect.arrayContaining(['experts', 'skills', 'connectors', 'automation', 'remote', 'cases', 'approvals']))
    expect(modules.find(item => item.id === 'experts')?.status).toBe('planned')
    expect(modules.find(item => item.id === 'approvals')?.status).toBe('planned')
  })

  it('keeps every module in one of the four declared groups', () => {
    const groups = new Set(['生命体区', '工作台区', '治理区', '观测区'])
    expect(modules.every(item => groups.has(item.group))).toBe(true)
  })
})

import { describe, expect, it } from 'vitest'
import router from './index'

describe('work-platform routes', () => {
  it('resolves the primary functional pages', () => {
    expect(router.resolve('/overview').name).toBe('overview')
    expect(router.resolve('/models').name).toBe('models')
    expect(router.resolve('/middleware').name).toBe('middleware')
    expect(router.resolve('/services').name).toBe('services')
    expect(router.resolve('/collab').name).toBe('collab')
  })

  it('keeps all dynamic module routes reachable', () => {
    const expected = [
      'vitals', 'brain', 'senses', 'evolution', 'collab', 'experts',
      'skills', 'connectors', 'automation', 'remote', 'cases', 'approvals',
    ]

    for (const path of expected) {
      expect(router.resolve(`/${path}`).matched.length).toBeGreaterThan(0)
    }
  })

  it('has a fallback route for unknown paths', () => {
    expect(router.resolve('/definitely-not-a-page').matched.length).toBeGreaterThan(0)
  })
})
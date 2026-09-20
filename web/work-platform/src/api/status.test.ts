import { beforeEach, describe, expect, it } from 'vitest'
import { clearDegrade, dataSourceStatus, reportApiOk, reportDegrade } from './status'

describe('data source degradation status', () => {
  beforeEach(() => {
    clearDegrade()
    dataSourceStatus.apiOk = 0
  })

  it('deduplicates degradation records by scope', () => {
    reportDegrade('overview', 'timeout')
    reportDegrade('overview', 'HTTP 500')

    expect(dataSourceStatus.degraded).toHaveLength(1)
    expect(dataSourceStatus.degraded[0]?.reason).toBe('HTTP 500')
  })

  it('clears one scope when the API recovers', () => {
    reportDegrade('models', 'BFF unavailable')
    reportDegrade('overview', 'timeout')

    reportApiOk('models')

    expect(dataSourceStatus.apiOk).toBe(1)
    expect(dataSourceStatus.degraded.map(item => item.scope)).toEqual(['overview'])
  })

  it('keeps at most 20 degradation records', () => {
    for (let index = 0; index < 25; index++) {
      reportDegrade(`scope-${index}`, 'failed')
    }

    expect(dataSourceStatus.degraded).toHaveLength(20)
    expect(dataSourceStatus.degraded[0]?.scope).toBe('scope-5')
    expect(dataSourceStatus.degraded[19]?.scope).toBe('scope-24')
  })
})
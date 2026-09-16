import { reactive } from 'vue'

export type DataSourceMode = 'mock' | 'api'

export interface DegradeRecord {
  /** 端点/数据域标识，例如 overview、vitals、middleware */
  scope: string
  /** 回落原因（HTTP 状态、超时、BFF 未实现等） */
  reason: string
  at: string
}

const MAX_RECORDS = 20

/**
 * 数据源健康状态。
 *
 * 设计约束（2026-09-16）：API 模式下任何 Mock 回落都不得静默发生。
 * 所有回落都会登记在 `degraded` 中，并由工作平台顶栏徽标 + 全局横幅暴露给用户。
 */
export const dataSourceStatus = reactive({
  mode: (import.meta.env.VITE_DATA_SOURCE || 'mock') as DataSourceMode,
  degraded: [] as DegradeRecord[],
  apiOk: 0,
})

export function reportDegrade(scope: string, reason: string) {
  const record: DegradeRecord = {
    scope,
    reason,
    at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
  }
  const index = dataSourceStatus.degraded.findIndex(item => item.scope === scope)
  if (index >= 0) dataSourceStatus.degraded[index] = record
  else dataSourceStatus.degraded.push(record)
  if (dataSourceStatus.degraded.length > MAX_RECORDS) {
    dataSourceStatus.degraded.splice(0, dataSourceStatus.degraded.length - MAX_RECORDS)
  }
  if (import.meta.env.DEV) {
    console.warn(`[wp-data-source] ${scope} 回落到 Mock 数据：${reason}`)
  }
}

export function reportApiOk(scope: string) {
  dataSourceStatus.apiOk += 1
  const index = dataSourceStatus.degraded.findIndex(item => item.scope === scope)
  if (index >= 0) dataSourceStatus.degraded.splice(index, 1)
}

export function clearDegrade() {
  dataSourceStatus.degraded.splice(0, dataSourceStatus.degraded.length)
}
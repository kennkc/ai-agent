import axios from 'axios'
import {
  approvals, automations, brainChain, brainDecision, cases, chatMessages, collaboration,
  connectors, evolution, evolutionMetrics, experts, growthTimeline, healingRecords, managedModels,
  metrics, getMiddlewareOverview, modelCallSeries, modelRoutes, modelRuntimeNodes, modelTokenTrend, notifications,
  onlineAgents, optimizationSuggestions, organs, remoteChannels, remoteFlow, resultArtifacts,
  searchIndex, senses, serviceHealth, skills, startMiddlewareMock, stopMiddlewareMock, buildSuggestionExecution, tasks, teamWorkflow, todaySummary, tracingSeed, vitalSigns,
} from './mock'
import type { MiddlewareNode, MiddlewareOverview, OptimizationSuggestion, SuggestionExecution, TracingOverview } from '../types'
const source = (import.meta.env.VITE_DATA_SOURCE || 'mock') as 'mock' | 'api'
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/wp',
  timeout: 10000,
  headers: {
    'X-Tenant-Id': import.meta.env.VITE_TENANT_ID || 'default',
  },
})

const unwrap = (payload: any) => payload?.data ?? payload ?? {}
// BFF 响应为 {data: {...}} 且 axios 又包一层 .data —— unwrapBody 连剥两层
const unwrapBody = (payload: any) => {
  const once = unwrap(payload)
  return once && typeof once === 'object' && 'data' in once && once.data && typeof once.data === 'object'
    ? once.data
    : once
}
const asArray = (payload: any) => {
  const value = unwrap(payload)
  return Array.isArray(value) ? value : (value.items || value.list || [])
}
const safe = async (request: () => Promise<any>, fallback: any): Promise<any> => {
  try {
    return await request()
  } catch {
    return fallback
  }
}

const mockWorkbench = {
  overview: {
    metrics,
    services: serviceHealth,
    timeline: growthTimeline,
    vitals: vitalSigns,
    workflow: teamWorkflow,
    model_calls: modelCallSeries,
    model_runtime: modelRuntimeNodes,
    optimization_suggestions: optimizationSuggestions,
    today_summary: todaySummary,
  },
  vitals: vitalSigns,
  organs,
  brain: { decision: brainDecision, chain: brainChain },
  senses,
  evolution: { metrics: evolutionMetrics, healings: healingRecords, trend: evolution.trend },
  collaboration,
  experts,
  skills,
  connectors,
  automations,
  cases,
  approvals,
  models: managedModels,
  model_routes: modelRoutes,
  model_token_trend: modelTokenTrend,
  remote_channels: remoteChannels,
  remote_flow: remoteFlow,
  online_agents: onlineAgents,
}
let executionSeq = 0

export const dataProvider = {
  mode: source,

  async getOverview() {
    if (source === 'mock') return mockWorkbench.overview
    // BFF 未实现 /overview 时回落 mock 数据，避免整页报错（已实现端点：middleware / tracing）
    const fallback = await safe(() => api.get('/overview'), { data: { data: mockWorkbench.overview } })
    return unwrap(fallback)
  },

  async getTasks() {
    if (source === 'mock') return tasks
    return asArray(await api.get('/tasks'))
  },

  async getChat(taskId = 'T-1042') {
    if (source === 'mock') return chatMessages
    return asArray(await api.get(`/chat/${taskId}`))
  },

  async getResults(taskId = 'T-1042') {
    if (source === 'mock') return resultArtifacts
    return asArray(await api.get(`/results/${taskId}`))
  },

  async ask(question: string, taskId = 'T-1042') {
    if (source === 'api') return unwrap(await api.post(`/chat/${taskId}`, { question }))
    return {
      answer: `已基于任务上下文完成追问分析。本次问题：${question}。结论已追加到结果工作区，原有产物保持不变。`,
      citations: [
        { title: '任务上下文 T-1042', source: 'session-manager' },
        { title: '知识检索结果', source: 'body-service' },
      ],
    }
  },

  async getMiddleware(): Promise<MiddlewareOverview> {
    if (source === 'mock') return getMiddlewareOverview()
    // API 模式：BFF 未就绪或中间件观测服务未启动时，返回 enabled=false 触发"未启用"页
    const fallback: MiddlewareOverview = { enabled: false, items: [], summary: { total: 0, up: 0, down: 0 }, checked_at: '' }
    try {
      const payload = unwrapBody(await api.get('/middleware'))
      return payload && typeof payload === 'object' && 'enabled' in payload ? payload as MiddlewareOverview : fallback
    } catch {
      return fallback
    }
  },

  async startMiddleware(key: string): Promise<MiddlewareNode | null> {
    if (source === 'api') return unwrapBody(await api.post(`/middleware/${key}/start`))
    return startMiddlewareMock(key)
  },

  async stopMiddleware(key: string): Promise<MiddlewareNode | null> {
    if (source === 'api') return unwrapBody(await api.post(`/middleware/${key}/stop`))
    return stopMiddlewareMock(key)
  },

  async getTracing(): Promise<TracingOverview> {
    if (source === 'mock') return tracingSeed
    const fallback: TracingOverview = { enabled: false, ui_url: '', services: [], recent: [], checked_at: '' }
    try {
      const payload = unwrapBody(await api.get('/tracing'))
      return payload && typeof payload === 'object' && 'enabled' in payload ? payload as TracingOverview : fallback
    } catch {
      return fallback
    }
  },

  async getWorkbenchData() {
    if (source === 'mock') return mockWorkbench

    const requests = [
      api.get('/vitals'), api.get('/organs'), api.get('/brain/DEC-20260912-0042'),
      api.get('/senses'), api.get('/evolution'), api.get('/collab/DOM-2048'),
      api.get('/experts'), api.get('/skills'), api.get('/connectors'),
      api.get('/automations'), api.get('/cases'), api.get('/approvals'),
      api.get('/models'), api.get('/remote-im/channels'), api.get('/agents/online'),
    ]
    const results = await Promise.all(requests.map(request => safe(() => request, { data: { data: null } })))
    const [vitals, organsData, brain, sensesData, evolutionData, collaborationData, expertsData,
      skillsData, connectorsData, automationsData, casesData, approvalsData, modelsData, remoteChannelsData, onlineAgentsData] = results.map(item => unwrap(item.data))

    return {
      vitals: Array.isArray(vitals) ? vitals : (vitals ? vitalSigns.map(item => ({ ...item, ...vitals[item.key] })) : vitalSigns),
      organs: organsData || organs,
      brain: brain?.chain ? { decision: brain, chain: brain.chain } : { decision: brainDecision, chain: brainChain },
      senses: sensesData || senses,
      evolution: evolutionData?.healings ? {
        metrics: evolutionData.metrics || [
          { label: '正向反馈', value: evolutionData.feedback_up ?? 0, unit: '条' },
          { label: '负向反馈', value: evolutionData.feedback_down ?? 0, unit: '条' },
          { label: '幻觉率', value: evolutionData.hallucination_rate ?? 0, unit: '%' },
          { label: '满意度', value: evolutionData.satisfaction ?? 0, unit: '分' },
        ],
        healings: evolutionData.healings,
        trend: evolutionData.trend || evolution.trend,
      } : evolution,
      collaboration: collaborationData || collaboration,
      experts: expertsData || experts,
      skills: skillsData || skills,
      connectors: connectorsData || connectors,
      automations: automationsData || automations,
      cases: casesData || cases,
      approvals: approvalsData || approvals,
      models: modelsData || managedModels,
      model_routes: modelRoutes,
      model_token_trend: modelTokenTrend,
      remote_channels: remoteChannelsData || remoteChannels,
      remote_flow: remoteFlow,
      online_agents: onlineAgentsData || onlineAgents,
    }
  },

  async getSearch(query: string) {
    if (source === 'mock') {
      const keyword = query.trim().toLowerCase()
      if (!keyword) return searchIndex
      return searchIndex.filter(item => `${item.title}${item.text}${item.type}`.toLowerCase().includes(keyword))
    }
    return asArray(await api.get('/search', { params: { q: query } }))
  },

  async getNotifications() {
    return notifications
  },

  async createTask(payload: Record<string, unknown>) {
    if (source === 'api') return unwrap(await api.post('/tasks', payload))
    return payload
  },

  async retryTask(taskId: string) {
    if (source === 'api') return unwrap(await api.post(`/tasks/${taskId}/retry`))
    return { task_id: taskId, state: 'running' }
  },

  async installSkill(skillId: string) {
    if (source === 'api') return unwrap(await api.post(`/skills/${skillId}/install`))
    return { skill_id: skillId, state: 'installed' }
  },

  async authorizeConnector(connectorId: string) {
    if (source === 'api') return unwrap(await api.post(`/connectors/${connectorId}/authorize`))
    return { connector_id: connectorId, state: 'online' }
  },

  async createAutomation(payload: Record<string, unknown>) {
    if (source === 'api') return unwrap(await api.post('/automations', payload))
    return payload
  },

  async patchAutomation(automationId: string, payload: Record<string, unknown>) {
    if (source === 'api') return unwrap(await api.patch(`/automations/${automationId}`, payload))
    return { automation_id: automationId, ...payload }
  },

  async reuseCase(caseId: string) {
    if (source === 'api') return unwrap(await api.post(`/cases/${caseId}/reuse`))
    return { case_id: caseId }
  },

  async decideApproval(approvalId: string, decision: 'approved' | 'rejected', reason = '') {
    if (source === 'api') return unwrap(await api.post(`/approvals/${approvalId}/decision`, { decision, reason }))
    return { approval_id: approvalId, state: decision, reason }
  },

  async applySuggestion(suggestion: OptimizationSuggestion): Promise<SuggestionExecution> {
    if (source === 'api') return unwrap(await api.post(`/suggestions/${suggestion.suggestion_id}/apply`))
    return buildSuggestionExecution(suggestion, (executionSeq += 1))
  },

  async updatePreferences(payload: Record<string, unknown>) {
    if (source === 'api') return unwrap(await api.put('/preferences', payload))
    return payload
  },
}

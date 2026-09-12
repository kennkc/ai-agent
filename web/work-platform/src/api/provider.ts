import axios from 'axios'
import {
  approvals, automations, brainChain, brainDecision, cases, chatMessages, collaboration,
  connectors, evolution, evolutionMetrics, experts, growthTimeline, healingRecords,
  metrics, modelCallSeries, modelRuntimeNodes, notifications, optimizationSuggestions, organs, resultArtifacts, searchIndex, senses, serviceHealth, teamWorkflow,
  skills, tasks, vitalSigns,
} from './mock'

const source = (import.meta.env.VITE_DATA_SOURCE || 'mock') as 'mock' | 'api'
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/wp',
  timeout: 10000,
  headers: {
    'X-Tenant-Id': import.meta.env.VITE_TENANT_ID || 'default',
  },
})

const unwrap = (payload: any) => payload?.data ?? payload ?? {}
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
}

export const dataProvider = {
  mode: source,

  async getOverview() {
    if (source === 'mock') return mockWorkbench.overview
    return unwrap(await api.get('/overview'))
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

  async getWorkbenchData() {
    if (source === 'mock') return mockWorkbench

    const requests = [
      api.get('/vitals'), api.get('/organs'), api.get('/brain/DEC-20260912-0042'),
      api.get('/senses'), api.get('/evolution'), api.get('/collab/DOM-2048'),
      api.get('/experts'), api.get('/skills'), api.get('/connectors'),
      api.get('/automations'), api.get('/cases'), api.get('/approvals'),
    ]
    const results = await Promise.all(requests.map(request => safe(() => request, { data: { data: null } })))
    const [vitals, organsData, brain, sensesData, evolutionData, collaborationData, expertsData,
      skillsData, connectorsData, automationsData, casesData, approvalsData] = results.map(item => unwrap(item.data))

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

  async updatePreferences(payload: Record<string, unknown>) {
    if (source === 'api') return unwrap(await api.put('/preferences', payload))
    return payload
  },
}

import axios from 'axios'
import {
  approvals, automations, brainChain, brainDecision, cases, chatMessages, collaboration,
  connectors, evolution, evolutionMetrics, experts, growthTimeline, healingRecords, managedModels,
  metrics, getMiddlewareOverview, modelCallSeries, modelRoutes, modelRuntimeNodes, modelTokenTrend, notifications,
  onlineAgents, optimizationSuggestions, organs, remoteChannels, remoteFlow, resultArtifacts,
  searchIndex, senses, serviceHealth, skills, startMiddlewareMock, stopMiddlewareMock, buildSuggestionExecution, tasks, teamWorkflow, todaySummary, tracingSeed, vitalSigns,
} from './mock'
import { reportApiOk, reportDegrade } from './status'
import type {
  KnowledgeHit, KnowledgeSearchResult, KnowledgeStats,
  MiddlewareNode, MiddlewareOverview, OptimizationSuggestion, SuggestionExecution, TracingOverview,
} from '../types'
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
/**
 * API 模式下的一次请求。
 * 成功即清除该 scope 的降级记录；失败则登记降级后再返回 fallback——
 * 任何 Mock 回落都必须留下可观测痕迹（详见 ./status.ts）。
 */
const safe = async (request: () => Promise<any>, fallback: any, scope = 'unknown'): Promise<any> => {
  try {
    const result = await request()
    reportApiOk(scope)
    return result
  } catch (error) {
    const reason = (error as { response?: { status?: number } })?.response?.status
      ? `HTTP ${(error as { response: { status: number } }).response.status}`
      : ((error as Error)?.message || 'request failed')
    reportDegrade(scope, reason)
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

// —— 中间件模块不受全局 mock 开关限制：卡片必须按真实启停状态展示 ——
// wp-bff GET /middleware 对 8 个中间件做真实 TCP 探针；仅当 BFF 不可达时回落演示数据并标注 data_source='mock'
const mockMiddlewareFallback = (): MiddlewareOverview => ({
  ...getMiddlewareOverview(),
  data_source: 'mock',
})

// —— 躯体视图不受全局 mock 开关限制：知识量/检索指标必须反映体层真实状态 ——
// VITE_DATA_SOURCE=mock 或体层不可达时给出演示数据，但一律带 data_source='mock' 标注，
// 且体层不可用（BFF 200 + available=false）时不伪造知识量，直接展示"不可用"。
function mockKnowledgeStats(): KnowledgeStats {
  return {
    available: true,
    tenant_id: 'default',
    checked_at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    body_url: 'http://127.0.0.1:8083',
    note: '检索 P99/命中率口径为「最近 500 次采样」，非全量历史',
    knowledge: { documents: 6, chunks: 24, metadata_backend: 'pg', vector_points: 24 },
    retrieval: {
      searches: 48, searches_with_result: 45, search_hit_rate: 0.9375,
      cache_hits: 21, cache_hit_rate: 0.4375, rerank_calls: 27, rerank_degraded: 2,
      latency_p50_ms: 38, latency_p95_ms: 126, latency_p99_ms: 208,
      sample_size: 48, p99_basis: 'sampled_recent_searches',
    },
    storage: {
      hot: { tier: 'HOT', store: 'redis', available: true, detail: '缓存优先策略（R3-08）', hit_rate: 0.4375, hits: 21, misses: 27 },
      warm: { tier: 'WARM', store: 'qdrant', available: true, detail: '向量主库（R3-04/R3-05）', collection: 'lifeform_knowledge', vector_size: 768 },
      cold: { tier: 'COLD', store: 'postgres', available: true, detail: '元数据真相源', backend: 'pg' },
    },
    embedding: { backend: 'bge-m3', dim: 1024, available: true, degraded: false },
    reranker: { backend: 'bge-reranker', available: true, degraded: false },
    vector_store: { provider: 'qdrant', collection: 'lifeform_knowledge', available: true, vector_size: 768 },
    data_source: 'mock',
  }
}

/** 演示检索：对 mock 检索索引做朴素匹配，并复刻 BFF 的高亮词计算口径 */
function mockKnowledgeSearch(query: string, topK: number): KnowledgeSearchResult {
  const keyword = query.trim()
  const terms = keyword ? [keyword] : []
  if (!keyword) {
    return { available: true, query, top_k: topK, hits: [], hit_count: 0, latency_ms: 0, data_source: 'mock' }
  }
  const hits: KnowledgeHit[] = searchIndex
    .filter(item => `${item.title}${item.text}`.includes(keyword.slice(0, Math.max(2, Math.ceil(keyword.length / 2)))))
    .slice(0, topK)
    .map((item, index) => ({
      rank: index + 1,
      chunk_id: `mock-${index}`,
      doc_id: `mock-doc-${index}`,
      title: item.title,
      heading: item.type,
      chunk_index: 0,
      score: Math.max(0.5, 0.92 - index * 0.08),
      rerank_score: Math.max(0.4, 0.95 - index * 0.1),
      source: 'mock',
      snippet: item.text,
      matched_terms: terms.filter(term => `${item.title}${item.text}`.includes(term)),
    }))
  return {
    available: true, query, top_k: topK, parsed_terms: terms, hits, hit_count: hits.length,
    latency_ms: 12 * hits.length, data_source: 'mock',
  }
}

export const dataProvider = {
  mode: source,

  async getOverview() {
    if (source === 'mock') return mockWorkbench.overview
    try {
      const payload = unwrapBody(await api.get('/overview'))
      reportApiOk('overview')
      // BFF /overview 目前只聚合观测域（中间件 + 链路追踪）；其余总览数据域仍是 Mock，
      // 逐个登记降级，避免"接口通了 = 数据真实"的误读。
      const gaps: string[] = Array.isArray(payload?.gaps) ? payload.gaps : []
      gaps.forEach(gap => reportDegrade(String(gap), 'BFF /overview 未覆盖，当前展示 Mock 数据'))
      return {
        ...mockWorkbench.overview,
        observability: payload?.observability ?? null,
        gaps,
        source: payload?.source || 'wp-bff',
      }
    } catch (error) {
      reportDegrade('overview', (error as Error)?.message || 'BFF /overview 不可达')
      return mockWorkbench.overview
    }
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
    try {
      const payload = unwrapBody(await api.get('/middleware'))
      if (payload && typeof payload === 'object' && 'enabled' in payload) {
        reportApiOk('middleware')
        return { ...payload, data_source: 'live' } as MiddlewareOverview
      }
      reportDegrade('middleware', '响应缺少 enabled 字段')
      return mockMiddlewareFallback()
    } catch (error) {
      reportDegrade('middleware', (error as Error)?.message || 'BFF /middleware 不可达')
      return mockMiddlewareFallback()
    }
  },

  async startMiddleware(key: string): Promise<MiddlewareNode | null> {
    try {
      return unwrapBody(await api.post(`/middleware/${key}/start`))
    } catch {
      // BFF 不可达时回落 mock 演示启动流程
      return startMiddlewareMock(key)
    }
  },

  async stopMiddleware(key: string): Promise<MiddlewareNode | null> {
    try {
      return unwrapBody(await api.post(`/middleware/${key}/stop`))
    } catch {
      return stopMiddlewareMock(key)
    }
  },

  async getTracing(): Promise<TracingOverview> {
    if (source === 'mock') return tracingSeed
    const fallback: TracingOverview = { enabled: false, ui_url: '', services: [], recent: [], checked_at: '' }
    try {
      const payload = unwrapBody(await api.get('/tracing'))
      if (payload && typeof payload === 'object' && 'enabled' in payload) {
        reportApiOk('tracing')
        return payload as TracingOverview
      }
      reportDegrade('tracing', '响应缺少 enabled 字段')
      return fallback
    } catch (error) {
      reportDegrade('tracing', (error as Error)?.message || 'BFF /tracing 不可达')
      return fallback
    }
  },

  /**
   * 躯体层知识统计（R-C03 躯体视图）。BFF 代理体层 stats：
   * 知识量 / 检索 P99 / 检索命中率 / 缓存命中率 / 三层存储可用性。
   *
   * 约定：BFF 返回 200 且 available=false 时，说明**体层不可用**——
   * 此时不回落到 Mock（没有 Mock 知识量可造），而是把 unavailable 状态如实交给界面。
   */
  async getKnowledge(): Promise<KnowledgeStats> {
    if (source === 'mock') return mockKnowledgeStats()
    try {
      const payload = unwrapBody(await api.get('/knowledge'))
      if (payload && typeof payload === 'object' && 'available' in payload) {
        if (payload.available === false) {
          reportDegrade('knowledge', payload.reason || '体层不可用（body-service 未启动）')
        } else {
          reportApiOk('knowledge')
        }
        return { data_source: 'live', ...(payload as KnowledgeStats) }
      }
      reportDegrade('knowledge', '响应缺少 available 字段')
      return { ...mockKnowledgeStats(), available: false, reason: '响应结构不符合契约' }
    } catch (error) {
      reportDegrade('knowledge', (error as Error)?.message || 'BFF /knowledge 不可达')
      return { ...mockKnowledgeStats(), available: false, reason: 'BFF /knowledge 不可达' }
    }
  },

  /**
   * 检索测试（R-C03）：语义检索 + 命中片段高亮。
   * hits[].matched_terms 由 BFF 计算，前端据此高亮 snippet；体层不可用时返回空 hits 并标注原因。
   */
  async searchKnowledge(query: string, topK = 5): Promise<KnowledgeSearchResult> {
    if (source === 'mock') return mockKnowledgeSearch(query, topK)
    try {
      const payload = unwrapBody(await api.post('/knowledge/search', { query, top_k: topK, use_cache: true }))
      if (payload && typeof payload === 'object' && 'hits' in payload) {
        if (payload.available === false) reportDegrade('knowledge_search', payload.reason || '体层检索不可用')
        else reportApiOk('knowledge_search')
        return { data_source: 'live', ...(payload as KnowledgeSearchResult) }
      }
      reportDegrade('knowledge_search', '响应缺少 hits 字段')
      return { ...mockKnowledgeSearch(query, topK), available: false, reason: '响应结构不符合契约' }
    } catch (error) {
      reportDegrade('knowledge_search', (error as Error)?.message || 'BFF /knowledge/search 不可达')
      return { ...mockKnowledgeSearch(query, topK), available: false, reason: 'BFF /knowledge/search 不可达' }
    }
  },

  async getWorkbenchData() {
    if (source === 'mock') return mockWorkbench

    // 每个端点单独登记降级 scope，便于界面精确指出"哪个模块仍在用 Mock 数据"
    const endpoints: Array<[string, string]> = [
      ['vitals', '/vitals'], ['organs', '/organs'], ['brain', '/brain/DEC-20260912-0042'],
      ['senses', '/senses'], ['evolution', '/evolution'], ['collaboration', '/collab/DOM-2048'],
      ['experts', '/experts'], ['skills', '/skills'], ['connectors', '/connectors'],
      ['automations', '/automations'], ['cases', '/cases'], ['approvals', '/approvals'],
      ['models', '/models'], ['remote_channels', '/remote-im/channels'], ['online_agents', '/agents/online'],
    ]
    const results = await Promise.all(endpoints.map(([scope, path]) =>
      safe(() => api.get(path), { data: { data: null } }, scope),
    ))
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

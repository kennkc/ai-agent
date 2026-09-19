import axios from 'axios'
import {
  approvals, automations, brainChain, brainDecision, cases, chatMessages, collaboration,
  connectors, evolution, evolutionMetrics, experts, growthTimeline, healingRecords, managedModels,
  metrics, getMiddlewareOverview, modelCallSeries, modelRoutes, modelRuntimeNodes, modelTokenTrend, notifications,
  onlineAgents, optimizationSuggestions, organs, remoteChannels, remoteFlow, resultArtifacts,
  searchIndex, senses, serviceHealth, skills, startMiddlewareMock, stopMiddlewareMock, buildSuggestionExecution, tasks, teamWorkflow, todaySummary, tracingSeed, vitalSigns,
  executionSeed, executeToolMock, toolImpactMock,
} from './mock'
import { reportApiOk, reportDegrade } from './status'
import type {
  BrainAnswer, ExecutionOverview, ExecutionTool, KnowledgeHit, KnowledgeIngestInput, KnowledgeIngestResult, KnowledgeSearchResult, KnowledgeStats,
  MiddlewareNode, MiddlewareOverview, OptimizationSuggestion, SessionContext, SessionInfo, SessionStats,
  SuggestionExecution, ToolExecutionResult, ToolImpactReport, TracingOverview,
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

/**
 * **写路径的未实现端点处理**（2026-09-18）。
 *
 * 契约里标 `x-wp-status: planned` 的端点（`/tasks`、`/skills/{id}/install` 等）BFF 尚未实现。
 * API 模式下直接 `await api.post(...)` 会抛出 axios 原始错误（`Request failed with status code 404`），
 * 既不登记降级、也无法让界面说清"到底是接口没实现还是服务挂了"。
 *
 * 这里统一处理：**登记降级 + 抛出说明性错误**。
 * 关键约束：**绝不返回伪造的成功** —— 写操作没生效就必须让调用方看到失败。
 */
function plannedEndpointError(scope: string, method: string, path: string, cause?: unknown): Error {
  const status = (cause as { response?: { status?: number } })?.response?.status
  const reason = status ? `HTTP ${status}` : ((cause as Error)?.message || 'request failed')
  reportDegrade(scope, `端点未实现（planned）：${method} ${path}（${reason}）`)
  return new Error(`${method} ${path} 尚未实现（契约标记 planned），操作未生效`)
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
//
// 字段口径对齐（2026-09-18 实测 `GET /api/wp/knowledge` 后校正）：
//   1. `embedding` / `reranker` 必须与真实后端**同一套取值**——权重未装时真实返回
//      `hash-ngram-768` / `lexical-coverage` 且 `degraded=true`；此前 Mock 写
//      `bge-m3` / `dim 1024` / `degraded=false`，与同对象的 `vector_store.vector_size=768`
//      **自相矛盾**（照 Mock 建集合会因维度冲突失败），且凭空声称"未降级"。
//   2. `storage.rules`（TierRouter 阈值）与 `retrieval` 的
//      `documents / chunks / ingest_failures / sample_limit` 真实返回均有，Mock 此前缺字段。
function mockKnowledgeStats(): KnowledgeStats {
  return {
    available: true,
    tenant_id: 'default',
    checked_at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    body_url: 'http://127.0.0.1:8083',
    note: '检索 P99/命中率口径为「最近 500 次采样」，非全量历史',
    knowledge: { documents: 6, chunks: 24, metadata_backend: 'pg', vector_points: 24 },
    retrieval: {
      documents: 6, chunks: 24, ingest_failures: 0,
      searches: 48, searches_with_result: 45, search_hit_rate: 0.9375,
      cache_hits: 21, cache_hit_rate: 0.4375, rerank_calls: 27, rerank_degraded: 2,
      latency_p50_ms: 38, latency_p95_ms: 126, latency_p99_ms: 208,
      sample_size: 48, sample_limit: 500, p99_basis: 'sampled_recent_searches',
    },
    storage: {
      hot: { tier: 'HOT', store: 'redis', available: true, detail: '缓存优先策略（R3-08）', hit_rate: 0.4375, hits: 21, misses: 27 },
      warm: { tier: 'WARM', store: 'qdrant', available: true, detail: '向量主库（R3-04/R3-05）', collection: 'lifeform_knowledge', vector_size: 768 },
      cold: { tier: 'COLD', store: 'postgres', available: true, detail: '元数据真相源', backend: 'pg' },
      rules: { hot_threshold: 2, warm_threshold: 1 },
    },
    embedding: { backend: 'hash-ngram-768', dim: 768, available: true, degraded: true },
    reranker: { backend: 'lexical-coverage', available: true, degraded: true },
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

/**
 * 演示问答（R4-09 对话界面骨架）。
 *
 * 仅用于 `VITE_DATA_SOURCE=mock`：给出的回答与来源一律带 `data_source='mock'`，
 * 且 `degraded=true`，界面会显示"演示答复"徽标——**不冒充真实模型输出**。
 */
function mockBrainAnswer(question: string): BrainAnswer {
  return {
    available: true,
    question,
    answer: `（演示答复 · 未调用大脑层）已基于任务上下文完成追问分析。本次问题：${question}。切到 VITE_DATA_SOURCE=api 后将走 nlp-service 大脑层的真实检索增强链路。`,
    generator: 'mock',
    degraded: true,
    degraded_reasons: ['mock_data_source'],
    sources: [
      { title: '演示来源 · 任务上下文', heading: 'demo', score: 0.5, source: 'mock' },
      { title: '演示来源 · 知识检索', heading: 'demo', score: 0.45, source: 'mock' },
    ],
    gap: { coverage: 0, sufficient: false, has_gap: true, usable_chunks: 0 },
    chain: [{ step: 'mock', model: 'demo', latency_ms: 0, note: '未执行真实链路' }],
    decision_id: '',
    data_source: 'mock',
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

  /**
   * 任务 / 会话 / 结果 / 搜索：契约中仍为 `planned`（BFF 未实现）。
   * API 模式下**回落演示数据并登记降级**（与 getWorkbenchData 同口径）——
   * 这样切到 api 不会白屏，且顶栏徽标会如实指出"该域仍是 Mock"。
   */
  async getTasks() {
    if (source === 'mock') return tasks
    return safe(() => api.get('/tasks').then(asArray), tasks, 'tasks')
  },

  async getChat(taskId = 'T-1042') {
    if (source === 'mock') return chatMessages
    return safe(() => api.get(`/chat/${taskId}`).then(asArray), chatMessages, 'chat')
  },

  async getResults(taskId = 'T-1042') {
    if (source === 'mock') return resultArtifacts
    return safe(() => api.get(`/results/${taskId}`).then(asArray), resultArtifacts, 'results')
  },

  /**
   * 追问（R4-06 端到端 / R4-09 对话界面）：BFF `/brain/ask` → nlp-service 大脑层。
   *
   * 与旧的 `/chat/{task_id}`（契约仍为 planned）不同，该端点已在 Phase 4 实装，
   * 会回传 `sources`（来源标注 R4-08）/ `gap`（缺口检测 R4-07）/ `chain`（决策链 D5）。
   *
   * 约定：**大脑层不可用时不伪造回答** —— 返回 `available=false` + `reason`，
   * 由界面展示失败（一条编造的"回答"比报错更危险，会被当成真实模型输出）。
   */
  async askBrain(question: string, taskId = 'T-1042', context: Array<{ role: string; content: string }> = []): Promise<BrainAnswer> {
    if (source === 'mock') return mockBrainAnswer(question)
    try {
      const payload = unwrapBody(await api.post('/brain/ask', {
        question,
        session_id: taskId,
        intent: '',
        context,
      }))
      if (payload && typeof payload === 'object' && 'available' in payload) {
        if (payload.available === false) {
          reportDegrade('brain_ask', String(payload.reason || '大脑层不可用（nlp-service 未启动）'))
        } else {
          reportApiOk('brain_ask')
        }
        return { data_source: 'live', ...(payload as BrainAnswer) }
      }
      reportDegrade('brain_ask', '响应缺少 available 字段')
      return { available: false, question, answer: '', reason: '响应结构不符合契约' }
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      const reason = status ? `HTTP ${status}` : ((error as Error)?.message || 'BFF /brain/ask 不可达')
      reportDegrade('brain_ask', reason)
      return { available: false, question, answer: '', reason: `BFF /brain/ask 不可达（${reason}）` }
    }
  },

  /**
   * R4-01 创建会话（BFF → session-manager，FSM NEW→ACTIVE，Redis 持久化）。
   *
   * 与旧版"拿 taskId 当 session_id"不同：真实会话 id 由 session-manager 生成并落 Redis，
   * 多轮上下文取 Redis 而不是浏览器内存 —— 这是 R4-02「重启可恢复」在产品侧成立的前提。
   * 会话服务不可用时返回 `available=false`，由界面标注，绝不本地伪造 id。
   */
  async createSession(): Promise<SessionInfo> {
    if (source === 'mock') {
      return { available: false, session_id: '', status: '', reason: 'mock 数据源不创建真实会话' }
    }
    try {
      const payload = unwrapBody(await api.post('/session', {}))
      if (payload && payload.session_id) {
        reportApiOk('session_create')
        return { available: true, session_id: String(payload.session_id), status: String(payload.status || 'NEW') }
      }
      reportDegrade('session_create', '会话服务未返回 session_id')
      return { available: false, session_id: '', status: '', reason: '会话服务不可用：未返回 session_id' }
    } catch (error) {
      const reason = (error as Error)?.message || 'BFF /session 不可达'
      reportDegrade('session_create', reason)
      return { available: false, session_id: '', status: '', reason: `会话服务不可用（${reason}）` }
    }
  },

  /**
   * R4-01/02 会话问答：走 session-manager FSM（状态校验 + 多轮上下文 + 落 Redis）后再问大脑层。
   * 会话链路不可用时**回退到 /brain/ask 并如实标注**，而不是假装多轮上下文还在。
   */
  async askSession(sessionId: string, question: string): Promise<BrainAnswer> {
    try {
      const payload = unwrapBody(await api.post(`/session/${encodeURIComponent(sessionId)}/ask`, { question }))
      if (payload && typeof payload === 'object' && 'available' in payload) {
        if (payload.available === false) {
          reportDegrade('session_ask', String(payload.reason || '会话服务不可用'))
        } else {
          reportApiOk('session_ask')
        }
        return { data_source: 'live', session_id: sessionId, ...(payload as BrainAnswer) }
      }
      reportDegrade('session_ask', '响应缺少 available 字段')
      return { available: false, question, answer: '', reason: '响应结构不符合契约' }
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      const reason = status === 409
        ? '会话已关闭，无法继续追问（请新建会话）'
        : status === 404 ? '会话不存在或已过期' : ((error as Error)?.message || 'BFF 会话端点不可达')
      reportDegrade('session_ask', reason)
      return { available: false, question, answer: '', reason }
    }
  },

  /** R4-02 多轮上下文回放：重启后能否读回历史，是「持久化」的硬证据。 */
  async getSessionContext(sessionId: string, turns = 10): Promise<SessionContext> {
    try {
      const payload = unwrapBody(await api.get(`/session/${encodeURIComponent(sessionId)}/context?turns=${turns}`))
      return payload && payload.available
        ? { available: true, session_id: sessionId, messages: Array.isArray(payload.messages) ? payload.messages : [] }
        : { available: false, session_id: sessionId, messages: [], reason: payload?.reason || '上下文不可读' }
    } catch (error) {
      return { available: false, session_id: sessionId, messages: [], reason: (error as Error)?.message || '上下文不可读' }
    }
  },

  async closeSession(sessionId: string): Promise<SessionInfo> {
    try {
      const payload = unwrapBody(await api.delete(`/session/${encodeURIComponent(sessionId)}`))
      if (payload && payload.available !== false) {
        return { available: true, session_id: sessionId, status: String(payload.status || 'CLOSED') }
      }
      return { available: false, session_id: sessionId, status: '', reason: payload?.reason || '关闭会话失败' }
    } catch (error) {
      return { available: false, session_id: sessionId, status: '', reason: (error as Error)?.message || '关闭会话失败' }
    }
  },

  /** R-C04 会话真相：活跃会话数 + 意图分布（不可用时不填 0）。 */
  async getSessionStats(): Promise<SessionStats> {
    try {
      const payload = unwrapBody(await api.get('/session/stats'))
      if (payload && payload.available) return { available: true, ...payload }
      return { available: false, active_sessions: null, by_intent: {}, reason: payload?.reason || '会话统计不可读' }
    } catch (error) {
      return { available: false, active_sessions: null, by_intent: {}, reason: (error as Error)?.message || '会话统计不可读' }
    }
  },

  /** D5 决策链回放：404 表示「审计里没有这条决策」，与上游不可用（available=false）是两回事。 */
  async getDecision(decisionId: string): Promise<BrainAnswer | null> {
    if (!decisionId) return null
    try {
      const payload = unwrapBody(await api.get(`/brain/${encodeURIComponent(decisionId)}`))
      return { data_source: 'live', ...(payload as BrainAnswer) }
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 404) return null
      reportDegrade('decision_replay', `HTTP ${status || 'unknown'}`)
      return null
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
   * R-C05(预) 执行视图：四肢层工具调用流 + 沙箱状态（BFF 代理 tool-executor）。
   *
   * 三条约定：
   *   1. 四肢层不可用（BFF 200 + `available:false`）→ 回**演示数据**并登记降级，
   *      界面据此打「演示数据」徽标 —— 不让"读不到"被渲染成"零次调用"。
   *   2. `partial=true` 时保留真实分片、缺片为 `null`，界面逐片标注而不是补 0。
   *   3. 工具清单/审计记录一律来自上游，前端**不本地编造**工具条目。
   */
  async getExecution(): Promise<ExecutionOverview> {
    if (source === 'mock') return executionSeed
    try {
      const payload = unwrapBody(await api.get('/tools'))
      if (!payload || typeof payload !== 'object' || !('available' in payload)) {
        reportDegrade('execution', '响应缺少 available 字段')
        return { ...executionSeed, available: false, reason: '响应结构不符合契约', data_source: 'mock' }
      }
      if (payload.available === false) {
        reportDegrade('execution', String(payload.reason || '四肢层不可用（tool-executor 未启动）'))
        return { ...(payload as ExecutionOverview), data_source: 'live' }
      }
      reportApiOk('execution')
      if (payload.partial) {
        reportDegrade('execution_partial', `分片缺失：${(payload.partial_reasons || []).join(', ') || '未知'}`)
      }
      return { ...(payload as ExecutionOverview), data_source: 'live' }
    } catch (error) {
      reportDegrade('execution', (error as Error)?.message || 'BFF /tools 不可达')
      return {
        ...executionSeed, available: false, data_source: 'mock',
        reason: 'BFF /tools 不可达（wp-bff 未启动）：以下为演示回落数据',
      }
    }
  },

  /**
   * R5-02 工具执行（写路径）。
   *
   * Mock 数据源下只做**本地守卫复演**（危险参数照样被拒），结果统一带
   * `data_source:'mock'` + `reason`，界面打「演示」徽标 —— 绝不假装真跑过工具。
   * 切到 api 后真正触达 tool-executor；工具被守卫/沙箱拒绝时 `success:false`
   * 但 `available:true`（这是已落审计的真实调用结果，不是服务不可用）。
   */
  async executeTool(toolName: string, args: Record<string, unknown> = {}): Promise<ToolExecutionResult> {
    if (source === 'mock') return executeToolMock(toolName, args)
    try {
      const payload = unwrapBody(await api.post('/tools/execute', { tool_name: toolName, arguments: args }))
      if (!payload || typeof payload !== 'object' || !('available' in payload)) {
        reportDegrade('tool_execute', '响应缺少 available 字段')
        return { available: false, tool_name: toolName, reason: '响应结构不符合契约' }
      }
      if (payload.available === false) {
        reportDegrade('tool_execute', String(payload.reason || '四肢层不可用'))
      } else if (payload.success === false) {
        reportDegrade('tool_execute', `工具被拒：${payload.error_code} ${payload.error_message || ''}`.trim())
      } else {
        reportApiOk('tool_execute')
      }
      return { data_source: 'live', ...(payload as ToolExecutionResult) }
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      const reason = status === 401 || status === 403
        ? `写路径鉴权失败（HTTP ${status}）：控制令牌缺失或来源不在白名单`
        : ((error as Error)?.message || 'BFF /tools/execute 不可达')
      reportDegrade('tool_execute', reason)
      return { available: false, tool_name: toolName, reason }
    }
  },

  /**
   * IN-06 单工具详情（BFF `GET /tools/{name}` → `{ tool: {...} }`）。
   *
   * 与 `getExecution()` 的工具清单同构，区别只是取单个。取不到返回 `null`，
   * 由调用方决定展示"未登记"而非编造一个工具。
   */
  async getToolDetail(toolName: string): Promise<ExecutionTool | null> {
    if (source === 'mock') {
      return (executionSeed.tools || []).find((item) => item.name === toolName) || null
    }
    try {
      const payload = unwrapBody(await api.get(`/tools/${encodeURIComponent(toolName)}`))
      const tool = (payload && (payload.tool ?? payload)) as ExecutionTool
      if (!tool || !tool.name) {
        reportDegrade('tool_detail', '响应缺少工具字段')
        return null
      }
      reportApiOk('tool_detail')
      return tool
    } catch (error) {
      reportDegrade('tool_detail', (error as Error)?.message || `BFF /tools/${toolName} 不可达`)
      return null
    }
  },

  /**
   * IN-06 变更影响分析（BFF `GET /tools/{name}/impact` → `{ impact: {...} }`）。
   *
   * **注意口径**：`affected_agents/flows` 只覆盖注册表**显式登记**过的消费方，
   * 空列表 ≠ 无影响。界面必须把这句 note 一并展示，避免把"没登记"读成"很安全"。
   */
  async getToolImpact(toolName: string): Promise<ToolImpactReport> {
    if (source === 'mock') return toolImpactMock(toolName)
    try {
      const payload = unwrapBody(await api.get(`/tools/${encodeURIComponent(toolName)}/impact`))
      const impact = (payload && (payload.impact ?? payload)) as ToolImpactReport
      if (!impact || !impact.tool_name) {
        reportDegrade('tool_impact', '响应缺少影响分析字段')
        return { ...toolImpactMock(toolName), data_source: 'mock', note: '响应结构不符合契约，以下为演示数据' }
      }
      reportApiOk('tool_impact')
      return {
        ...impact,
        affected_agents: impact.affected_agents || [],
        affected_flows: impact.affected_flows || [],
        data_source: 'live',
      }
    } catch (error) {
      const reason = (error as Error)?.message || `BFF /tools/${toolName}/impact 不可达`
      reportDegrade('tool_impact', reason)
      return { ...toolImpactMock(toolName), data_source: 'mock', note: `影响分析不可用（${reason}）：以下为演示数据` }
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

  /**
   * 文档入库（R3-09 写路径）：把图文正文交给体层做「格式解析 → 分块 → 嵌入 → 向量入库」。
   *
   * 约定：**写路径绝不回落到 Mock** —— Mock 里造一个"入库成功"没有任何意义，
   * 只会让人误以为知识已落库。Mock 模式下如实返回 `available=false` 并说明原因。
   */
  async insertKnowledge(input: KnowledgeIngestInput): Promise<KnowledgeIngestResult> {
    if (source === 'mock') {
      return {
        available: false,
        data_source: 'mock',
        reason: '当前为 Mock 数据源（VITE_DATA_SOURCE=mock），入库不可用；请切换为 api 后重试',
      }
    }
    try {
      const payload = unwrapBody(await api.post('/knowledge', input))
      if (payload && typeof payload === 'object' && 'available' in payload) {
        if (payload.available === false) {
          reportDegrade('knowledge_ingest', payload.reason || '体层入库不可用')
        } else {
          reportApiOk('knowledge_ingest')
        }
        return { data_source: 'live', ...(payload as KnowledgeIngestResult) }
      }
      reportDegrade('knowledge_ingest', '响应缺少 available 字段')
      return { available: false, reason: '响应结构不符合契约' }
    } catch (error) {
      const reason = (error as Error)?.message || 'BFF /knowledge 写路径不可达'
      reportDegrade('knowledge_ingest', reason)
      return { available: false, reason }
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
    const localSearch = () => {
      const keyword = query.trim().toLowerCase()
      if (!keyword) return searchIndex
      return searchIndex.filter(item => `${item.title}${item.text}${item.type}`.toLowerCase().includes(keyword))
    }
    if (source === 'mock') return localSearch()
    // `/search` 亦为 planned 端点：回落本地索引并登记降级，避免切 api 后搜索结果页白屏
    return safe(() => api.get('/search', { params: { q: query } }).then(asArray), localSearch(), 'search')
  },

  async getNotifications() {
    return notifications
  },

  // ─────────── 写路径（契约中多数字段仍为 planned）───────────
  // 约定：api 模式下**不伪造成功**。端点未实现 → 登记降级 + 抛出说明性错误，
  // 让界面显示"操作未生效"，而不是静默吞掉或假装成功。
  async createTask(payload: Record<string, unknown>) {
    if (source !== 'api') return payload
    try { return unwrap(await api.post('/tasks', payload)) }
    catch (error) { throw plannedEndpointError('task_create', 'POST', '/tasks', error) }
  },

  async retryTask(taskId: string) {
    if (source !== 'api') return { task_id: taskId, state: 'running' }
    try { return unwrap(await api.post(`/tasks/${taskId}/retry`)) }
    catch (error) { throw plannedEndpointError('task_retry', 'POST', `/tasks/${taskId}/retry`, error) }
  },

  async installSkill(skillId: string) {
    if (source !== 'api') return { skill_id: skillId, state: 'installed' }
    try { return unwrap(await api.post(`/skills/${skillId}/install`)) }
    catch (error) { throw plannedEndpointError('skill_install', 'POST', `/skills/${skillId}/install`, error) }
  },

  async authorizeConnector(connectorId: string) {
    if (source !== 'api') return { connector_id: connectorId, state: 'online' }
    try { return unwrap(await api.post(`/connectors/${connectorId}/authorize`)) }
    catch (error) { throw plannedEndpointError('connector_authorize', 'POST', `/connectors/${connectorId}/authorize`, error) }
  },

  async createAutomation(payload: Record<string, unknown>) {
    if (source !== 'api') return payload
    try { return unwrap(await api.post('/automations', payload)) }
    catch (error) { throw plannedEndpointError('automation_create', 'POST', '/automations', error) }
  },

  async patchAutomation(automationId: string, payload: Record<string, unknown>) {
    if (source !== 'api') return { automation_id: automationId, ...payload }
    try { return unwrap(await api.patch(`/automations/${automationId}`, payload)) }
    catch (error) { throw plannedEndpointError('automation_patch', 'PATCH', `/automations/${automationId}`, error) }
  },

  async reuseCase(caseId: string) {
    if (source !== 'api') return { case_id: caseId }
    try { return unwrap(await api.post(`/cases/${caseId}/reuse`)) }
    catch (error) { throw plannedEndpointError('case_reuse', 'POST', `/cases/${caseId}/reuse`, error) }
  },

  async decideApproval(approvalId: string, decision: 'approved' | 'rejected', reason = '') {
    if (source !== 'api') return { approval_id: approvalId, state: decision, reason }
    try { return unwrap(await api.post(`/approvals/${approvalId}/decision`, { decision, reason })) }
    catch (error) { throw plannedEndpointError('approval_decision', 'POST', `/approvals/${approvalId}/decision`, error) }
  },

  async applySuggestion(suggestion: OptimizationSuggestion): Promise<SuggestionExecution> {
    if (source !== 'api') return buildSuggestionExecution(suggestion, (executionSeq += 1))
    try { return unwrap(await api.post(`/suggestions/${suggestion.suggestion_id}/apply`)) }
    catch (error) { throw plannedEndpointError('suggestion_apply', 'POST', `/suggestions/${suggestion.suggestion_id}/apply`, error) }
  },

  async updatePreferences(payload: Record<string, unknown>) {
    if (source !== 'api') return payload
    try { return unwrap(await api.put('/preferences', payload)) }
    catch (error) { throw plannedEndpointError('preferences_update', 'PUT', '/preferences', error) }
  },
}

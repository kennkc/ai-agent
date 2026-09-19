export type ModuleId =
  | 'overview' | 'vitals' | 'brain' | 'senses' | 'evolution' | 'collab'
  | 'tasks' | 'chat' | 'experts' | 'skills' | 'connectors' | 'automation' | 'models' | 'remote'
  | 'cases' | 'approvals'
  | 'middleware' | 'tracing' | 'knowledge'

export type ModuleGroup = '生命体区' | '工作台区' | '治理区' | '观测区'
export type ThemeMode = 'dark' | 'light' | 'system'

export interface WorkModule {
  id: ModuleId
  path: string
  title: string
  group: ModuleGroup
  phase: string
  status: 'ready' | 'prototype' | 'planned'
  description: string
  icon: string
}

export interface MetricCard {
  label: string
  value: string | number
  unit?: string
  trend?: string
  tone?: 'primary' | 'success' | 'warning' | 'danger'
}

export interface TaskItem {
  task_id: string
  title: string
  type: string
  state: 'pending' | 'running' | 'done' | 'failed' | 'archived'
  progress: number
  priority: 'P0' | 'P1' | 'P2'
  agent: string
  updated_at: string
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  citations?: Array<{ title: string; source?: string; score?: number; snippet?: string }>
  created_at: string
  /** 大脑层原始回答（R4-06/R4-08）：界面据此展示来源置信度、缺口提示与降级标注 */
  brain?: BrainAnswer
}

/**
 * 大脑层回答来源（R4-08 来源标注）。
 * `score` 为向量召回相似（判可用性的依据），`rerank_score` 仅用于排序展示 ——
 * 二者量纲不同，前端不要混用做阈值判断。
 */
export interface BrainSource {
  chunk_id?: string
  doc_id?: string
  title: string
  heading?: string
  score?: number
  rerank_score?: number
  snippet?: string
  source?: string
}

/** 信息缺口判定（R4-07）：`has_gap=true` 时界面需明确提示「知识不足」 */
export interface BrainGap {
  coverage?: number
  sufficient?: boolean
  has_gap?: boolean
  usable_chunks?: number
  threshold?: number
}

/** 决策链单步（D5 思考链回放） */
export interface BrainChainStep {
  step: string
  model?: string
  latency_ms?: number
  note?: string
}

/**
 * 大脑层回答（R4-06 / R4-08 / R4-09）。
 *
 * `available=false` 表示大脑层不可用（此时 `answer` 为空、`reason` 说明原因）——
 * 界面**必须**展示失败，不得拿它当空回答渲染成"发送成功"。
 */
export interface BrainAnswer {
  available: boolean
  question: string
  answer: string
  /** 生成后端：template（模板降级）/ model（真实模型）/ local-retrieval（本地检索直出） */
  generator?: string
  degraded?: boolean
  degraded_reasons?: string[]
  sources?: BrainSource[]
  gap?: BrainGap
  chain?: BrainChainStep[]
  plan?: { template?: string; top_k?: number; planner?: string }
  decision_id?: string
  cache_hit?: boolean
  cache_similarity?: number
  latency_ms?: number
  data_source?: 'mock' | 'live'
  reason?: string
  /** 所属真实会话（session-manager 生成），未走会话链路时为空 */
  session_id?: string
  /** 会话状态机状态：NEW / ACTIVE / IDLE / TIMEOUT / CLOSED */
  session_status?: string
}

/**
 * R4-01 会话句柄。
 * `available=false` 表示会话服务不可用 —— 此时 `session_id` 必为空字符串，
 * 界面不得本地拼一个 id 冒充（那会让「多轮上下文持久化」看起来成立实则不存在）。
 */
export interface SessionInfo {
  available: boolean
  session_id: string
  status: string
  reason?: string
}

/** R4-02 多轮上下文（来自 Redis，不是浏览器内存） */
export interface SessionContext {
  available: boolean
  session_id: string
  messages: Array<{ role: string; content: string; created_at?: string }>
  reason?: string
}

/** R-C04 会话真相汇总：读不到时 active_sessions 为 null（不静默填 0） */
export interface SessionStats {
  available: boolean
  active_sessions: number | null
  by_intent?: Record<string, number>
  total_messages?: number
  reason?: string
}

export interface ResultArtifact {
  artifact_id: string
  name: string
  type: 'document' | 'image' | 'data' | 'code'
  state: 'ready' | 'generating' | 'review'
  size: string
  updated_at: string
  preview: string
}

export interface VitalSign {
  key: string
  label: string
  value: number
  unit: string
  status: 'healthy' | 'warning' | 'critical'
  trend: string
  threshold: string
}

export interface OrganHealth {
  organ: string
  score: number | null
  state: string
  baseline: boolean
  note: string
}

export interface SenseChannel {
  channel_id: string
  name: string
  icon: string
  state: 'UP' | 'DEGRADED' | 'DOWN'
  count: number
  quality: number
  recent: string
  reason: string
  source: string
  last_sync: string
  samples: Array<{ time: string; title: string; quality: number }>
}

export interface EvolutionMetric {
  label: string
  value: number
  unit: string
}

export interface HealingRecord {
  healing_id: string
  doc: string
  issue: string
  action: string
  recurrence: string
  created_at: string
  detail: string
}

export interface CollaborationAgent {
  agent_id: string
  name: string
  role: string
  progress: number
  state: 'running' | 'done' | 'waiting' | 'blocked'
  current_task: string
  use_case: string
  model: string
  tools: string[]
  artifact_count: number
  confidence: number
  waiting_for?: string
  bus_position: number
  bus_message: string
  bus_kind: 'dispatch' | 'running' | 'result' | 'waiting'
  is_leader?: boolean
}

export interface CollaborationMessage {
  message_id: string
  time: string
  type: 'dispatch' | 'result' | 'heartbeat' | 'negotiate'
  from: string
  to: string
  text: string
  payload: Record<string, unknown>
}

export interface ManagedModel {
  model_id: string
  name: string
  provider: string
  tier: 'L0' | 'L1' | 'L2' | 'L3'
  state: 'active' | 'standby' | 'degraded' | 'disabled'
  task_types: string[]
  cost_per_1k: number
  latency_ms: number
  quality: number
  share: number
  quota: string
}

export interface ModelRoute {
  route_id: string
  task_type: string
  model_id: string
  model_name: string
  share: number
  cost: string
  note: string
}

export interface RemoteChannel {
  channel_id: string
  name: string
  type: 'wechat' | 'wecom' | 'feishu' | 'dingtalk' | 'qq'
  state: 'online' | 'available' | 'offline'
  account: string
  capabilities: string[]
  last_message: string
}

export interface RemoteFlowEvent {
  event_id: string
  time: string
  direction: '下发' | '执行' | '回传'
  channel: string
  message: string
  status: 'sent' | 'running' | 'done' | 'failed'
}

export interface OnlineAgent {
  agent_id: string
  name: string
  role: string
  state: 'run' | 'wait' | 'idle'
  task: string
  model: string
  latency_ms: number
}
export interface ExpertProfile {
  expert_id: string
  name: string
  domain: string
  persona: string
  methodology: string
  tool_whitelist: string[]
  output_schema: Record<string, unknown>
  state: 'active' | 'deprecated' | 'suspended'
}

export interface SkillItem {
  skill_id: string
  name: string
  category: string
  version: string
  installs: number
  rating: number
  state: 'installed' | 'available' | 'installing' | 'failed'
  audit: 'passed' | 'reviewing' | 'blocked'
  description: string
  permissions: string[]
}

export interface ConnectorItem {
  connector_id: string
  name: string
  protocol: string
  state: 'online' | 'pending_auth' | 'offline'
  latency: number
  calls: number
  enabled: boolean
  health_note: string
}

export interface AutomationItem {
  automation_id: string
  auto_name: string
  cron: string
  action: string
  state: 'active' | 'paused' | 'failed'
  last_run: string
  next_run: string
  push: string[]
  last_error?: string
}

export interface CaseItem {
  case_id: string
  title: string
  category: string
  reuse_count: number
  experts: string[]
  skills: string[]
  prompt: string
  outcome: string
}

export interface ApprovalItem {
  approval_id: string
  action: string
  risk_level: 'L1' | 'L2' | 'L3' | 'L4'
  applicant: string
  state: 'pending' | 'approved' | 'rejected' | 'expired'
  context: string
  target: string
  impact: string
  parameters: Record<string, unknown>
  ttl: string
  approved_by: string[]
  required_approvals: number
  reason?: string
}

export interface NotificationItem {
  id: string
  type: 'approval' | 'task' | 'healing' | 'system'
  title: string
  text: string
  time: string
  unread: boolean
  route?: string
}

export interface SearchItem {
  type: '任务' | '知识' | '技能' | '案例' | '审批'
  title: string
  text: string
  route: string
}

export interface UserPreferences {
  theme: ThemeMode
  default_model: string
  language: string
  notify_approval: boolean
  notify_task: boolean
  notify_healing: boolean
}

export type WorkflowStepState = 'done' | 'running' | 'waiting' | 'blocked'
export type ModelNodeState = 'healthy' | 'busy' | 'degraded' | 'offline'
export type SuggestionStatus = 'pending' | 'applied' | 'dismissed'

export interface OverviewWorkflowStep {
  step_id: string
  label: string
  state: WorkflowStepState
  progress: number
  owner: string
  timestamp: string
}

export interface OverviewWorkflowAgent {
  agent_id: string
  name: string
  role: string
  state: 'running' | 'done' | 'waiting'
  progress: number
  current_task: string
  use_case: string
  model: string
}

export interface OverviewWorkflow {
  domain_id: string
  task_title: string
  progress: number
  eta: string
  last_update: string
  execution_mode: string
  steps: OverviewWorkflowStep[]
  agents: OverviewWorkflowAgent[]
}

export interface ModelCallPoint {
  time: string
  calls: number
  tokens: number
  latency: number
  cost: number
}

export interface ModelRuntimeNode {
  node_id: string
  name: string
  model: string
  state: ModelNodeState
  load: number
  queue: number
  throughput: number
  latency: number
  role: string
}

export interface OptimizationSuggestion {
  suggestion_id: string
  title: string
  category: '成本优化' | '性能优化' | '质量优化' | '安全优化'
  priority: 'high' | 'medium' | 'low'
  impact: string
  confidence: number
  effort: '低' | '中' | '高'
  status: SuggestionStatus
  evidence: string
  action: string
  source: string
  target?: string
}

export type SuggestionExecutionState = 'queued' | 'running' | 'done' | 'failed'

export interface SuggestionExecutionStep {
  name: string
  state: 'pending' | 'running' | 'done'
  detail?: string
}

export interface SuggestionExecution {
  execution_id: string
  suggestion_id: string
  title: string
  action: string
  category: OptimizationSuggestion['category']
  state: SuggestionExecutionState
  progress: number
  steps: SuggestionExecutionStep[]
  logs: Array<{ time: string; text: string }>
  started_at: string
  finished_at: string | null
  result: string | null
}

export interface MiddlewareNode {
  key: string
  name: string
  role: string
  port: number
  state: 'up' | 'down' | 'starting' | 'stopping'
  console_url?: string
  console_label?: string
  metrics: Array<{ label: string; value: string }>
  last_check: string
}

export interface MiddlewareOverview {
  enabled: boolean
  /** 探针类型：tcp = 仅端口可达性，不代表进程内部健康度 */
  probe_mode?: string
  items: MiddlewareNode[]
  summary: { total: number; up: number; down: number }
  checked_at: string
  /** 数据来源：live=wp-bff 真实探针；mock=BFF 不可达时的演示回落（仅前端标注用，BFF 不返回） */
  data_source?: 'live' | 'mock'
}

/** BFF /overview 聚合的观测域（真实数据来源：wp-bff） */
export interface OverviewObservability {
  middleware: {
    total: number
    up: number
    down: number
    pending: number
    probe_mode: string
    items: MiddlewareNode[]
  }
  tracing: {
    enabled: boolean
    services: number
    spans_sampled: number
    recent_errors: number
    p99_ms: number
    p99_basis: string
  }
}

export interface TracingServiceStat {
  name: string
  spans_24h: number
  error_rate: number
  p99_ms: number
  /** 采样口径：本服务参与聚合的 trace 条数 */
  sample_size?: number
  /** 采样上限（当前实现 = 最近 20 条） */
  sample_limit?: number
  /** P99 的计算基础，sampled_recent_traces = 采样内分位，非全量 24h 指标 */
  p99_basis?: string
}

export interface TracingRecentTrace {
  trace_id: string
  service: string
  operation: string
  duration_ms: number
  spans: number
  time: string
  /** 绝对毫秒时间戳，用于跨天排序（time 为本地时间字符串） */
  start_time_ms?: number
  status: 'ok' | 'error'
}

export interface TracingOverview {
  enabled: boolean
  ui_url: string
  services: TracingServiceStat[]
  recent: TracingRecentTrace[]
  checked_at: string
}

/** 三层存储中的一层（R-C03 躯体视图；available 为真实探针结果） */
export interface KnowledgeTier {
  tier: 'HOT' | 'WARM' | 'COLD'
  store: 'redis' | 'qdrant' | 'postgres'
  available: boolean
  detail?: string
  collection?: string
  vector_size?: number
  backend?: string
  hit_rate?: number
  hits?: number
  misses?: number
}

/**
 * BFF /knowledge 躯体层知识统计。
 * available=false 表示体层不可用：此时 knowledge/retrieval 等字段缺失，
 * 界面必须展示"不可用"而不是把缺失渲染为 0。
 */
export interface KnowledgeStats {
  available: boolean
  tenant_id?: string
  checked_at?: string
  body_url?: string
  reason?: string
  gaps?: string[]
  note?: string
  knowledge?: {
    documents: number
    chunks: number
    metadata_backend: 'pg' | 'memory'
    vector_points: number
  }
  retrieval?: {
    /** 体层检索口径的文档/切片总数（与 knowledge 段同源，便于交叉核对） */
    documents: number
    chunks: number
    ingest_failures: number
    searches: number
    searches_with_result: number
    search_hit_rate: number
    cache_hits: number
    cache_hit_rate: number
    rerank_calls: number
    rerank_degraded: number
    latency_p50_ms: number
    latency_p95_ms: number
    latency_p99_ms: number
    sample_size: number
    /** 采样上限（体层默认 500），P99 口径为「最近 N 次」而非全量历史 */
    sample_limit: number
    p99_basis: string
  }
  storage?: { hot?: KnowledgeTier; warm?: KnowledgeTier; cold?: KnowledgeTier; rules?: { hot_threshold: number; warm_threshold: number } }
  embedding?: { backend: string; dim: number; available: boolean; degraded: boolean }
  reranker?: { backend: string; available: boolean; degraded: boolean }
  vector_store?: { provider: string; collection: string; available: boolean; vector_size: number }
  /** 前端标注用：live=BFF 真实代理；mock=BFF 不可达时的演示回落（BFF 不返回该字段） */
  data_source?: 'live' | 'mock'
}

/** 检索命中（含 BFF 计算的高亮词与截窗片段） */
export interface KnowledgeHit {
  rank: number
  chunk_id: string
  doc_id: string
  title: string
  heading?: string
  chunk_index?: number
  score?: number
  rerank_score?: number
  source?: string
  ingest_time_iso?: string
  snippet: string
  matched_terms: string[]
}

export interface KnowledgeSearchResult {
  available: boolean
  tenant_id?: string
  query: string
  top_k: number
  parsed_terms?: string[]
  hits: KnowledgeHit[]
  hit_count?: number
  latency_ms?: number
  checked_at?: string
  reason?: string
  highlight_note?: string
  data_source?: 'live' | 'mock'
}

/** 文档入库（R3-09 写路径）：单篇与批量共用 */
export interface KnowledgeIngestInput {
  doc_id?: string
  title?: string
  content: string
  source?: string
  /** 体层 DocumentParser 支持的格式；auto 按内容嗅探 */
  format?: 'auto' | 'md' | 'markdown' | 'text' | 'txt' | 'html' | 'htm'
}

export interface KnowledgeIngestResult {
  available: boolean
  mode?: 'single' | 'batch'
  tenant_id?: string
  latency_ms?: number
  checked_at?: string
  reason?: string
  error_code?: string
  doc_id?: string
  chunk_count?: number
  status?: 'PENDING' | 'INDEXED' | 'FAILED'
  vector_backend?: string
  degraded?: boolean
  normalized_chars?: number
  success?: boolean
  total?: number
  succeeded?: number
  failed?: number
  data_source?: 'live' | 'mock'
}

export interface TodaySummaryMetric {
  label: string
  value: string | number
  unit?: string
  trend: string
  tone: 'primary' | 'success' | 'warning' | 'danger'
}

export interface TodaySummaryEvent {
  time: string
  title: string
  detail: string
  type: 'task' | 'knowledge' | 'model' | 'governance'
}

export interface TodaySummary {
  date: string
  headline: string
  running_index: number
  metrics: TodaySummaryMetric[]
  events: TodaySummaryEvent[]
  attention: string[]
}

export interface OverviewCockpit {
  today_summary: TodaySummary
  workflow: OverviewWorkflow
  model_calls: ModelCallPoint[]
  model_runtime: ModelRuntimeNode[]
  optimization_suggestions: OptimizationSuggestion[]
}

export type ModuleId =
  | 'overview' | 'vitals' | 'brain' | 'senses' | 'evolution' | 'collab'
  | 'tasks' | 'chat' | 'experts' | 'skills' | 'connectors' | 'automation' | 'models' | 'remote'
  | 'cases' | 'approvals'
  | 'middleware' | 'services' | 'tracing' | 'metrics' | 'knowledge' | 'execution'

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
  /** 高频入口固定在左侧菜单顶部，避免被分组折叠隐藏。 */
  pinned?: boolean
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
export interface ManagedService extends Omit<MiddlewareNode, 'state'> {
  state: 'up' | 'down' | 'starting' | 'stopping'
  controllable: boolean
  controlled: boolean
  control_status: 'controlled' | 'external' | 'disabled'
  pid?: number | null
}

export interface ManagedServiceOverview {
  enabled: boolean
  control_enabled: boolean
  probe_mode?: string
  items: ManagedService[]
  summary: { total: number; up: number; down: number }
  checked_at: string
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


export interface MetricsSummary {
  targets_up: number
  targets_total: number
  active_alerts: number
  qps: number | null
  error_rate: number | null
  avg_latency_ms: number | null
  max_latency_ms: number | null
  http_p95_ms: number | null
  jvm_heap_used_bytes: number | null
  jvm_heap_max_bytes: number | null
  jvm_heap_used_ratio: number | null
  jvm_threads: number | null
  gc_pause_avg_ms: number | null
  gc_pause_max_ms: number | null
  gc_p95_ms: number | null
  hikari_active: number | null
  hikari_max: number | null
  hikari_pending: number | null
  llm_qps: number | null
  llm_failure_rate: number | null
  llm_degraded_qps: number | null
  collab_domains: number | null
  heartbeat_pending: number | null
  tool_qps: number | null
  tool_failure_rate: number | null
  tool_circuit_open_qps: number | null
}

export interface MetricsServiceItem {
  job: string
  health: 'up' | 'down' | 'unknown' | string
  qps: number | null
  error_rate: number | null
  avg_latency_ms: number | null
  max_latency_ms: number | null
  http_p95_ms: number | null
  heap_used_bytes: number | null
  heap_max_bytes: number | null
  heap_used_ratio: number | null
  threads: number | null
  hikari_active: number | null
  hikari_max: number | null
  hikari_pending: number | null
  gc_p95_ms: number | null
}

export interface MetricsTargetItem {
  job: string
  instance: string
  health: string
  scrape_url: string
  last_error: string
  last_scrape_at: string | null
}

export interface MetricsAlertItem {
  name: string
  severity: string
  job: string
  instance: string
  state: string
  active_at: string | null
  summary: string
  description: string
}

export interface MetricsOverview {
  available: boolean
  reason?: string
  window: string
  selected_job: string
  generated_at?: string
  sources?: {
    prometheus?: { available: boolean; url?: string }
    targets?: { available: boolean; total: number; up: number }
    alerts?: { available: boolean; active: number }
  }
  summary: MetricsSummary
  services: MetricsServiceItem[]
  targets: MetricsTargetItem[]
  alerts: MetricsAlertItem[]
  support?: {
    http_p95: boolean
    http_p95_reason: string
    gc_p95: boolean
    gc_p95_reason: string
    metric_scope: 'system' | string
  }
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

// ─────────── R-C05(预) 执行视图：四肢层工具（代理 tool-executor） ───────────

/** 工具熔断状态（ToolExecutor.breakerState） */
export interface ToolCircuitState {
  tool_name: string
  state: 'closed' | 'open'
  consecutive_failures: number
  open_until?: number
}

/** 工具注册表条目（GET /api/tool/list → BFF /tools） */
export interface ExecutionTool {
  name: string
  version: string
  description?: string
  /**
   * JSON Schema Draft-07 —— tool-executor 回传的是**字符串**（`ToolMeta.parametersSchema`），
   * 不是已解析对象。取用时需 `JSON.parse`，故联合类型放宽以兼容两种来源。
   */
  parameters_schema?: string | Record<string, unknown>
  /** 参数 Schema 指纹；变更即触发 L1 契约测试（IN-06） */
  schema_hash?: string
  sandbox_required: boolean
  timeout_ms: number
  whitelist_domains?: string[]
  deprecated: boolean
  deprecated_since?: string | null
  removed_after?: string | null
  owner?: string
  /** 注册时间：tool-executor 回传 epoch 毫秒（契约宽松，允许 ISO 串） */
  registered_at?: number | string
  circuit?: ToolCircuitState
}

/** 工具调用审计明细（R5-08）：一次真实调用一行 */
export interface ToolAuditEntry {
  audit_id: string
  call_id?: string
  tenant_id?: string
  tool_name: string
  tool_version?: string
  /** 参数摘要（已脱敏） */
  args?: string
  success: boolean
  output?: string | null
  error_code?: string | null
  error_message?: string | null
  latency_ms: number
  sandboxed: boolean
  sandbox_backend?: string
  degraded?: boolean
  created_at?: number
  requested_by?: string
}

/** 工具执行指标（最近窗口采样，非全量历史） */
export interface ToolMetricsSnapshot {
  window_size?: number
  total_calls: number
  total_failures: number
  blocked_calls: number
  /** 窗口内成功率；无样本时为 null（不填 0 冒充「全部失败」） */
  success_rate: number | null
  p50_ms: number
  p95_ms: number
  p99_ms: number
  calls_by_tool?: Record<string, number>
}

/** 沙箱状态：是否真隔离（degraded=true 表示已降级为受限子进程，非隔离边界） */
export interface ToolSandboxStatus {
  enabled: boolean
  active_backend: string
  isolated: boolean
  degraded: boolean
  docker_available: boolean
  process_fallback_available: boolean
  timeout_ms: number
  memory_mb: number
  note?: string
}

/** 注册表变更记录（IN-06 版本并存 / 废弃期） */
export interface ToolRegistryChange {
  tool_name: string
  version: string
  schema_hash?: string
  /** 实际返回为大写动作名：REGISTER / UPDATE / DEPRECATE */
  action: string
  previous_version?: string | null
  /** epoch 毫秒（契约宽松，允许 ISO 串） */
  changed_at?: number | string
  changed_by?: string
  /** 受影响面：当前实现回传数组（可能为空数组），旧口径曾为字符串 */
  impact?: string[] | string
}

export interface ToolRegistrySummary {
  tool_count: number
  deprecated_count: number
  change_count: number
  storage?: string
  registry_backend?: string
}

export interface ToolChangeLog {
  summary: ToolRegistrySummary | null
  change_log: ToolRegistryChange[]
  semver_policy?: string
  deprecation_policy?: string
}

/** 审计汇总 */
export interface ToolAuditStats {
  backend: string
  degraded: boolean
  total: number
  success: number
  blocked: number
  sandboxed: number
  success_rate: number | null
}

/** 工具执行结果（BFF POST /tools/execute） */
export interface ToolExecutionResult {
  available: boolean
  tenant_id?: string
  tool_name: string
  success?: boolean
  output?: unknown
  error_code?: string
  error_message?: string
  details?: Record<string, string>
  call_id?: string
  audit_id?: string | null
  /** 工具层实测耗时 */
  latency_ms?: number
  /** BFF 侧往返耗时（含网络） */
  bff_latency_ms?: number
  sandboxed?: boolean
  sandbox_backend?: string
  degraded?: boolean
  checked_at?: string
  reason?: string
  data_source?: 'live' | 'mock'
}

/**
 * 执行视图聚合（BFF GET /tools）。
 *
 * `available=false` 表示四肢层不可用（tool-executor 未启动）—— 此时各分片缺失，
 * 界面必须展示"不可用"而不是把缺失渲染成 0；
 * `partial=true` 表示整体可用但个别分片没取到，具体见 `partial_reasons`。
 *
 * `partial_reasons` 里的码**带失败原因后缀**：`_timeout` 是对端在预算内没返回（"慢"），
 * `_missing` 是端点不存在（版本旧），`_unavailable` 才是真连不上。
 * 三者必须分别叙述 —— 把"慢"说成"没有"曾导致沙箱状态被误报不可用。
 * 人类可读解释见 `partial_note`；各子请求预算见 `probe_budget_ms`。
 */
export interface ExecutionOverview {
  available: boolean
  tenant_id?: string
  checked_at?: string
  tool_url?: string
  reason?: string
  /** 四肢层不可用时的机器码：`timeout` / `unreachable` / `endpoint_missing` / `http_error` / `bad_json` */
  reason_code?: string
  gaps?: string[]
  tools?: ExecutionTool[]
  total?: number
  registry_backend?: string
  registry?: ToolChangeLog | null
  metrics?: { metrics: ToolMetricsSnapshot; circuit_breakers: ToolCircuitState[] } | null
  sandbox?: ToolSandboxStatus | null
  audit?: { items: ToolAuditEntry[]; total: number; audit_backend?: string; degraded?: boolean } | null
  audit_stats?: { audit: ToolAuditStats; kafka?: Record<string, unknown> } | null
  partial?: boolean
  partial_reasons?: string[]
  /** 分片缺失的人类可读解释（含"这是慢不是没有"的提示），可能为 null */
  partial_note?: string | null
  /** 各子请求超时预算（毫秒），供排查"慢 vs 没有"用 */
  probe_budget_ms?: { default: number; sandbox: number }
  note?: string
  data_source?: 'live' | 'mock'
}

/**
 * IN-06 工具变更影响分析（BFF `GET /tools/{name}/impact` → `{ impact: {...} }`）。
 *
 * **口径如实**：`affected_agents` / `affected_flows` 只覆盖注册表里
 * **显式登记过**的消费方（`ToolRegistry.registerConsumer`），硬编码调用方不会出现，
 * 因此列表为空**不等于没有影响**（`note` 会说明这一点）。
 */
export interface ToolImpactReport {
  tool_name: string
  current_version?: string | null
  schema_hash?: string
  affected_agents: string[]
  affected_flows: string[]
  /** 历史上发生过 schema 变更（版本维度） */
  schema_changed: boolean
  /** 有变更且存在登记消费方 → 需回归其 L1 契约测试 */
  contract_test_required: boolean
  note?: string
  /** 前端本地标注（非上游字段） */
  data_source?: 'live' | 'mock'
}

// ─────────── WB-10 模型接入配置（前台可配的大模型接口）───────────
//
// 真相源是 nlp-service 的 `llm_model_config` 表，BFF 只做代理。
// **密钥口径**：响应里只会出现 `api_key_hint`（`sk-***last4`），
// 明文与密文都不出接口 —— 前端也**不得**把 api_key 回填到表单里冒充已有值。

/** 功能角色（决定该模型被哪些链路调用） */
export type ModelRoleKey = 'intent' | 'embed' | 'rerank' | 'generate' | 'plan' | 'code'

export interface ModelRole {
  key: ModelRoleKey | string
  label: string
  default_tier: string
  default_timeout_ms: number
  description: string
}

export interface ModelProvider {
  key: string
  default_base_url: string
}

export interface ModelConfig {
  id: number
  tenant_id: string
  config_key: string
  role_label: string
  name: string
  provider: string
  base_url: string
  model: string
  /** 脱敏后的密钥（`sk-***last4`）；空串表示未配置凭据 */
  api_key_hint: string
  has_api_key: boolean
  tier: string
  max_tokens: number | null
  temperature: number | null
  timeout_ms: number | null
  routing_weight: number
  enabled: boolean
  extra?: Record<string, unknown>
  last_probe_at?: string | null
  last_probe_ok?: boolean | null
  last_probe_latency_ms?: number | null
  last_probe_error?: string | null
  created_at?: string
  updated_at?: string
}

/** 创建/局部更新的请求体。`api_key` 省略 = 保留原凭据；空串 = 清空。 */
export interface ModelConfigUpsert {
  config_key?: string
  name?: string
  provider?: string
  base_url?: string
  model?: string
  api_key?: string
  tier?: string
  max_tokens?: number | null
  temperature?: number | null
  timeout_ms?: number | null
  routing_weight?: number | null
  enabled?: boolean
  extra?: Record<string, unknown>
}

/** 配置改动后的引擎重载摘要 —— 没有它就分不清「配了没生效」还是「模型侧有问题」 */
export interface ModelReloadBrief {
  ok: boolean
  roles: string[]
  count: number
  error: string
}

export interface ModelConfigList {
  available: boolean
  tenant_id?: string
  reason?: string
  items: ModelConfig[]
  by_role: Record<string, ModelConfig[]>
  total?: number
  enabled?: number
  roles?: ModelRole[]
  providers?: ModelProvider[]
  storage?: {
    backend?: string
    degraded?: boolean
    reason?: string
    dsn?: string
    key_source?: string
    key_source_warning?: string
    applied_ddl?: number
  }
  /** 当前**实际装配**的角色引擎（与配置可能不同步，reload 后才一致） */
  llm?: { role_configured: string[]; engines: Record<string, Array<Record<string, unknown>>> }
}

export interface ModelProbeResult {
  available?: boolean
  id?: number
  name?: string
  role?: string
  supported: boolean
  ok: boolean
  latency_ms?: number | null
  detail?: string
  reason?: string
  status?: number | null
  checked_at?: string
}

/** 用量分桶计数（calls 只记成功；failures 单列，两者相加才是尝试次数） */
export interface ModelUsageCounters {
  calls: number
  failures: number
  prompt_tokens: number
  completion_tokens: number
  latency_ms_sum: number
}

export interface ModelUsageDaily extends ModelUsageCounters {
  date: string
}

/** Token 用量看板数据（WB-10 后半句）。`estimated_share` 是**诚实度指标**：
 *  不为 0 时上面的 token 数只能当趋势看，不能当账单看。 */
export interface ModelUsage {
  available: boolean
  tenant_id?: string
  reason?: string
  window?: { days: number; start?: string; end?: string }
  totals?: ModelUsageCounters & { tokens?: number; attempts?: number; success_rate?: number | null }
  by_role?: Record<string, ModelUsageCounters>
  by_model?: Record<string, ModelUsageCounters>
  by_token_source?: Record<string, number>
  estimated_share?: number
  daily?: ModelUsageDaily[]
  storage?: { degraded?: boolean; backend?: string; reason?: string; note?: string }
}

export type ModelRuntimeState = 'active' | 'degraded' | 'offline' | 'unverified' | 'disabled'

export interface ModelRuntimeUsage {
  calls: number
  failures: number
  attempts: number
  success_rate: number | null
  tokens: number
  avg_latency_ms: number | null
  share: number | null
}

export interface ModelRuntimeItem {
  config_id: number
  config_key: string
  role_label: string
  name: string
  provider: string
  model: string
  tier: string
  enabled: boolean
  routing_weight: number
  runtime_state: ModelRuntimeState
  probe: {
    state: 'passed' | 'failed' | 'unverified'
    ok: boolean | null
    at: string | null
    latency_ms: number | null
    error: string
  }
  usage: ModelRuntimeUsage | null
  prometheus: {
    qps?: number
    failure_qps?: number
    failure_rate?: number | null
    p95_latency_seconds?: number
    p95_latency_ms?: number | null
  } | null
}

export interface ModelRoutingEntry {
  role: string
  role_label: string
  mode: 'role_weight_fallback'
  primary_config_id: number | null
  candidates: Array<{
    config_id: number
    name: string
    provider: string
    model: string
    routing_weight: number
    probe_state: 'passed' | 'failed' | 'unverified'
  }>
  usage: {
    calls: number
    failures: number
    attempts: number
    avg_latency_ms: number | null
  } | null
}

export interface ModelRuntimeOverview {
  available: boolean
  tenant_id?: string
  reason?: string
  window?: { days: number; start?: string; end?: string }
  generated_at?: string
  sources?: {
    config?: { available: boolean; backend?: string; degraded?: boolean }
    usage?: { available: boolean; degraded?: boolean; reason?: string }
    prometheus?: {
      available: boolean
      complete: boolean
      availability: Record<string, boolean>
      by_role: Record<string, Record<string, number | null>>
      reason?: string
    }
  }
  items: ModelRuntimeItem[]
  routing: ModelRoutingEntry[]
  unsupported_fields?: Record<string, { available: false; reason: string }>
}

/** 写路径结果（统一信封：成功回传 item/reload，失败带 code） */
export interface ModelWriteResult {
  ok: boolean
  data?: Record<string, unknown>
  code?: string
  message?: string
  details?: Record<string, unknown>
}

export type ModuleId =
  | 'overview' | 'vitals' | 'brain' | 'senses' | 'evolution' | 'collab'
  | 'tasks' | 'chat' | 'experts' | 'skills' | 'connectors' | 'automation' | 'models' | 'remote'
  | 'cases' | 'approvals'
  | 'middleware' | 'tracing'

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
  citations?: Array<{ title: string; source: string }>
  created_at: string
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
  items: MiddlewareNode[]
  summary: { total: number; up: number; down: number }
  checked_at: string
}

export interface TracingServiceStat {
  name: string
  spans_24h: number
  error_rate: number
  p99_ms: number
}

export interface TracingRecentTrace {
  trace_id: string
  service: string
  operation: string
  duration_ms: number
  spans: number
  time: string
  status: 'ok' | 'error'
}

export interface TracingOverview {
  enabled: boolean
  ui_url: string
  services: TracingServiceStat[]
  recent: TracingRecentTrace[]
  checked_at: string
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

export type ModuleId =
  | 'overview' | 'vitals' | 'brain' | 'senses' | 'evolution' | 'collab'
  | 'tasks' | 'chat' | 'experts' | 'skills' | 'connectors' | 'automation'
  | 'cases' | 'approvals'

export type ModuleGroup = '生命体区' | '工作台区' | '治理区'

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

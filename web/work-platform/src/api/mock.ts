import type { ChatMessage, MetricCard, TaskItem } from '../types'

export const metrics: MetricCard[] = [
  { label: '活跃任务', value: 12, unit: '个', trend: '+3', tone: 'primary' },
  { label: '并发 Agent', value: 6, unit: '个', trend: '峰值 8', tone: 'success' },
  { label: '知识文档', value: 1284, unit: '篇', trend: '+36', tone: 'primary' },
  { label: '本月成本', value: 1280.5, unit: '元', trend: '-8%', tone: 'warning' },
]

export const tasks: TaskItem[] = [
  { task_id: 'T-1042', title: '金融竞品调研报告', type: 'expert_team', state: 'running', progress: 62, priority: 'P0', agent: '团长+5专家', updated_at: '09:02' },
  { task_id: 'T-1041', title: '知识库质量巡检', type: 'automation', state: 'done', progress: 100, priority: 'P1', agent: '质检 Agent', updated_at: '08:40' },
  { task_id: 'T-1038', title: 'Q2 财报摘要', type: 'single_agent', state: 'pending', progress: 0, priority: 'P1', agent: '数据 Agent', updated_at: '08:21' },
  { task_id: 'T-1035', title: '连接器授权修复', type: 'system', state: 'failed', progress: 35, priority: 'P0', agent: '系统', updated_at: '昨天' },
]

export const chatMessages: ChatMessage[] = [
  { role: 'user', content: '汇总一下当前 Agent 架构的调研结论。', created_at: '09:02' },
  { role: 'assistant', content: '已完成初步汇总。架构包含 Vue 工作平台、Gateway、Session、NLP、Body 与协作总线，M1 重点验证问答和任务闭环。', citations: [{ title: '架构设计 v3.2', source: 'knowledge' }], created_at: '09:02' },
]

export const serviceHealth = [
  { name: 'gateway-service', port: 8080, status: 'UP' },
  { name: 'session-manager', port: 8081, status: 'UP' },
  { name: 'sense-service', port: 8082, status: 'UP' },
  { name: 'body-service', port: 8083, status: 'UP' },
  { name: 'nlp-service', port: 8000, status: 'UP' },
]


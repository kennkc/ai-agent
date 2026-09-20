import type { WorkModule } from '../types'

export const modules: WorkModule[] = [
  { id: 'overview', path: '/overview', title: '总览', group: '生命体区', phase: 'P1', status: 'ready', description: '生命体征、任务、知识与成本摘要', icon: 'DataBoard' },
  { id: 'vitals', path: '/vitals', title: '生命体征', group: '生命体区', phase: 'P1', status: 'ready', description: '心跳、负载、延迟、成功率与免疫指数', icon: 'Odometer' },
  { id: 'brain', path: '/brain', title: '大脑决策沙盘', group: '生命体区', phase: 'M1', status: 'prototype', description: '思考链回放与决策审计', icon: 'Cpu' },
  { id: 'senses', path: '/senses', title: '五感矩阵', group: '生命体区', phase: 'P2', status: 'prototype', description: '视觉、听觉、触觉、嗅觉、味觉采集状态', icon: 'View' },
  { id: 'evolution', path: '/evolution', title: '进化视图', group: '生命体区', phase: 'P8', status: 'prototype', description: '反馈、自愈、幻觉率与满意度', icon: 'Refresh' },
  { id: 'collab', path: '/collab', title: '协作总线', group: '生命体区', phase: 'P3/P6', status: 'prototype', description: '多 Agent 协作域、心跳与 DAG 执行', icon: 'Share' },
  { id: 'execution', path: '/execution', title: '执行视图', group: '生命体区', phase: 'P5', status: 'prototype', description: '工具调用流、拦截记录与沙箱隔离状态（R-C05 预）', icon: 'SetUp' },
  { id: 'tasks', path: '/tasks', title: '任务中心', group: '工作台区', phase: 'P1', status: 'ready', description: '任务生命周期、筛选与进度', icon: 'List' },
  { id: 'chat', path: '/chat', title: '任务对话', group: '工作台区', phase: 'P1', status: 'ready', description: '对话追问与结果工作区', icon: 'ChatDotRound' },
  { id: 'experts', path: '/experts', title: '专家团队', group: '工作台区', phase: 'P3', status: 'prototype', description: '专家档案、工具白名单与输出 Schema', icon: 'UserFilled' },
  { id: 'skills', path: '/skills', title: '技能市场', group: '工作台区', phase: 'P3', status: 'prototype', description: '技能包注册、安装、审核与版本', icon: 'Grid' },
  { id: 'connectors', path: '/connectors', title: '连接器', group: '工作台区', phase: 'P5', status: 'prototype', description: 'MCP 连接器注册、授权与健康', icon: 'Connection' },
  { id: 'automation', path: '/automation', title: '自动化', group: '工作台区', phase: 'P7', status: 'prototype', description: 'cron 任务、推送与失败重试', icon: 'Timer' },
  { id: 'models', path: '/models', title: '多模型管理', group: '工作台区', phase: 'P5', status: 'prototype', description: '模型路由、Token 成本、质量与健康度', icon: 'Cpu' },
  { id: 'remote', path: '/remote', title: '远程 IM 遥控', group: '工作台区', phase: 'P7', status: 'prototype', description: '手机 IM 下发任务与结果回传', icon: 'ChatDotRound' },
  { id: 'cases', path: '/cases', title: '灵感案例', group: '工作台区', phase: 'P8', status: 'prototype', description: '案例模板与“做同款”', icon: 'Collection' },
  { id: 'approvals', path: '/approvals', title: '免疫审批', group: '治理区', phase: 'P7', status: 'prototype', description: 'L1-L4 审批队列与双人复核', icon: 'Checked' },
  { id: 'middleware', path: '/middleware', title: '中间件监控', group: '观测区', phase: 'P1', status: 'prototype', description: '中间件真实启停状态（wp-bff TCP 探针）', icon: 'Monitor' },
  { id: 'services', path: '/services', title: '后台服务', group: '观测区', phase: 'P1', status: 'ready', description: '应用服务监控与本地受控启停', icon: 'Monitor' },
  { id: 'knowledge', path: '/knowledge', title: '躯体知识库', group: '观测区', phase: 'P3', status: 'ready', description: '知识量、检索 P99 与命中率、三层存储、检索测试', icon: 'Files' },
  { id: 'tracing', path: '/tracing', title: '链路追踪', group: '观测区', phase: 'P1', status: 'prototype', description: 'Jaeger 分布式链路、服务延迟与错误率', icon: 'Guide' },
]

export const moduleMap = Object.fromEntries(modules.map(item => [item.id, item])) as Record<WorkModule['id'], WorkModule>


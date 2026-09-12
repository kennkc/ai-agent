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

export const vitalSigns = [
  { key: 'heart_rate', label: '心率 / QPS', value: 186, unit: 'req/s', status: 'healthy', trend: '+12%' },
  { key: 'body_temp', label: '体温 / 负载', value: 62, unit: '%', status: 'healthy', trend: '+4%' },
  { key: 'blood_pressure', label: '血压 / 延迟', value: 286, unit: 'ms', status: 'warning', trend: '-8%' },
  { key: 'blood_oxygen', label: '血氧 / 成功率', value: 99.2, unit: '%', status: 'healthy', trend: '+0.2%' },
  { key: 'immune_index', label: '免疫 / 安全', value: 96, unit: '分', status: 'healthy', trend: '+1.5%' },
]

export const organs = [
  { organ: '大脑', score: 94, state: '健康', note: '意图与规划稳定' },
  { organ: '小脑', score: 88, state: '健康', note: '编排队列正常' },
  { organ: '感官', score: 82, state: '亚健康', note: 'OCR 渠道待校准' },
  { organ: '躯体', score: 91, state: '健康', note: '检索质量稳定' },
  { organ: '四肢', score: 86, state: '健康', note: '工具成功率 94%' },
  { organ: '免疫', score: 97, state: '健康', note: '无高风险事件' },
]

export const brainChain = [
  { step: '意图识别', model: 'L0-Rule', latency: '42ms', confidence: 0.96, io: '用户问题 → 知识问答 / 金融领域' },
  { step: '任务规划', model: 'L2-Plan', latency: '318ms', confidence: 0.91, io: '知识问答 → 检索 → 生成 → 校验' },
  { step: '知识检索', model: 'Qdrant + BM25', latency: '286ms', confidence: 0.88, io: '金融竞品 → TOP5 文档片段' },
  { step: '分析推理', model: 'L2-LLM', latency: '1.4s', confidence: 0.93, io: '文档片段 → 结构化对比' },
  { step: '内容生成', model: 'L2-LLM', latency: '1.8s', confidence: 0.92, io: '分析结果 → 报告草稿' },
  { step: '自校验', model: 'L1-Judge', latency: '220ms', confidence: 0.95, io: '报告草稿 → 来源覆盖/事实检查' },
]

export const senses = [
  { name: '视觉', icon: 'View', state: 'UP', count: 128, quality: 92, recent: 'OCR: 财报截图', tone: 'success' },
  { name: '听觉', icon: 'Microphone', state: 'UP', count: 36, quality: 88, recent: '会议录音转写', tone: 'success' },
  { name: '触觉', icon: 'Document', state: 'UP', count: 512, quality: 96, recent: 'URL/文件采集', tone: 'success' },
  { name: '嗅觉', icon: 'Promotion', state: 'DEGRADED', count: 204, quality: 74, recent: '舆情源限流', tone: 'warning' },
  { name: '味觉', icon: 'Checked', state: 'UP', count: 680, quality: 98, recent: '六维质检通过', tone: 'success' },
]

export const evolution = {
  metrics: [
    { label: '正向反馈', value: 428, unit: '条' },
    { label: '负向反馈', value: 36, unit: '条' },
    { label: '幻觉率', value: 2.1, unit: '%' },
    { label: '满意度', value: 91, unit: '分' },
  ],
  healings: [
    { doc: '金融竞品-2025Q2.md', issue: '来源过期', action: '重新采集并替换', recurrence: '否' },
    { doc: '芯片行业 FAQ.md', issue: '事实冲突', action: '冲突仲裁后更新', recurrence: '否' },
    { doc: '客户案例库.md', issue: '重复内容', action: '语义去重', recurrence: '否' },
  ],
}

export const collaboration = {
  domain_id: 'collab-20260812-0042',
  task_id: 'T-1042',
  mode: 'fanout',
  agents: [
    { name: '团长 Agent', role: 'Leader', progress: 100 },
    { name: '检索专家', role: 'Retrieval', progress: 100 },
    { name: '分析专家', role: 'Analysis', progress: 88 },
    { name: '生成专家', role: 'Writing', progress: 72 },
    { name: '质检 Agent', role: 'Verify', progress: 64 },
  ],
  messages: [
    { type: 'dispatch', text: '团长 → 5 专家：竞品调研子任务分派', time: '09:12:04' },
    { type: 'heartbeat', text: '分析专家：进度 88%', time: '09:12:28' },
    { type: 'result', text: '检索专家：12 个来源已回传', time: '09:13:02' },
    { type: 'artifact', text: '共享工件层：evidence.json 已更新', time: '09:13:09' },
    { type: 'negotiate', text: '分析专家 vs 质检 Agent：置信度仲裁', time: '09:13:32' },
  ],
  artifacts: [
    { name: 'evidence.json', source: '检索专家', size: '82KB', state: 'verified' },
    { name: 'analysis.md', source: '分析专家', size: '16KB', state: 'verified' },
    { name: 'report-draft.md', source: '生成专家', size: '42KB', state: 'review' },
  ],
  gates: [
    { name: 'Schema 校验', state: 'PASS' },
    { name: '交叉验证', state: 'PASS' },
    { name: '置信度门', state: 'PASS' },
    { name: 'L3 审批', state: 'PENDING' },
  ],
}

export const experts = [
  { expert_id: 'E-01', name: '行业研究员', domain: '信息调研', persona: '严谨、来源优先', methodology: '多源检索 → 交叉验证 → 报告', tools: ['search', 'retrieval'], state: 'active' },
  { expert_id: 'E-02', name: '数据分析师', domain: '数据分析', persona: '冷静的数据洞察者', methodology: '清洗 → 分析 → 可视化 → 结论', tools: ['sql', 'chart'], state: 'active' },
  { expert_id: 'E-03', name: '金融领域专家', domain: '金融', persona: '风险敏感、结论克制', methodology: '公告 → 财务 → 风险提示', tools: ['search', 'financial-api'], state: 'active' },
  { expert_id: 'E-04', name: '文档审查员', domain: '质量审核', persona: '挑错优先', methodology: 'Schema → 事实 → 引用', tools: ['diff', 'audit'], state: 'suspended' },
]

export const skills = [
  { skill_id: 'S-101', name: '深度调研', category: 'Research', version: '2.1.0', installs: 128, rating: 4.8, state: 'installed' },
  { skill_id: 'S-102', name: '财报分析', category: 'Finance', version: '1.6.2', installs: 86, rating: 4.7, state: 'available' },
  { skill_id: 'S-103', name: '长文写作', category: 'Writing', version: '3.0.1', installs: 203, rating: 4.9, state: 'available' },
  { skill_id: 'S-104', name: '代码执行', category: 'Tooling', version: '1.2.0', installs: 64, rating: 4.5, state: 'available' },
]

export const connectors = [
  { connector_id: 'C-01', name: '腾讯文档', protocol: 'MCP', state: 'online', latency: 42, calls: 1260 },
  { connector_id: 'C-02', name: '飞书', protocol: 'MCP', state: 'pending_auth', latency: 0, calls: 0 },
  { connector_id: 'C-03', name: '企业微信', protocol: 'MCP', state: 'offline', latency: 0, calls: 880 },
]

export const automations = [
  { auto_name: '科创板行情摘要', cron: '0 9 * * 1-5', action: '检索 + 生成 + 推送', state: 'active', last_run: '今日 09:00', next_run: '明日 09:00', push: '工作区 + 邮件' },
  { auto_name: '周报生成', cron: '0 18 * * 5', action: '汇总 + 生成周报', state: 'active', last_run: '08-07', next_run: '08-14', push: '工作区' },
  { auto_name: '知识库质量巡检', cron: '0 2 * * *', action: '污染检测 + 自愈', state: 'active', last_run: '今日 02:00', next_run: '明日 02:00', push: '免疫审批' },
  { auto_name: '舆情日报', cron: '0 8 * * *', action: '舆情采集 + 情感分析', state: 'paused', last_run: '08-05', next_run: '-', push: 'IM' },
]

export const cases = [
  { case_id: 'CASE-01', title: '行业竞品调研报告', category: 'Research', reuse_count: 86, experts: ['行业研究员', '数据分析师'], skills: ['深度调研'] },
  { case_id: 'CASE-02', title: '上市公司财报摘要', category: 'Finance', reuse_count: 54, experts: ['金融领域专家'], skills: ['财报分析'] },
  { case_id: 'CASE-03', title: '周度知识库巡检', category: 'Ops', reuse_count: 31, experts: ['文档审查员'], skills: ['长文写作'] },
]

export const approvals = [
  { approval_id: 'A-3021', action: '删除过期知识文档', risk_level: 'L3', applicant: '质检 Agent', state: 'pending', context: '目标文档：legacy-report.md' },
  { approval_id: 'A-3022', action: '调用外部写接口', risk_level: 'L4', applicant: '连接器-Agent', state: 'pending', context: '目标：企业微信消息推送' },
  { approval_id: 'A-3019', action: '启用自动化任务', risk_level: 'L2', applicant: '调度器', state: 'approved', context: '科创板行情摘要' },
]


export const notifications = [
  { id: 'N-01', type: 'approval', title: 'L3 审批待处理', text: '质检 Agent 请求删除过期知识文档', time: '2 分钟前', unread: true },
  { id: 'N-02', type: 'task', title: '任务执行完成', text: '知识库质量巡检已完成', time: '18 分钟前', unread: true },
  { id: 'N-03', type: 'healing', title: '知识自愈完成', text: '金融竞品文档已重新采集并替换', time: '42 分钟前', unread: false },
]

export const searchIndex = [
  { type: '任务', title: '金融竞品调研报告', text: 'T-1042 · running · 团长+5专家' },
  { type: '知识', title: '架构设计 v3.2', text: '32 组件 · Vue 3 工作平台 · C1-C32' },
  { type: '技能', title: '深度调研', text: 'Research · v2.1.0 · 已安装' },
  { type: '案例', title: '行业竞品调研报告', text: '做同款 · 已复用 86 次' },
]

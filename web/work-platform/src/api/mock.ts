import type {
  ApprovalItem, AutomationItem, CaseItem, ChatMessage, CollaborationMessage, ConnectorItem,
  EvolutionMetric, ExpertProfile, HealingRecord, ManagedModel, MetricCard, MiddlewareOverview, ModelCallPoint, ModelRoute, ModelRuntimeNode, NotificationItem, OnlineAgent, OptimizationSuggestion, OrganHealth, OverviewWorkflow, RemoteChannel, RemoteFlowEvent,
  ResultArtifact, SearchItem, SenseChannel, SkillItem, SuggestionExecution, TaskItem, TracingOverview, VitalSign, MiddlewareNode,
  ExecutionOverview, ToolExecutionResult, ToolImpactReport,
} from '../types'

export const metrics: MetricCard[] = [
  { label: '活跃任务', value: 12, unit: '个', trend: '+3 今日新增', tone: 'primary' },
  { label: '并发 Agent', value: 6, unit: '个', trend: '峰值 8 / 上限 8', tone: 'success' },
  { label: '知识文档', value: 1284, unit: '篇', trend: '+36 本周', tone: 'primary' },
  { label: '本月成本', value: 1280.5, unit: '元', trend: '预算使用 64%', tone: 'warning' },
]

export const tasks: TaskItem[] = [
  { task_id: 'T-1042', title: '金融竞品调研报告', type: '专家团协作', state: 'running', progress: 62, priority: 'P0', agent: '团长+5专家', updated_at: '09:02' },
  { task_id: 'T-1041', title: '知识库质量巡检', type: '自动化', state: 'done', progress: 100, priority: 'P1', agent: '质检 Agent', updated_at: '08:40' },
  { task_id: 'T-1038', title: 'Q2 财报摘要', type: '单 Agent', state: 'pending', progress: 0, priority: 'P1', agent: '数据 Agent', updated_at: '08:21' },
  { task_id: 'T-1035', title: '连接器授权修复', type: '系统任务', state: 'failed', progress: 35, priority: 'P0', agent: '系统', updated_at: '昨天' },
  { task_id: 'T-1029', title: '季度知识归档', type: '单 Agent', state: 'archived', progress: 100, priority: 'P2', agent: '归档 Agent', updated_at: '08-28' },
]

export const chatMessages: ChatMessage[] = [
  { role: 'user', content: '汇总一下当前 Agent 架构的调研结论。', created_at: '09:02' },
  {
    role: 'assistant',
    content: '已完成初步汇总。架构包含 Vue 工作平台、Gateway、Session、NLP、Body 与协作总线，M1 重点验证问答、任务闭环与决策审计。',
    citations: [
      { title: '架构设计 v3.2', source: 'knowledge/architecture' },
      { title: 'Phase1 验收记录', source: 'audit/phase1' },
    ],
    created_at: '09:02',
  },
]

export const resultArtifacts: ResultArtifact[] = [
  { artifact_id: 'ART-01', name: '金融竞品调研报告.md', type: 'document', state: 'ready', size: '42 KB', updated_at: '09:08', preview: '# 金融竞品调研报告\n\n1. 市场格局\n2. 产品能力对比\n3. 风险与机会\n\n来源：12 份文档，置信度 93%。' },
  { artifact_id: 'ART-02', name: '增长趋势对比图.png', type: 'image', state: 'ready', size: '1.2 MB', updated_at: '09:07', preview: '可视化预览：2024-2026 年五家竞品增长曲线。' },
  { artifact_id: 'ART-03', name: 'evidence.json', type: 'data', state: 'review', size: '82 KB', updated_at: '09:05', preview: '{\n  "source_count": 12,\n  "verified": 10,\n  "pending": 2\n}' },
]

export const serviceHealth = [
  { name: 'gateway-service', port: 8080, status: 'UP', latency: '18ms' },
  { name: 'session-manager', port: 8081, status: 'UP', latency: '36ms' },
  { name: 'sense-service', port: 8082, status: 'UP', latency: '52ms' },
  { name: 'body-service', port: 8083, status: 'UP', latency: '74ms' },
  { name: 'nlp-service', port: 8000, status: 'UP', latency: '126ms' },
]

export const vitalSigns: VitalSign[] = [
  { key: 'heart_rate', label: '心率 / QPS', value: 186, unit: 'req/s', status: 'healthy', trend: '+12%', threshold: '异常 > 400 req/s' },
  { key: 'body_temp', label: '体温 / 负载', value: 62, unit: '%', status: 'healthy', trend: '+4%', threshold: '异常 > 85%' },
  { key: 'blood_pressure', label: '血压 / 延迟', value: 286, unit: 'ms', status: 'warning', trend: '-8%', threshold: 'SLO < 500ms' },
  { key: 'blood_oxygen', label: '血氧 / 成功率', value: 99.2, unit: '%', status: 'healthy', trend: '+0.2%', threshold: '异常 < 95%' },
  { key: 'immune_index', label: '免疫 / 安全', value: 96, unit: '分', status: 'healthy', trend: '+1.5%', threshold: '异常 < 80 分' },
]

export const organs: OrganHealth[] = [
  { organ: '大脑', score: 94, state: '健康', baseline: true, note: '意图与规划稳定' },
  { organ: '小脑', score: 88, state: '健康', baseline: true, note: '编排队列正常' },
  { organ: '感官', score: null, state: '待校准', baseline: false, note: '历史基线不足 7 天，暂不评分' },
  { organ: '躯体', score: 91, state: '健康', baseline: true, note: '检索质量稳定' },
  { organ: '四肢', score: 86, state: '健康', baseline: true, note: '工具成功率 94%' },
  { organ: '免疫', score: 97, state: '健康', baseline: true, note: '无高风险事件' },
]

export const brainDecision = {
  decision_id: 'DEC-20260912-0042',
  task_id: 'T-1042',
  created_at: '2026-09-12 09:02:18',
  state: 'completed',
  confidence: 93,
  audit_state: 'PASS',
  replay_latency: 420,
  sources: [
    { title: '2025 金融科技竞争格局', score: 0.94, chunk: 'chunk-0081' },
    { title: '主流产品能力对比', score: 0.91, chunk: 'chunk-0142' },
    { title: '行业监管年报', score: 0.88, chunk: 'chunk-0037' },
  ],
}

export const brainChain = [
  { step: '意图识别', model: 'L0-Rule', latency: '42ms', confidence: 0.96, io: '输入：用户问题\n输出：知识问答 / 金融领域 / 需要归因' },
  { step: '任务规划', model: 'L2-Plan', latency: '318ms', confidence: 0.91, io: '输入：意图槽位\n输出：检索 → 分析 → 生成 → 自校验' },
  { step: '知识检索', model: 'Qdrant + BM25', latency: '286ms', confidence: 0.88, io: '输入：金融竞品\n输出：TOP5 文档片段 + 来源评分' },
  { step: '分析推理', model: 'L2-LLM', latency: '1.4s', confidence: 0.93, io: '输入：文档片段\n输出：结构化竞品对比与风险项' },
  { step: '内容生成', model: 'L2-LLM', latency: '1.8s', confidence: 0.92, io: '输入：分析结果\n输出：报告草稿与引用映射' },
  { step: '自校验', model: 'L1-Judge', latency: '220ms', confidence: 0.95, io: '输入：报告草稿\n输出：来源覆盖 PASS / 事实检查 PASS' },
]

export const senses: SenseChannel[] = [
  { channel_id: 'SEN-01', name: '视觉', icon: 'View', state: 'UP', count: 128, quality: 92, recent: 'OCR: 财报截图', reason: '通道健康', source: 'OCR / 图片解析', last_sync: '12 秒前', samples: [{ time: '09:12', title: '2025Q2 财报截图', quality: 96 }, { time: '08:54', title: '产品界面截图', quality: 88 }] },
  { channel_id: 'SEN-02', name: '听觉', icon: 'Microphone', state: 'UP', count: 36, quality: 88, recent: '会议录音转写', reason: '通道健康', source: 'ASR / 会议流', last_sync: '36 秒前', samples: [{ time: '09:06', title: '周会录音 32 分钟', quality: 91 }, { time: '昨天', title: '用户访谈片段', quality: 85 }] },
  { channel_id: 'SEN-03', name: '触觉', icon: 'Document', state: 'UP', count: 512, quality: 96, recent: 'URL/文件采集', reason: '通道健康', source: 'Web / 文件系统', last_sync: '8 秒前', samples: [{ time: '09:14', title: '行业报告 PDF', quality: 98 }, { time: '09:10', title: '竞品官网 URL', quality: 94 }] },
  { channel_id: 'SEN-04', name: '嗅觉', icon: 'Promotion', state: 'DEGRADED', count: 204, quality: 74, recent: '舆情源限流', reason: '第三方舆情源触发限流，采集延迟上升', source: '舆情 / 社媒流', last_sync: '4 分钟前', samples: [{ time: '09:08', title: '微博舆情批次', quality: 72 }, { time: '08:41', title: '新闻舆情批次', quality: 76 }] },
  { channel_id: 'SEN-05', name: '味觉', icon: 'Checked', state: 'UP', count: 680, quality: 98, recent: '六维质检通过', reason: '通道健康', source: '质量评估引擎', last_sync: '18 秒前', samples: [{ time: '09:13', title: '报告事实性抽检', quality: 99 }, { time: '08:58', title: '引用完整性抽检', quality: 97 }] },
]

export const evolutionMetrics: EvolutionMetric[] = [
  { label: '正向反馈', value: 428, unit: '条' },
  { label: '负向反馈', value: 36, unit: '条' },
  { label: '幻觉率', value: 2.1, unit: '%' },
  { label: '满意度', value: 91, unit: '分' },
]

export const healingRecords: HealingRecord[] = [
  { healing_id: 'H-2081', doc: '金融竞品-2025Q2.md', issue: '来源过期', action: '重新采集并替换', recurrence: '否', created_at: '09:24', detail: '原引用链接失效，重新抓取后覆盖 3 个段落并通过引用校验。' },
  { healing_id: 'H-2079', doc: '芯片行业 FAQ.md', issue: '事实冲突', action: '冲突仲裁后更新', recurrence: '否', created_at: '昨天 18:12', detail: '两个来源对产能数据口径不一致，采用公司公告源并标记争议。' },
  { healing_id: 'H-2074', doc: '宏观指标说明.md', issue: '过期指标', action: '重算并更新快照', recurrence: '是', created_at: '08-29', detail: '指标口径变更导致复发，已新增口径版本字段避免再次误用。' },
]

export const evolution = {
  metrics: evolutionMetrics,
  healings: healingRecords,
  trend: [
    { week: 'W27', positive: 44, negative: 8, hallucination: 3.4, satisfaction: 86 },
    { week: 'W28', positive: 52, negative: 7, hallucination: 3.1, satisfaction: 87 },
    { week: 'W29', positive: 61, negative: 6, hallucination: 2.8, satisfaction: 88 },
    { week: 'W30', positive: 68, negative: 5, hallucination: 2.6, satisfaction: 89 },
    { week: 'W31', positive: 72, negative: 5, hallucination: 2.4, satisfaction: 90 },
    { week: 'W32', positive: 67, negative: 3, hallucination: 2.1, satisfaction: 91 },
  ],
}

export const collaboration = {
  domain_id: 'DOM-2048',
  task_id: 'T-1042',
  mode: 'fanout',
  mode_label: '扇出 Fan-out',
  protocol: 'MC-P v1',
  concurrency_current: 5,
  concurrency_limit: 8,
  p99_ms: 7.8,
  ack_rate: 99.98,
  messages_per_sec: 42,
  updated_at: '1.8 秒前',
  agents: [
    { agent_id: 'AG-01', name: '团长 Agent', role: 'Planner / Merger', progress: 72, state: 'running', current_task: 'DAG 规划与结果合并', use_case: '多 Agent 任务编排', model: 'L2-Plan', tools: ['planner', 'merger'], artifact_count: 1, confidence: 0.96, bus_position: 14, bus_message: 'dispatch ×5', bus_kind: 'dispatch', is_leader: true },
    { agent_id: 'AG-02', name: '调研 Agent', role: 'Researcher', progress: 100, state: 'done', current_task: '12 份资料已回传', use_case: '竞品资料调研', model: 'L2-LLM', tools: ['search', 'retrieval'], artifact_count: 2, confidence: 0.94, bus_position: 72, bus_message: 'result ✓', bus_kind: 'result' },
    { agent_id: 'AG-03', name: '数据 Agent', role: 'Analyst', progress: 84, state: 'running', current_task: '增长数据归一化', use_case: '增长数据分析', model: 'L2-LLM', tools: ['sql', 'chart'], artifact_count: 1, confidence: 0.88, bus_position: 84, bus_message: 'running 84%', bus_kind: 'running' },
    { agent_id: 'AG-04', name: '金融 Agent', role: 'Domain Expert', progress: 68, state: 'running', current_task: '监管风险与口径校验', use_case: '金融合规审查', model: 'L2-LLM', tools: ['financial-api'], artifact_count: 1, confidence: 0.91, bus_position: 68, bus_message: 'negotiate', bus_kind: 'running' },
    { agent_id: 'AG-05', name: '审查 Agent', role: 'Verifier', progress: 24, state: 'waiting', current_task: '等待交叉验证输入', use_case: '结果质量验收', model: 'L1-Judge', tools: ['diff', 'audit'], artifact_count: 0, confidence: 0, waiting_for: 'C27 合并结果', bus_position: 24, bus_message: 'waiting', bus_kind: 'waiting' },
  ],
  messages: [
    { message_id: 'M-9001', time: '09:02:18', type: 'dispatch', from: '团长 Agent', to: '调研 Agent', text: '派发资料检索任务', payload: { task: 'collect_market_docs', limit: 20, timeout_ms: 60000 } },
    { message_id: 'M-9002', time: '09:03:42', type: 'heartbeat', from: '调研 Agent', to: '团长 Agent', text: '检索进度 60%', payload: { progress: 60, found: 12, failures: 0 } },
    { message_id: 'M-9003', time: '09:05:06', type: 'result', from: '调研 Agent', to: '团长 Agent', text: '回传 12 份有效资料', payload: { artifact_id: 'ART-EVIDENCE', quality: 0.94 } },
    { message_id: 'M-9004', time: '09:06:31', type: 'negotiate', from: '金融 Agent', to: '团长 Agent', text: '请求补充监管口径来源', payload: { reason: 'source_conflict', need: 2, priority: 'P0' } },
    { message_id: 'M-9005', time: '09:08:12', type: 'heartbeat', from: '数据 Agent', to: '团长 Agent', text: '分析阶段 84%', payload: { progress: 84, current: 'growth_normalization' } },
  ],
  dag: {
    nodes: [
      { id: 'start', label: '任务接收', x: 5, y: 42, state: 'done' },
      { id: 'plan', label: 'C25 规划', x: 24, y: 42, state: 'done' },
      { id: 'research', label: '资料检索', x: 46, y: 12, state: 'done' },
      { id: 'analysis', label: '数据分析', x: 46, y: 42, state: 'running' },
      { id: 'expert', label: '领域分析', x: 46, y: 72, state: 'running' },
      { id: 'merge', label: 'C27 合并', x: 69, y: 42, state: 'waiting' },
      { id: 'approval', label: 'L3 审批', x: 88, y: 42, state: 'waiting' },
    ],
    edges: [
      ['start', 'plan'], ['plan', 'research'], ['plan', 'analysis'], ['plan', 'expert'],
      ['research', 'merge'], ['analysis', 'merge'], ['expert', 'merge'], ['merge', 'approval'],
    ],
  },
  artifacts: [
    { name: 'evidence.json', source: '调研 Agent', size: '82KB', state: 'verified' },
    { name: 'growth-model.csv', source: '数据 Agent', size: '126KB', state: 'review' },
    { name: 'analysis.md', source: '金融 Agent', size: '16KB', state: 'review' },
    { name: 'report-draft.md', source: '团长 Agent', size: '42KB', state: 'generating' },
  ],
  gates: [
    { name: 'Schema 校验', state: 'PASS', detail: '输出符合 report.v2 Schema' },
    { name: '交叉验证', state: 'PASS', detail: '核心结论由 2 个以上来源支持' },
    { name: '置信度门', state: 'PENDING', detail: '当前 91%，目标 ≥ 92%' },
    { name: 'L3 审批', state: 'WAITING', detail: '合并完成后进入审批队列' },
  ],
}
export const experts: ExpertProfile[] = [
  { expert_id: 'E-01', name: '行业研究员', domain: '信息调研', persona: '严谨、来源优先', methodology: '多源检索 → 交叉验证 → 报告', tool_whitelist: ['search', 'retrieval'], output_schema: { type: 'object', required: ['summary', 'evidence', 'confidence'], properties: { summary: 'string', evidence: 'array<source>', confidence: 'number' } }, state: 'active' },
  { expert_id: 'E-02', name: '数据分析师', domain: '数据分析', persona: '冷静的数据洞察者', methodology: '清洗 → 分析 → 可视化 → 结论', tool_whitelist: ['sql', 'chart'], output_schema: { type: 'object', required: ['dataset', 'insights', 'charts'], properties: { dataset: 'string', insights: 'array<string>', charts: 'array<artifact>' } }, state: 'active' },
  { expert_id: 'E-03', name: '金融领域专家', domain: '金融', persona: '风险敏感、结论克制', methodology: '公告 → 财务 → 风险提示', tool_whitelist: ['search', 'financial-api'], output_schema: { type: 'object', required: ['facts', 'risks', 'citations'], properties: { facts: 'array<fact>', risks: 'array<risk>', citations: 'array<source>' } }, state: 'active' },
  { expert_id: 'E-04', name: '文档审查员', domain: '质量审核', persona: '挑错优先', methodology: 'Schema → 事实 → 引用', tool_whitelist: ['diff', 'audit'], output_schema: { type: 'object', required: ['passed', 'issues'], properties: { passed: 'boolean', issues: 'array<issue>' } }, state: 'suspended' },
]

export const skills: SkillItem[] = [
  { skill_id: 'S-101', name: '深度调研', category: 'Research', version: '2.1.0', installs: 128, rating: 4.8, state: 'installed', audit: 'passed', description: '多源搜索、去重、交叉验证和证据链装配。', permissions: ['网络访问', '知识库读取'] },
  { skill_id: 'S-102', name: '财报分析', category: 'Finance', version: '1.6.2', installs: 86, rating: 4.7, state: 'available', audit: 'passed', description: '提取财务指标、生成趋势与风险摘要。', permissions: ['金融 API', '文件解析'] },
  { skill_id: 'S-103', name: '长文写作', category: 'Writing', version: '3.0.1', installs: 203, rating: 4.9, state: 'available', audit: 'passed', description: '结构化大纲、长文生成与引用校对。', permissions: ['知识库读取', '产物写入'] },
  { skill_id: 'S-104', name: '代码执行', category: 'Tooling', version: '1.2.0', installs: 64, rating: 4.5, state: 'available', audit: 'reviewing', description: '在隔离沙箱中运行数据分析代码。', permissions: ['沙箱执行', '文件读写'] },
]

export const connectors: ConnectorItem[] = [
  { connector_id: 'C-01', name: '腾讯文档', protocol: 'MCP', state: 'online', latency: 42, calls: 1260, enabled: true, health_note: '最近 24 小时无失败调用' },
  { connector_id: 'C-02', name: '飞书', protocol: 'MCP', state: 'pending_auth', latency: 0, calls: 0, enabled: false, health_note: '等待 OAuth 授权回调' },
  { connector_id: 'C-03', name: '企业微信', protocol: 'MCP', state: 'offline', latency: 0, calls: 880, enabled: false, health_note: '凭证已过期，需重新授权后重试' },
]

export const automations: AutomationItem[] = [
  { automation_id: 'AUTO-01', auto_name: '科创板行情摘要', cron: '0 9 * * 1-5', action: '检索 + 生成 + 推送', state: 'active', last_run: '今日 09:00', next_run: '明日 09:00', push: ['工作区', '邮件'] },
  { automation_id: 'AUTO-02', auto_name: '周报生成', cron: '0 18 * * 5', action: '汇总 + 生成周报', state: 'active', last_run: '08-07', next_run: '08-14', push: ['工作区'] },
  { automation_id: 'AUTO-03', auto_name: '知识库质量巡检', cron: '0 2 * * *', action: '污染检测 + 自愈', state: 'active', last_run: '今日 02:00', next_run: '明日 02:00', push: ['审批中心'] },
  { automation_id: 'AUTO-04', auto_name: '舆情日报', cron: '0 8 * * *', action: '舆情采集 + 情感分析', state: 'failed', last_run: '08-05 08:00', next_run: '-', push: ['IM'], last_error: '舆情源限流，连续重试 3 次失败' },
  { automation_id: 'AUTO-05', auto_name: '库存预警', cron: '*/30 * * * *', action: '数据查询 + 阈值判断', state: 'paused', last_run: '08-05 10:30', next_run: '-', push: ['IM', '邮件'] },
]

export const cases: CaseItem[] = [
  { case_id: 'CASE-01', title: '行业竞品调研报告', category: 'Research', reuse_count: 86, experts: ['行业研究员', '数据分析师'], skills: ['深度调研'], prompt: '调研目标行业的头部产品，输出能力矩阵、增长趋势、风险与机会，并附来源。', outcome: '结构化竞品报告 + 证据链 + 图表' },
  { case_id: 'CASE-02', title: '上市公司财报摘要', category: 'Finance', reuse_count: 54, experts: ['金融领域专家'], skills: ['财报分析'], prompt: '提取最近四个季度核心财务指标，生成趋势解释与风险提示。', outcome: '财报摘要 + 风险清单 + 数据表' },
  { case_id: 'CASE-03', title: '周度知识库巡检', category: 'Ops', reuse_count: 31, experts: ['文档审查员'], skills: ['长文写作'], prompt: '扫描知识库中的过期、冲突和低引用文档，生成修复建议。', outcome: '巡检报告 + 自愈任务 + 审批请求' },
]

export const approvals: ApprovalItem[] = [
  { approval_id: 'A-3021', action: '删除过期知识文档', risk_level: 'L3', applicant: '质检 Agent', state: 'pending', context: '知识自愈流程计划删除失效文档', target: 'legacy-report.md', impact: '影响 3 个引用关系，删除前将备份', parameters: { mode: 'soft_delete', backup: true }, ttl: '剩余 18 分钟', approved_by: [], required_approvals: 1 },
  { approval_id: 'A-3022', action: '调用外部写接口', risk_level: 'L4', applicant: '连接器-Agent', state: 'pending', context: '向企业微信发送自动化结果卡片', target: '企业微信群 / 运营日报', impact: '外部可见，消息不可撤回', parameters: { connector: '企业微信', message_type: 'card', recipient_count: 12 }, ttl: '剩余 07 分钟', approved_by: ['tenant-admin'], required_approvals: 2 },
  { approval_id: 'A-3019', action: '启用自动化任务', risk_level: 'L2', applicant: '调度器', state: 'approved', context: '启用科创板行情摘要', target: 'AUTO-01', impact: '每日 09:00 触发一次', parameters: { cron: '0 9 * * 1-5' }, ttl: '已处理', approved_by: ['current-user'], required_approvals: 1 },
  { approval_id: 'A-3016', action: '批量导出客户数据', risk_level: 'L3', applicant: '数据 Agent', state: 'expired', context: '导出超过 TTL 未审批', target: 'customer_export.csv', impact: '包含 PII，已自动挂起', parameters: { rows: 12480, masking: true }, ttl: '已超时', approved_by: [], required_approvals: 1 },
]

export const notifications: NotificationItem[] = [
  { id: 'N-01', type: 'approval', title: 'L3 审批待处理', text: '质检 Agent 请求删除过期知识文档', time: '2 分钟前', unread: true, route: '/approvals' },
  { id: 'N-02', type: 'task', title: '任务执行完成', text: '知识库质量巡检已完成', time: '18 分钟前', unread: true, route: '/tasks' },
  { id: 'N-03', type: 'healing', title: '知识自愈完成', text: '金融竞品文档已重新采集并替换', time: '42 分钟前', unread: false, route: '/evolution' },
]

export const searchIndex: SearchItem[] = [
  { type: '任务', title: '金融竞品调研报告', text: 'T-1042 · running · 团长+5专家', route: '/tasks' },
  { type: '知识', title: '架构设计 v3.2', text: '32 组件 · Vue 3 工作平台 · C1-C32', route: '/overview' },
  { type: '技能', title: '深度调研', text: 'Research · v2.1.0 · 已安装', route: '/skills' },
  { type: '案例', title: '行业竞品调研报告', text: '做同款 · 已复用 86 次', route: '/cases' },
  { type: '审批', title: '调用外部写接口', text: 'A-3022 · L4 · 双人复核', route: '/approvals' },
]

export const growthTimeline = [
  { phase: 'P0/P1', title: '胚胎期 / 神经期', desc: 'Monorepo、契约生成、Gateway、Session、NLP、Body、VS1 问答闭环。', status: 'done' },
  { phase: 'P2', title: '感官期', desc: '五感采集、质量检查、渠道降级与感官矩阵。', status: 'next' },
  { phase: 'P3', title: '躯体期', desc: '知识库、混合检索、专家与技能注册。', status: 'planned' },
  { phase: 'M1', title: '大脑 MVP', desc: '问答 MVP、思考链回放和决策审计。', status: 'planned' },
  { phase: 'P6-P8', title: '协作与进化', desc: '多 Agent 编排、自动化治理、自愈与灵感案例。', status: 'planned' },
]

export const teamWorkflow: OverviewWorkflow = {
  domain_id: 'DOM-2048',
  task_title: '金融竞品调研报告',
  progress: 68,
  eta: '约 06:20',
  last_update: '1.8 秒前',
  execution_mode: '多线并行 · fan-out',
  steps: [
    { step_id: 'dispatch', label: '任务接入', state: 'done', progress: 100, owner: '团长', timestamp: '09:02:18' },
    { step_id: 'plan', label: 'C25 规划', state: 'done', progress: 100, owner: '团长', timestamp: '09:02:51' },
    { step_id: 'fanout', label: '专家扇出', state: 'done', progress: 100, owner: 'Planner', timestamp: '09:03:06' },
    { step_id: 'execute', label: '并行执行', state: 'running', progress: 72, owner: '5 Agents', timestamp: '进行中' },
    { step_id: 'merge', label: 'C27 合并', state: 'waiting', progress: 28, owner: 'Merger', timestamp: '等待输入' },
    { step_id: 'verify', label: '交叉验证', state: 'waiting', progress: 12, owner: 'Verifier', timestamp: '等待合并' },
    { step_id: 'approval', label: '审批门', state: 'waiting', progress: 0, owner: '治理层', timestamp: '待触发' },
  ],
  agents: [
    { agent_id: 'AG-01', name: '团长', role: 'Planner / Merger', state: 'running', progress: 72, current_task: 'DAG 规划与结果合并', use_case: '多 Agent 任务编排', model: 'L2-Plan' },
    { agent_id: 'AG-02', name: '调研 Agent', role: 'Researcher', state: 'done', progress: 100, current_task: '12 份资料已回传', use_case: '竞品资料调研', model: 'L2-LLM' },
    { agent_id: 'AG-03', name: '数据 Agent', role: 'Analyst', state: 'running', progress: 84, current_task: '增长数据归一化', use_case: '增长数据分析', model: 'L2-LLM' },
    { agent_id: 'AG-04', name: '金融 Agent', role: 'Domain Expert', state: 'running', progress: 68, current_task: '监管风险与口径校验', use_case: '金融合规审查', model: 'L2-LLM' },
    { agent_id: 'AG-05', name: '审查 Agent', role: 'Verifier', state: 'waiting', progress: 24, current_task: '等待交叉验证输入', use_case: '结果质量验收', model: 'L1-Judge' },
  ],
}

export const modelCallSeries: ModelCallPoint[] = [
  { time: '08:30', calls: 34, tokens: 7.8, latency: 248, cost: 0.42 },
  { time: '08:35', calls: 38, tokens: 8.2, latency: 252, cost: 0.47 },
  { time: '08:40', calls: 36, tokens: 8.8, latency: 264, cost: 0.51 },
  { time: '08:45', calls: 44, tokens: 9.6, latency: 272, cost: 0.58 },
  { time: '08:50', calls: 48, tokens: 10.4, latency: 286, cost: 0.63 },
  { time: '08:55', calls: 42, tokens: 9.9, latency: 276, cost: 0.59 },
  { time: '09:00', calls: 52, tokens: 11.8, latency: 292, cost: 0.72 },
  { time: '09:05', calls: 58, tokens: 12.6, latency: 306, cost: 0.78 },
  { time: '09:10', calls: 54, tokens: 12.1, latency: 298, cost: 0.74 },
  { time: '09:15', calls: 62, tokens: 13.4, latency: 312, cost: 0.84 },
]

export const modelRuntimeNodes: ModelRuntimeNode[] = [
  { node_id: 'router', name: '意图路由', model: 'L0-Rule', state: 'healthy', load: 36, queue: 0, throughput: 62, latency: 12, role: 'Intent Router' },
  { node_id: 'planner', name: '任务规划', model: 'L2-Plan', state: 'busy', load: 74, queue: 3, throughput: 18, latency: 318, role: 'Planner' },
  { node_id: 'retrieval', name: '检索增强', model: 'Qdrant + BM25', state: 'healthy', load: 61, queue: 1, throughput: 27, latency: 286, role: 'RAG Retriever' },
  { node_id: 'analyst', name: '分析推理', model: 'L2-LLM', state: 'busy', load: 82, queue: 5, throughput: 14, latency: 1420, role: 'Reasoner' },
  { node_id: 'generator', name: '内容生成', model: 'L2-LLM', state: 'healthy', load: 57, queue: 2, throughput: 16, latency: 1810, role: 'Generator' },
  { node_id: 'judge', name: '自校验', model: 'L1-Judge', state: 'healthy', load: 42, queue: 0, throughput: 21, latency: 220, role: 'Verifier' },
]

export const optimizationSuggestions: OptimizationSuggestion[] = [
  {
    suggestion_id: 'OPT-2401',
    title: '启用财报分析语义缓存',
    category: '成本优化',
    priority: 'high',
    impact: '预计降低 18% Token 成本、P95 延迟下降 9%',
    confidence: 94,
    effort: '低',
    status: 'pending',
    evidence: '近 7 天存在 1,284 次相似请求，缓存命中潜力 31.8%。',
    action: '会话级语义缓存 0.95',
    source: 'LLM 调用监控 / 成本分析',
    target: '/models',
  },
  {
    suggestion_id: 'OPT-2402',
    title: '分析节点由 L2 切换 L1 预摘要',
    category: '性能优化',
    priority: 'medium',
    impact: '预计队列深度下降 35%，吞吐提升 12%',
    confidence: 88,
    effort: '中',
    status: 'pending',
    evidence: '结构抽取任务占比 42%，不需要完整 L2 推理能力。',
    action: '任务路由策略：structured_extract → L1',
    source: '模型状态拓扑 / 路由审计',
    target: '/models',
  },
  {
    suggestion_id: 'OPT-2403',
    title: '补强监管口径知识片段',
    category: '质量优化',
    priority: 'high',
    impact: '预计事实冲突率下降 21%，归因覆盖率提升至 96%',
    confidence: 91,
    effort: '中',
    status: 'pending',
    evidence: '近 3 次金融 Agent 协商均请求补充监管口径来源。',
    action: '知识缺口采集 + 引用校验',
    source: '协作工作流 / 自校验记录',
    target: '/senses',
  },
  {
    suggestion_id: 'OPT-2404',
    title: '收紧外部写接口审批 TTL',
    category: '安全优化',
    priority: 'medium',
    impact: '高风险动作平均等待时间下降 26%',
    confidence: 86,
    effort: '低',
    status: 'pending',
    evidence: 'L4 审批平均剩余 7 分钟，低风险等待占用明显。',
    action: 'TTL 分级：L3 30m / L4 10m',
    source: '审批时效 / 免疫审计',
    target: '/approvals',
  },
]

const executionStepTemplates: Record<OptimizationSuggestion['category'], string[]> = {
  成本优化: ['评估影响面', '下发缓存策略', '灰度验证命中', '全量生效'],
  性能优化: ['采集基线指标', '切换路由策略', '压测验证', '基线对比报告'],
  质量优化: ['标记知识缺口', '触发补采指令', '引用校验', '质量复评'],
  安全优化: ['解析审批策略', '生成变更工单', '治理复核', '策略生效'],
}

const executionLogTemplates: Record<OptimizationSuggestion['category'], string[]> = {
  成本优化: ['分析近 7 天相似请求聚类，确认缓存收益范围', '语义缓存策略 0.95 已下发至路由层', '灰度 20% 流量，命中率 31.2% 达标', '缓存策略全量生效，成本基线更新'],
  性能优化: ['基线采集完成：P95 / 队列深度 / 吞吐入档', '路由策略 structured_extract → L1 已生效', '压测通过：队列深度下降 36%', '对比报告已生成并归档至知识库'],
  质量优化: ['已定位 3 个监管口径知识缺口', 'R1 补采指令已下发至感官层', '新增片段引用校验通过，来源可溯', '复评通过：事实冲突率回落至阈值内'],
  安全优化: ['审批 TTL 策略解析完成，等待治理确认', '变更工单 GP-2091 已生成', 'L3/L4 双人复核通过', '新 TTL 分级策略已生效，审计已留痕'],
}

export function buildSuggestionExecution(suggestion: OptimizationSuggestion, seq: number): SuggestionExecution {
  const now = () => new Date().toLocaleTimeString('zh-CN', { hour12: false })
  return {
    execution_id: `EXE-${suggestion.suggestion_id}-${String(seq).padStart(3, '0')}`,
    suggestion_id: suggestion.suggestion_id,
    title: suggestion.title,
    action: suggestion.action,
    category: suggestion.category,
    state: 'queued',
    progress: 0,
    steps: executionStepTemplates[suggestion.category].map(name => ({ name, state: 'pending' })),
    logs: [{ time: now(), text: '优化任务已入列，等待执行器调度' }],
    started_at: now(),
    finished_at: null,
    result: null,
  }
}

export { executionLogTemplates }

export const todaySummary = {
  date: '2026-09-12',
  headline: '生命体运行稳定，5 个 Agent 正在并行处理 P0 调研任务，今日知识质量与模型成本均优于基线。',
  running_index: 92,
  metrics: [
    { label: '完成任务', value: 9, unit: '个', trend: '+3 较昨日', tone: 'success' },
    { label: '新增知识', value: 36, unit: '篇', trend: '质检通过 97%', tone: 'primary' },
    { label: 'Token 成本', value: 286, unit: '元', trend: '-12% 较昨日', tone: 'success' },
    { label: '治理处理', value: 4, unit: '项', trend: '1 项待关注', tone: 'warning' },
  ],
  events: [
    { time: '09:08', title: 'P0 调研进入并行执行', detail: '团长已完成 C25 规划，5 条成员泳道同时运行。', type: 'task' },
    { time: '08:42', title: '知识自愈完成', detail: '金融竞品文档重新采集并替换，引用校验通过。', type: 'knowledge' },
    { time: '08:15', title: '模型策略优化生效', detail: '结构化抽取切换 L1 预摘要，P95 延迟下降 9%。', type: 'model' },
  ],
  attention: [
    'L4 外部写接口将在 7 分钟后超时，需要第二人复核。',
    '舆情嗅觉渠道处于 DEGRADED，采集延迟约 4 分钟。',
    '分析推理节点负载 82%，建议关注队列深度。',
  ],
}

export const managedModels: ManagedModel[] = [
  { model_id: 'L0-LOCAL', name: '本地轻量模型', provider: 'Local', tier: 'L0', state: 'active', task_types: ['意图识别', '规则路由'], cost_per_1k: 0, latency_ms: 12, quality: 86, share: 100, quota: '不受限' },
  { model_id: 'EMB-QWEN', name: 'Qwen Embedding', provider: 'Alibaba', tier: 'L1', state: 'active', task_types: ['向量嵌入', '检索路由'], cost_per_1k: 0.08, latency_ms: 86, quality: 91, share: 70, quota: '额度充足' },
  { model_id: 'DEEPSEEK-V3', name: 'DeepSeek V3', provider: 'DeepSeek', tier: 'L2', state: 'active', task_types: ['分析推理', '内容生成'], cost_per_1k: 1.2, latency_ms: 310, quality: 93, share: 70, quota: '已用 64%' },
  { model_id: 'QWEN-MAX', name: 'Qwen-Max', provider: 'Alibaba', tier: 'L2', state: 'standby', task_types: ['复杂推理', '中文长文'], cost_per_1k: 6.5, latency_ms: 780, quality: 96, share: 20, quota: '按量计费' },
  { model_id: 'CLAUDE-SONNET', name: 'Claude Sonnet', provider: 'Anthropic', tier: 'L3', state: 'standby', task_types: ['代码生成', '复杂代理'], cost_per_1k: 4.2, latency_ms: 920, quality: 97, share: 10, quota: '预算内' },
]

export const modelRoutes: ModelRoute[] = [
  { route_id: 'RT-01', task_type: '意图识别', model_id: 'L0-LOCAL', model_name: '本地轻量模型', share: 100, cost: '¥0/1K', note: '零成本本地优先' },
  { route_id: 'RT-02', task_type: '检索路由', model_id: 'EMB-QWEN', model_name: 'Qwen Embedding + DeepSeek', share: 70, cost: '¥0.8/1K', note: '成本优先' },
  { route_id: 'RT-03', task_type: '内容生成', model_id: 'DEEPSEEK-V3', model_name: 'DeepSeek V3', share: 70, cost: '¥1.2/1K', note: '默认主力' },
  { route_id: 'RT-04', task_type: '复杂推理', model_id: 'QWEN-MAX', model_name: 'Qwen-Max / Claude', share: 20, cost: '¥6.5/1K', note: '质量优先' },
  { route_id: 'RT-05', task_type: '代码生成', model_id: 'CLAUDE-SONNET', model_name: 'Claude Sonnet', share: 10, cost: '¥4.2/1K', note: '代码专项' },
]

export const modelTokenTrend = [
  { week: 'W1', tokens: 7.1, cost: 1460, cache: 18 },
  { week: 'W2', tokens: 7.6, cost: 1520, cache: 21 },
  { week: 'W3', tokens: 7.4, cost: 1480, cache: 24 },
  { week: 'W4', tokens: 8.0, cost: 1580, cache: 27 },
  { week: 'W5', tokens: 8.6, cost: 1640, cache: 30 },
  { week: 'W6', tokens: 8.4, cost: 1590, cache: 34 },
]

export const remoteChannels: RemoteChannel[] = [
  { channel_id: 'IM-WX', name: '微信', type: 'wechat', state: 'online', account: 'ClawBot 主通道', capabilities: ['任务下发', '结果回传', '文件接收'], last_message: '2 分钟前' },
  { channel_id: 'IM-WECOM', name: '企业微信', type: 'wecom', state: 'online', account: '运营协同空间', capabilities: ['审批推送', '进度提醒', '任务下发'], last_message: '8 分钟前' },
  { channel_id: 'IM-FS', name: '飞书', type: 'feishu', state: 'available', account: '待绑定应用', capabilities: ['适配器', '群机器人'], last_message: '未启用' },
  { channel_id: 'IM-DT', name: '钉钉', type: 'dingtalk', state: 'available', account: '待绑定应用', capabilities: ['适配器', '审批联动'], last_message: '未启用' },
  { channel_id: 'IM-QQ', name: 'QQ', type: 'qq', state: 'offline', account: '个人移动入口', capabilities: ['任务下发', '文本回传'], last_message: '3 天前' },
]

export const remoteFlow: RemoteFlowEvent[] = [
  { event_id: 'RM-01', time: '09:16:02', direction: '下发', channel: '手机微信', message: '帮我分析今日科创板行情并生成摘要。', status: 'done' },
  { event_id: 'RM-02', time: '09:16:04', direction: '执行', channel: '桌面 Agent', message: '接收任务，唤醒检索与金融 Agent，执行调研。', status: 'running' },
  { event_id: 'RM-03', time: '09:18:41', direction: '回传', channel: '手机微信', message: '已生成摘要，3 份来源，置信度 0.93，附报告。', status: 'done' },
]

export const onlineAgents: OnlineAgent[] = [
  { agent_id: 'AG-01', name: '团长 Agent', role: 'C25 规划 · 扇出', state: 'run', task: 'T-1042', model: 'L2-Plan', latency_ms: 318 },
  { agent_id: 'AG-02', name: '检索专家', role: 'Qdrant + Web', state: 'run', task: 'T-1042', model: 'L1-Embed', latency_ms: 286 },
  { agent_id: 'AG-03', name: '数据专家', role: '分析 · Artifact', state: 'run', task: 'T-1042', model: 'L2-LLM', latency_ms: 1420 },
  { agent_id: 'AG-04', name: '撰写专家', role: 'L2 生成', state: 'wait', task: '待合并', model: 'L2-LLM', latency_ms: 1810 },
  { agent_id: 'AG-05', name: '质检 Agent', role: '交叉验证', state: 'idle', task: '空闲', model: 'L1-Judge', latency_ms: 220 },
]

const middlewareDefaultMetrics: Record<string, Array<{ label: string; value: string }>> = {
  redis: [ { label: '连接数', value: '12' }, { label: '内存', value: '21 MB' }, { label: '命中率', value: '—' } ],
  postgres: [ { label: '活跃连接', value: '3' }, { label: '库大小', value: '212 MB' }, { label: '慢查询', value: '0' } ],
  qdrant: [ { label: '集合', value: '3' }, { label: '向量数', value: '18.2K' }, { label: '检索 P99', value: '—' } ],
  nats: [ { label: '连接', value: '0' }, { label: '主题', value: '11' }, { label: '消息/分', value: '0' } ],
  nacos: [ { label: '注册服务', value: '0' }, { label: '健康实例', value: '0' }, { label: '配置数', value: '6' } ],
  minio: [ { label: '桶', value: '2' }, { label: '对象', value: '348' }, { label: '占用', value: '96 MB' } ],
  jaeger: [ { label: '服务', value: '0' }, { label: 'Span/分', value: '0' }, { label: '保留', value: '72h' } ],
  kafka: [ { label: '主题', value: '5' }, { label: '分区', value: '5' }, { label: 'Lag', value: '0' } ],
}

// 模拟运行时：演示启动流程将 jaeger / kafka 置为未启用，真实状态以 API 模式为准
const middlewareRuntime: MiddlewareOverview = {
  enabled: true,
  checked_at: '21:29:40',
  summary: { total: 8, up: 5, down: 3 },
  items: [
    { key: 'redis', name: 'Redis', role: '会话热存储 · L1 缓存', port: 6379, state: 'up', metrics: [ { label: '连接数', value: '24' }, { label: '内存', value: '38 MB' }, { label: '命中率', value: '97.2%' } ], last_check: '5 秒前' },
    { key: 'postgres', name: 'PostgreSQL', role: '关系库 · 元数据真相源', port: 5432, state: 'up', metrics: [ { label: '活跃连接', value: '9' }, { label: '库大小', value: '212 MB' }, { label: '慢查询', value: '0' } ], last_check: '5 秒前' },
    { key: 'qdrant', name: 'Qdrant', role: '向量库 · 温存储检索', port: 6333, state: 'up', console_url: 'http://127.0.0.1:6333/dashboard', console_label: '控制台', metrics: [ { label: '集合', value: '3' }, { label: '向量数', value: '18.2K' }, { label: '检索 P99', value: '42ms' } ], last_check: '6 秒前' },
    { key: 'nats', name: 'NATS', role: '神经总线 · 请求/回应', port: 4222, state: 'down', metrics: [ { label: '连接', value: '4' }, { label: '主题', value: '11' }, { label: '消息/分', value: '128' } ], last_check: '5 秒前' },
    { key: 'nacos', name: 'Nacos', role: '服务注册与发现', port: 8848, state: 'down', console_url: 'http://127.0.0.1:8848/nacos', console_label: '控制台', metrics: [ { label: '注册服务', value: '4' }, { label: '健康实例', value: '4' }, { label: '配置数', value: '6' } ], last_check: '6 秒前' },
    { key: 'minio', name: 'MinIO', role: '对象存储 · 采集暂存区', port: 9000, state: 'down', console_url: 'http://127.0.0.1:9001', console_label: '控制台', metrics: [ { label: '桶', value: '2' }, { label: '对象', value: '348' }, { label: '占用', value: '96 MB' } ], last_check: '7 秒前' },
    { key: 'jaeger', name: 'Jaeger', role: '分布式链路追踪', port: 16686, state: 'up', console_url: 'http://127.0.0.1:16686', console_label: 'Jaeger UI', metrics: [ { label: '服务', value: '4' }, { label: 'Span/分', value: '86' }, { label: '保留', value: '72h' } ], last_check: '6 秒前' },
    { key: 'kafka', name: 'Kafka', role: '事件流 · 发布订阅', port: 9092, state: 'up', metrics: [ { label: '主题', value: '5' }, { label: '分区', value: '5' }, { label: 'Lag', value: '0' } ], last_check: '7 秒前' },
  ],
}

export function getMiddlewareOverview(): MiddlewareOverview {
  const up = middlewareRuntime.items.filter(item => item.state === 'up').length
  return {
    enabled: middlewareRuntime.enabled,
    checked_at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    summary: { total: middlewareRuntime.items.length, up, down: middlewareRuntime.items.length - up },
    items: JSON.parse(JSON.stringify(middlewareRuntime.items)),
  }
}

export function startMiddlewareMock(key: string): MiddlewareNode | null {
  const item = middlewareRuntime.items.find(entry => entry.key === key)
  if (!item) return null
  if (item.state !== 'down') return JSON.parse(JSON.stringify(item))
  item.state = 'starting'
  item.last_check = '刚刚'
  window.setTimeout(() => {
    item.state = 'up'
    item.metrics = middlewareDefaultMetrics[key] || [{ label: '状态', value: 'OK' }]
    item.last_check = '刚刚'
  }, 4200)
  return JSON.parse(JSON.stringify(item))
}

export function stopMiddlewareMock(key: string): MiddlewareNode | null {
  const item = middlewareRuntime.items.find(entry => entry.key === key)
  if (!item) return null
  if (item.state !== 'up') return JSON.parse(JSON.stringify(item))
  item.state = 'stopping'
  item.last_check = '刚刚'
  window.setTimeout(() => {
    item.state = 'down'
    item.last_check = '刚刚'
  }, 3000)
  return JSON.parse(JSON.stringify(item))
}

export const tracingSeed: TracingOverview = {
  enabled: true,
  ui_url: 'http://127.0.0.1:16686',
  checked_at: '21:29:41',
  services: [
    { name: 'gateway-service', spans_24h: 4821, error_rate: 0.2, p99_ms: 128 },
    { name: 'session-manager', spans_24h: 3960, error_rate: 0.4, p99_ms: 312 },
    { name: 'sense-service', spans_24h: 2140, error_rate: 1.1, p99_ms: 486 },
    { name: 'body-service', spans_24h: 1876, error_rate: 0.1, p99_ms: 95 },
  ],
  recent: [
    { trace_id: '4cf1a9b2e7d83a10', service: 'session-manager', operation: 'POST /api/session/{id}/ask', duration_ms: 875, spans: 6, time: '21:27:12', status: 'ok' },
    { trace_id: '9b30d5e1c4a27f68', service: 'sense-service', operation: 'POST /api/sense/collect', duration_ms: 1204, spans: 8, time: '21:24:48', status: 'ok' },
    { trace_id: '77e2c8a4d1b95c03', service: 'gateway-service', operation: 'POST /api/auth/token', duration_ms: 34, spans: 2, time: '21:22:31', status: 'ok' },
    { trace_id: '1a8f3d6b9c05e247', service: 'session-manager', operation: 'POST /api/session/{id}/ask', duration_ms: 502, spans: 6, time: '21:18:05', status: 'error' },
  ],
}

// ─────────── R-C05(预) 执行视图：四肢层工具演示数据 ───────────
// 字段口径与 tool-executor 真实返回**逐字段对齐**（2026-09-19 实测 /api/tool/list 后校正）：
//   1. 参数名必须是真实的 `expr`（calculator）/ `code`（code）/ `url`（http），
//      写成 `expression`/`source` 会让演示参数照抄到真实服务时被 schema 校验拒绝；
//   2. `registered_at` / `changed_at` 真实为 **epoch 毫秒**，不是格式化字符串；
//   3. `change_log[].action` 实际为大写 `REGISTER`，`impact` 为**数组**（可为空）；
//   4. `metrics.success_rate` 在窗口无样本时为 `null` —— 不能拿 0 冒充「全失败」。
export const executionSeed: ExecutionOverview = {
  available: true,
  tenant_id: 'default',
  checked_at: '21:30:12',
  tool_url: 'http://127.0.0.1:8084',
  total: 3,
  registry_backend: 'in-memory',
  tools: [
    { name: 'calculator', version: '1.0.0', description: '安全的算术计算器：支持 + - * / % ^、括号与 sqrt/abs/min/max/round/pow/log 等函数。', sandbox_required: false, timeout_ms: 3000, whitelist_domains: [], deprecated: false, owner: 'lifeform-core', registered_at: 1789796935302, circuit: { tool_name: 'calculator', state: 'closed', consecutive_failures: 0 } },
    { name: 'code', version: '1.0.0', description: '在沙箱中执行 Python 代码片段，返回 stdout/stderr。危险操作在入沙箱前被拦截。', sandbox_required: true, timeout_ms: 12000, whitelist_domains: [], deprecated: false, owner: 'lifeform-core', registered_at: 1789796935303, circuit: { tool_name: 'code', state: 'closed', consecutive_failures: 0 } },
    { name: 'http', version: '1.0.0', description: '白名单域名 HTTP 抓取（SSRF 防护：域名白名单 + 内网地址拦截 + 响应体截断）。', sandbox_required: false, timeout_ms: 8000, whitelist_domains: ['api.open-meteo.com', 'httpbin.org', 'api.github.com'], deprecated: false, owner: 'lifeform-core', registered_at: 1789796935303, circuit: { tool_name: 'http', state: 'closed', consecutive_failures: 0 } },
  ],
  registry: {
    summary: { tool_count: 3, deprecated_count: 0, change_count: 3, storage: 'in-memory', registry_backend: 'in-memory' },
    change_log: [
      { tool_name: 'calculator', version: '1.0.0', schema_hash: '97836bb2787ca2b0', action: 'REGISTER', previous_version: null, changed_at: 1789796935303, changed_by: 'lifeform-core', impact: [] },
      { tool_name: 'code', version: '1.0.0', schema_hash: '335bafd3d28c861a', action: 'REGISTER', previous_version: null, changed_at: 1789796935303, changed_by: 'lifeform-core', impact: [] },
      { tool_name: 'http', version: '1.0.0', schema_hash: '900f82a69608fde8', action: 'REGISTER', previous_version: null, changed_at: 1789796935303, changed_by: 'lifeform-core', impact: [] },
    ],
    semver_policy: '工具接口 semver 版本化；schema 变更即触发 L1 契约测试（IN-06）',
    deprecation_policy: '废弃期 30 天，期间仍可调用并返回 deprecated=true',
  },
  metrics: {
    metrics: { window_size: 24, total_calls: 41, total_failures: 5, blocked_calls: 3, success_rate: 0.875, p50_ms: 6, p95_ms: 38, p99_ms: 62, calls_by_tool: { calculator: 22, http: 13, code: 6 } },
    circuit_breakers: [
      { tool_name: 'calculator', state: 'closed', consecutive_failures: 0 },
      { tool_name: 'code', state: 'closed', consecutive_failures: 0 },
      { tool_name: 'http', state: 'closed', consecutive_failures: 1 },
    ],
  },
  sandbox: {
    enabled: true, active_backend: 'process-restricted', isolated: false, degraded: true,
    docker_available: false, process_fallback_available: true, timeout_ms: 10000, memory_mb: 256,
    note: '未检测到可用沙箱镜像 —— 已降级为受限子进程（非隔离边界），仅静态预检 + 硬超时',
  },
  audit: {
    total: 5, audit_backend: 'postgres', degraded: false,
    items: [
      { audit_id: 'a-1041', call_id: 'call-7f3a91c2', tool_name: 'calculator', args: '{"expr":"(128*1.07+36)/2"}', success: true, output: '{"result":86.48}', latency_ms: 4, sandboxed: false, created_at: 1758285600000, requested_by: 'work-platform' },
      { audit_id: 'a-1040', call_id: 'call-2b8e40d5', tool_name: 'http', args: '{"url":"https://api.open-meteo.com/v1/forecast?latitude=31.2&longitude=121.5"}', success: true, output: '{"status":200,"bytes":842}', latency_ms: 61, sandboxed: false, created_at: 1758285480000, requested_by: 'work-platform' },
      { audit_id: 'a-1039', call_id: 'call-9c1d77ab', tool_name: 'code', args: '{"code":"import os; os.system(\'rm -rf /\')"}', success: false, error_code: 'AGENT_TOOL_ARGS_BLOCKED', error_message: '参数命中危险模式：os.system', latency_ms: 1, sandboxed: false, created_at: 1758285300000, requested_by: 'work-platform' },
      { audit_id: 'a-1038', call_id: 'call-4e6b2f90', tool_name: 'http', args: '{"url":"https://evil.example.net/collect"}', success: false, error_code: 'AGENT_TOOL_ARGS_BLOCKED', error_message: '目标域不在白名单内', latency_ms: 2, sandboxed: false, created_at: 1758285120000, requested_by: 'work-platform' },
      { audit_id: 'a-1037', call_id: 'call-1a5c83de', tool_name: 'code', args: '{"code":"print(sum(range(1000)))"}', success: true, output: '499500', latency_ms: 318, sandboxed: true, sandbox_backend: 'docker', created_at: 1758284900000, requested_by: 'work-platform' },
    ],
  },
  audit_stats: {
    audit: { backend: 'postgres', degraded: false, total: 41, success: 36, blocked: 3, sandboxed: 6, success_rate: 0.878 },
    kafka: { available: true, topic: 'lifeform.tool.invoked', published: 41 },
  },
  partial: false,
  partial_reasons: [],
  note: '演示数据（VITE_DATA_SOURCE=mock）：工具调用记录与沙箱状态均非真实执行结果',
  data_source: 'mock',
}

/**
 * 演示执行：不真跑工具，返回带 mock 标注的结果。
 * 危险参数按真实守卫口径拒绝 —— 演示也能看到「拦截」这条路径，而不是只有成功路径。
 * 参数键与真实 schema 一致（code / expr / url）。
 */
const MOCK_DANGEROUS = /(os\.system|subprocess\.(popen|call|run)|rm\s+-rf|Runtime\.getRuntime\(\)\.exec)/i
export function executeToolMock(toolName: string, args: Record<string, unknown>): ToolExecutionResult {
  mockCallSeq += 1
  const callId = `call-mock-${mockCallSeq}`
  const source = String(args.code ?? args.expr ?? args.url ?? '')
  if (MOCK_DANGEROUS.test(source)) {
    return {
      available: true, tool_name: toolName, success: false,
      error_code: 'AGENT_TOOL_ARGS_BLOCKED', error_message: '参数命中危险模式（演示判定，与真实守卫同口径）',
      call_id: callId, audit_id: callId, latency_ms: 1, bff_latency_ms: 2,
      checked_at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      data_source: 'mock', reason: '演示数据源：本次未真实执行工具',
    }
  }
  return {
    available: true, tool_name: toolName, success: true,
    output: { demo: true, input: args, note: '演示数据源未真实执行，切换 VITE_DATA_SOURCE=api 后走 tool-executor' },
    call_id: callId, audit_id: callId, latency_ms: 5, bff_latency_ms: 7, sandboxed: toolName === 'code',
    sandbox_backend: toolName === 'code' ? 'process-restricted' : undefined,
    checked_at: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    data_source: 'mock', reason: '演示数据源：本次未真实执行工具',
  }
}
let mockCallSeq = 0

/**
 * IN-06 影响分析演示数据（BFF `GET /tools/{name}/impact`）。
 *
 * 与真实实现同口径：**消费方来自注册表显式登记**（`ToolBootstrap.registerConsumer`），
 * 因此是"登记过的"，不是猜出来的；`note` 必须一起展示，避免把"没登记"读成"安全"。
 */
const MOCK_TOOL_CONSUMERS: Record<string, { agents: string[]; flows: string[] }> = {
  calculator: { agents: ['brain-planner'], flows: ['combo-task'] },
  http: { agents: ['brain-planner'], flows: ['combo-task'] },
  code: { agents: ['code-assist'], flows: ['code-assist'] },
}
export function toolImpactMock(toolName: string): ToolImpactReport {
  const consumers = MOCK_TOOL_CONSUMERS[toolName] || { agents: [], flows: [] }
  const known = Boolean(MOCK_TOOL_CONSUMERS[toolName])
  return {
    tool_name: toolName,
    current_version: known ? '1.0.0' : null,
    schema_hash: executionSeed.registry?.change_log.find((item) => item.tool_name === toolName)?.schema_hash,
    affected_agents: consumers.agents,
    affected_flows: consumers.flows,
    schema_changed: false,
    contract_test_required: false,
    note: known
      ? '以上为注册表登记的消费方，变更后需回归其契约测试'
      : '工具未登记消费方 —— 影响分析只覆盖**显式登记过**的 Agent/流程；硬编码调用方不会出现在此列表中',
    data_source: 'mock',
  }
}

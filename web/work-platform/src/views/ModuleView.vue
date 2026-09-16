<template>
  <div class="page module-page">
    <div class="hero-command compact-hero">
      <div class="hero-kicker">{{ module.group }} / {{ module.phase }}</div>
      <h2 class="hero-title">{{ module.title }}</h2>
      <p class="hero-desc">{{ module.description }} · {{ module.status === 'ready' ? '真实数据就绪' : '阶段性展示' }}</p>
    </div>
    <el-alert
      v-if="module.status === 'prototype'"
      title="当前为 Vue 3 + Element Plus 阶段性展示；Mock/API 结构一致，随对应 Phase 接入真实数据后零 UI 改动切换。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="moduleDegraded"
      :title="`API 模式下该模块尚未接入真实端点，当前展示 Mock 数据（${moduleDegraded.reason}）。补齐 BFF 端点后自动切换。`"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-alert v-if="loadError" title="模块数据加载失败，已保留最近快照" type="error" :closable="false" show-icon>
      <template #default><el-button size="small" text type="primary" @click="loadWorkbench">重试</el-button></template>
    </el-alert>
    <el-skeleton v-if="loading" :rows="7" animated />

    <template v-else>
      <!-- 生命体征 -->
      <template v-if="moduleId === 'vitals'">
        <div class="status-ribbon">
          <span class="pulse-dot" :class="{ offline: !vitalsOnline }" />
          <strong>{{ vitalsOnline ? '生命体征实时流已连接' : '实时流断开，当前为最近快照' }}</strong>
          <span>WS wp.vitals.update</span>
          <el-button v-if="!vitalsOnline" size="small" text type="primary" @click="reconnectVitals">重新连接</el-button>
        </div>
        <div class="metric-grid vitals-grid">
          <el-card v-for="item in vitalData" :key="item.key" class="section-card metric-card vital-card" :class="item.status" shadow="never">
            <div class="metric-label">{{ item.label }}</div>
            <div class="metric-value" :class="{ heartbeat: item.key === 'heart_rate' }">{{ item.value }}<span class="metric-unit">{{ item.unit }}</span></div>
            <div class="metric-trend">{{ item.trend }} · {{ item.threshold }}</div>
          </el-card>
        </div>
        <el-card class="section-card" shadow="never">
          <template #header><strong>D2 器官健康报告</strong><el-button text type="primary" class="header-action" @click="exportOrganReport">导出 PDF</el-button></template>
          <el-table :data="organData" stripe>
            <el-table-column prop="organ" label="器官" width="100" />
            <el-table-column label="评分" width="100"><template #default="{ row }">{{ row.score ?? '--' }}</template></el-table-column>
            <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="organTagType(row)">{{ row.state }}</el-tag></template></el-table-column>
            <el-table-column prop="note" label="说明" />
          </el-table>
        </el-card>
      </template>

      <!-- 决策沙盘 -->
      <template v-else-if="moduleId === 'brain'">
        <div v-if="brainData.chain.length" class="metric-grid brain-metrics">
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">决策置信度</div><div class="metric-value">{{ brainData.decision.confidence }}<span class="metric-unit">%</span></div><div class="metric-trend">阈值 ≥ 90%</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">合规审计</div><div class="metric-value">{{ brainData.decision.audit_state }}</div><div class="metric-trend">AuditLog 可回放</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">回放延迟</div><div class="metric-value">{{ brainData.decision.replay_latency }}<span class="metric-unit">ms</span></div><div class="metric-trend">SLO < 500ms</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">归因来源</div><div class="metric-value">{{ brainData.decision.sources.length }}<span class="metric-unit">条</span></div><div class="metric-trend">全部可追溯</div></el-card>
        </div>
        <el-card v-if="brainData.chain.length" class="section-card" shadow="never">
          <template #header><strong>D5 思考链回放</strong><span class="header-meta">decision_id: {{ brainData.decision.decision_id }} · task {{ brainData.decision.task_id }}</span></template>
          <el-collapse>
            <el-collapse-item v-for="step in brainData.chain" :key="step.step" :title="`${step.step} · ${step.model} · ${step.latency} · 置信度 ${Math.round(step.confidence * 100)}%`">
              <pre class="detail-pre">{{ step.io }}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-card>
        <div v-if="brainData.chain.length" class="two-column">
          <el-card class="section-card" shadow="never">
            <template #header><strong>归因溯源</strong></template>
            <el-table :data="brainData.decision.sources" size="small">
              <el-table-column prop="title" label="来源" min-width="180" />
              <el-table-column prop="chunk" label="片段" width="110" />
              <el-table-column prop="score" label="相关度" width="90" />
            </el-table>
          </el-card>
          <el-card class="section-card" shadow="never">
            <template #header><strong>合规审计摘要</strong></template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="可见范围">仅本人 / 管理员</el-descriptions-item>
              <el-descriptions-item label="审计状态">PASS</el-descriptions-item>
              <el-descriptions-item label="数据保留">365 天</el-descriptions-item>
              <el-descriptions-item label="敏感字段">已脱敏</el-descriptions-item>
            </el-descriptions>
          </el-card>
        </div>
        <el-empty v-else description="尚无思考记录"><el-button type="primary">发起首次问答</el-button></el-empty>
      </template>

      <!-- 五感矩阵 -->
      <template v-else-if="moduleId === 'senses'">
        <el-alert v-if="degradedSenses.length" :title="`${degradedSenses.length} 个感官通道降级，请检查采集源`" type="warning" :closable="false" show-icon />
        <div class="sense-grid">
          <el-card v-for="item in senseData" :key="item.channel_id" class="section-card sense-card" :class="item.state.toLowerCase()" shadow="never" @click="openSenseDetail(item)">
            <div class="sense-top"><el-icon size="24"><component :is="iconMap[item.icon]" /></el-icon><el-tag :type="senseTagType(item.state)">{{ item.state }}</el-tag></div>
            <h3>{{ item.name }}</h3>
            <div class="sense-value">{{ item.count }}<span>条</span></div>
            <el-progress :percentage="item.quality" :status="item.quality >= 90 ? 'success' : 'warning'" />
            <div class="metric-trend">{{ item.recent }}</div>
            <div class="sense-foot">{{ item.source }} · {{ item.last_sync }}</div>
          </el-card>
        </div>
      </template>

      <!-- 进化视图 -->
      <template v-else-if="moduleId === 'evolution'">
        <div class="metric-grid">
          <el-card v-for="item in evolutionData.metrics" :key="item.label" class="section-card metric-card" shadow="never">
            <div class="metric-label">{{ item.label }}</div><div class="metric-value">{{ item.value }}<span class="metric-unit">{{ item.unit }}</span></div>
          </el-card>
        </div>
        <el-card class="section-card" shadow="never">
          <template #header><strong>R-C07 周粒度反馈趋势</strong><span class="header-meta">满意度柱高 / 幻觉率标注</span></template>
          <div v-if="evolutionData.trend.length" class="trend-chart">
            <div v-for="item in evolutionData.trend" :key="item.week" class="trend-column">
              <span class="trend-value">{{ item.satisfaction }}</span>
              <div class="trend-track"><div class="trend-bar" :style="{ height: `${item.satisfaction}%` }" /></div>
              <strong>{{ item.week }}</strong>
              <small>幻觉 {{ item.hallucination }}%</small>
            </div>
          </div>
          <el-empty v-else description="暂无趋势数据" />
        </el-card>
        <el-card class="section-card" shadow="never">
          <template #header><strong>知识自愈记录（D4）</strong><span class="header-meta">点击查看清除详情</span></template>
          <el-table :data="evolutionData.healings" stripe @row-click="openHealingDetail">
            <el-table-column prop="healing_id" label="记录 ID" width="100" />
            <el-table-column prop="doc" label="文档" min-width="220" />
            <el-table-column prop="issue" label="问题" width="140" />
            <el-table-column prop="action" label="清除方式" min-width="180" />
            <el-table-column prop="recurrence" label="是否复发" width="100" />
            <el-table-column prop="created_at" label="时间" width="110" />
          </el-table>
        </el-card>
      </template>

      <!-- 协作总线 -->
      <template v-else-if="moduleId === 'collab'">
        <div class="collab-kpi-grid">
          <div class="collab-kpi"><span>协作域</span><strong>{{ collaborationData.domain_id }}</strong><small>任务 {{ collaborationData.task_id }}</small></div>
          <div class="collab-kpi"><span>并发负载</span><strong>{{ collaborationData.concurrency_current }}/{{ collaborationData.concurrency_limit }}</strong><small>当前 Agent 并发数</small></div>
          <div class="collab-kpi"><span>总线 P99</span><strong>{{ collaborationData.p99_ms }}ms</strong><small>目标 &lt; 10ms</small></div>
          <div class="collab-kpi"><span>消息确认率</span><strong>{{ collaborationData.ack_rate }}%</strong><small>{{ collaborationData.messages_per_sec }} msg/s</small></div>
        </div>

        <el-card class="section-card collaboration-mode-card" shadow="never">
          <template #header><strong>协作模式模块</strong><span class="header-meta">MC-02 · 三模式可切换</span></template>
          <div class="collaboration-mode-grid">
            <button v-for="(mode, index) in busModes" :key="mode.id" type="button" class="collaboration-mode-item" :class="{ active: busMode === mode.id }" @click="busMode = mode.id">
              <div class="mode-item-head">
                <span class="mode-number">0{{ index + 1 }}</span>
                <span class="mode-title"><strong>{{ mode.label }}</strong><small>{{ mode.english }}</small></span>
                <el-tag v-if="busMode === mode.id" size="small" type="success">当前模式</el-tag>
              </div>
              <p>{{ mode.description }}</p>
              <div class="mode-use-case"><span>适用</span>{{ mode.use_case }}</div>
              <div class="mode-mini-topology" :class="mode.id">
                <i v-for="node in mode.nodes" :key="node">{{ node }}</i>
              </div>
            </button>
          </div>
          <div class="active-mode-bar">
            <span>当前执行模式</span><strong>{{ activeMode.label }}</strong><em>{{ activeMode.description }}</em><el-tag effect="plain">{{ collaborationData.protocol }}</el-tag>
          </div>
        </el-card>
        <el-card class="section-card bus-topology-card" shadow="never">
          <template #header>
            <div class="card-title-row"><strong>多 Agent 协作总线</strong><span class="live-badge"><i /> BUS LIVE</span></div>
            <span class="header-meta">{{ collaborationData.protocol }} · {{ collaborationData.updated_at }}</span>
          </template>
          <div class="bus-topbar">
            <div class="bus-caption">
              <strong>生命群落并发拓扑</strong>
              <span>{{ collaborationData.mode }} · 团长拆解 → 总线扇出 → 成员并行 → 工件合并</span>
            </div>
            <el-tag class="bus-active-mode" effect="plain">当前：{{ activeModeLabel }}</el-tag>
          </div>
          <div class="agent-bus">
            <div v-for="agent in collaborationData.agents" :key="agent.agent_id" class="agent-bus-row" :class="[agent.state, { leader: agent.is_leader }]">
              <button class="bus-agent-node" :class="{ leader: agent.is_leader, worker: !agent.is_leader }" type="button" @click="openDetail(`${agent.name} 协作详情`, agent)">
                <span class="bus-agent-avatar">{{ agent.name.slice(0, 1) }}</span>
                <span class="bus-agent-copy"><strong>{{ agent.name }}</strong><small>{{ agent.role }} · {{ agent.use_case }}</small></span>
                <el-tag size="small" :type="agentTagType(agent.state)">{{ agentStateLabel(agent.state) }}</el-tag>
              </button>
              <div class="bus-track">
                <span class="bus-line-base" />
                <span class="bus-line-live" :class="agent.bus_kind" :style="{ width: `${agent.progress}%` }" />
                <span class="bus-packet" :style="{ left: `${agent.bus_position}%` }" />
                <button class="bus-message-pill" :class="agent.bus_kind" type="button" :style="{ left: `${agent.bus_position}%` }" @click="openProtocol({ message_id: agent.agent_id, type: agent.bus_kind, payload: agent })">
                  {{ agent.bus_message }}
                </button>
              </div>
              <button class="bus-agent-result" type="button" @click="openDetail(`${agent.name} 结果摘要`, agent)">
                <strong>{{ agent.artifact_count }} 工件</strong>
                <span>{{ confidenceText(agent) }}</span>
                <small>{{ agent.waiting_for || agent.current_task }}</small>
              </button>
            </div>
          </div>
          <div class="bus-status-bar">
            <span><i class="status-dot" /> 总线健康</span>
            <span>扇出全收 5/5</span>
            <span>重复消息 0</span>
            <span>心跳超时 0</span>
            <span class="gold">MC-01~06 协议链路在线</span>
          </div>
        </el-card>

        <div class="two-column collab-mid">
          <el-card class="section-card dag-card" shadow="never">
            <template #header><strong>并发 DAG 编排</strong><span class="header-meta">C25 规划 → 并行执行 → C27 合并</span></template>
            <div class="dag-canvas compact-dag">
              <svg viewBox="0 0 100 100" preserveAspectRatio="none" class="dag-lines">
                <line v-for="edge in collaborationData.dag.edges" :key="edge.join('-')" :x1="edgeStart(edge).x" :y1="edgeStart(edge).y" :x2="edgeEnd(edge).x" :y2="edgeEnd(edge).y" />
              </svg>
              <button v-for="node in collaborationData.dag.nodes" :key="node.id" class="dag-node" :class="node.state" :style="{ left: `${node.x}%`, top: `${node.y}%` }" type="button" @click="openDagNode(node)">{{ node.label }}</button>
            </div>
          </el-card>

          <el-card class="section-card message-stream-card" shadow="never">
            <template #header><strong>MC-P 实时消息流</strong><span class="header-meta">点击消息查看协议体</span></template>
            <div class="message-filter">
              <button v-for="item in messageFilters" :key="item.id" type="button" :class="{ active: messageFilter === item.id }" @click="messageFilter = item.id">{{ item.label }}</button>
            </div>
            <div class="message-pill-flow">
              <button v-for="message in filteredCollabMessages" :key="message.message_id" class="bus-message-card" :class="message.type" type="button" @click="openProtocol(message)">
                <span class="message-time">{{ message.time }}</span>
                <strong>{{ message.type }}</strong>
                <span class="message-route">{{ message.from }} → {{ message.to }}</span>
                <p>{{ message.text }}</p>
              </button>
            </div>
          </el-card>
        </div>

        <div class="two-column collab-bottom-stack">
          <el-card class="section-card" shadow="never">
            <template #header><strong>共享工件层</strong><span class="header-meta">{{ collaborationData.artifacts.length }} 个工件</span></template>
            <el-table :data="collaborationData.artifacts" size="small">
              <el-table-column prop="name" label="工件" />
              <el-table-column prop="source" label="来源" />
              <el-table-column prop="size" label="大小" width="80" />
              <el-table-column prop="state" label="状态" width="90" />
            </el-table>
          </el-card>
          <el-card class="section-card" shadow="never">
            <template #header><strong>协作验收门</strong><span class="header-meta">4 重校验</span></template>
            <div class="gate-grid compact-gates">
              <button v-for="gate in collaborationData.gates" :key="gate.name" class="gate-card" :class="gate.state.toLowerCase()" type="button" @click="openDetail(gate.name, gate)">
                <strong>{{ gate.name }}</strong><span>{{ gate.state }}</span><small>{{ gate.detail }}</small>
              </button>
            </div>
          </el-card>
        </div>
      </template>
      <!-- 专家团队 -->
      <template v-else-if="moduleId === 'experts'">
        <div class="filter-row">
          <el-radio-group v-model="expertStateFilter">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="active">可用</el-radio-button>
            <el-radio-button value="suspended">已暂停</el-radio-button>
          </el-radio-group>
          <span class="header-meta">{{ filteredExperts.length }} 位专家</span>
        </div>
        <div v-if="filteredExperts.length" class="card-grid">
          <el-card v-for="expert in filteredExperts" :key="expert.expert_id" class="section-card expert-card" shadow="never">
            <div class="card-heading"><strong>{{ expert.name }}</strong><el-tag :type="expertTagType(expert.state)">{{ expertStateLabel(expert.state) }}</el-tag></div>
            <p>{{ expert.domain }} · {{ expert.persona }}</p>
            <div class="tag-line"><el-tag v-for="tool in expert.tool_whitelist" :key="tool" size="small" type="info">{{ tool }}</el-tag></div>
            <div class="methodology">{{ expert.methodology }}</div>
            <el-button text type="primary" @click="openExpertSchema(expert)">查看输出 Schema</el-button>
          </el-card>
        </div>
        <el-empty v-else description="当前筛选下没有专家" />
      </template>

      <!-- 技能市场 -->
      <template v-else-if="moduleId === 'skills'">
        <div class="filter-row">
          <el-radio-group v-model="skillCategory">
            <el-radio-button v-for="category in skillCategories" :key="category" :value="category">{{ category === 'all' ? '全部' : category }}</el-radio-button>
          </el-radio-group>
          <el-input v-model="skillKeyword" placeholder="搜索技能" clearable class="mini-search" />
        </div>
        <div v-if="filteredSkills.length" class="card-grid">
          <el-card v-for="skill in filteredSkills" :key="skill.skill_id" class="section-card skill-card" shadow="never">
            <div class="card-heading"><strong>{{ skill.name }}</strong><el-tag>{{ skill.category }}</el-tag></div>
            <p class="skill-description">{{ skill.description }}</p>
            <div class="skill-meta">v{{ skill.version }} · 安装 {{ skill.installs }} · 评分 {{ skill.rating }}</div>
            <div class="tag-line">
              <el-tag size="small" :type="skill.audit === 'passed' ? 'success' : 'warning'">安全审核 {{ skill.audit }}</el-tag>
              <el-tag v-for="permission in skill.permissions" :key="permission" size="small" type="info">{{ permission }}</el-tag>
            </div>
            <el-button class="card-action" type="primary" :loading="skill.state === 'installing'" :disabled="skill.state === 'installed'" @click="installSkill(skill)">{{ skill.state === 'installed' ? '已安装' : skill.state === 'installing' ? '安装中' : '一键安装' }}</el-button>
          </el-card>
        </div>
        <el-empty v-else description="技能市场暂无匹配结果" />
      </template>

      <!-- 连接器 -->
      <template v-else-if="moduleId === 'connectors'">
        <el-card class="section-card" shadow="never">
          <template #header><strong>WB-06 MCP 连接器网关</strong><span class="header-meta">OAuth 授权 / 健康监控 / 启停</span></template>
          <el-table :data="connectorData" stripe>
            <el-table-column prop="name" label="连接器" min-width="130" />
            <el-table-column prop="protocol" label="协议" width="90" />
            <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="connectorTagType(row.state)">{{ connectorStateLabel(row.state) }}</el-tag></template></el-table-column>
            <el-table-column prop="latency" label="延迟(ms)" width="100" />
            <el-table-column prop="calls" label="调用量" width="100" />
            <el-table-column label="健康说明" min-width="220"><template #default="{ row }">{{ row.health_note }}</template></el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.state !== 'online'" size="small" type="primary" @click="openConnectorAuth(row)">授权</el-button>
                <el-switch v-else v-model="row.enabled" active-text="启用" @change="toggleConnector(row)" />
                <el-button size="small" text type="primary" @click="openDetail(`${row.name} 健康详情`, row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>

      <!-- 自动化 -->
      <template v-else-if="moduleId === 'automation'">
        <el-alert v-if="failedAutomations.length" :title="`${failedAutomations.length} 个自动化任务执行失败，可重试或查看错误`" type="error" :closable="false" show-icon />
        <el-card class="section-card" shadow="never">
          <template #header><strong>WB-07 定时任务</strong><el-button type="primary" class="header-action" @click="openAutomationForm">新建自动化</el-button></template>
          <el-table :data="automationData" stripe>
            <el-table-column prop="auto_name" label="任务" min-width="180" />
            <el-table-column prop="cron" label="Cron" width="140" />
            <el-table-column prop="action" label="动作" min-width="180" />
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="automationTagType(row.state)">{{ automationStateLabel(row.state) }}</el-tag></template></el-table-column>
            <el-table-column prop="next_run" label="下次运行" width="110" />
            <el-table-column label="推送渠道" width="150"><template #default="{ row }"><el-tag v-for="channel in row.push" :key="channel" size="small" class="inline-tag">{{ channel }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="{ row }">
                <el-switch v-if="row.state !== 'failed'" v-model="row.state" active-value="active" inactive-value="paused" @change="toggleAutomation(row)" />
                <el-button v-if="row.state === 'failed'" size="small" type="danger" plain @click="retryAutomation(row)">重试</el-button>
                <el-button size="small" text type="primary" @click="openDetail(row.auto_name, row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>

      <!-- 多模型管理 -->
      <template v-else-if="moduleId === 'models'">
        <div class="metric-grid model-kpi-grid">
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">本月 Token 消耗</div><div class="metric-value">8.42<span class="metric-unit">M</span></div><div class="metric-trend">较上月 -12% · 预算内</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">路由成本节省</div><div class="metric-value">¥6,180</div><div class="metric-trend">70/20/10 分流</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">模型健康度</div><div class="metric-value">99.2<span class="metric-unit">%</span></div><div class="metric-trend">无漂移告警</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">活跃模型</div><div class="metric-value">{{ activeModelCount }}<span class="metric-unit">/ {{ modelData.length }}</span></div><div class="metric-trend">多源路由在线</div></el-card>
        </div>
        <el-card class="section-card" shadow="never">
          <template #header><strong>模型池与运行状态</strong><span class="header-meta">按成本 / 质量 / 延迟动态路由</span></template>
          <div class="managed-model-grid">
            <article v-for="model in modelData" :key="model.model_id" class="managed-model-card" :class="model.state">
              <div class="model-card-head"><div><span class="model-tier">{{ model.tier }}</span><strong>{{ model.name }}</strong><small>{{ model.provider }} · {{ model.model_id }}</small></div><el-tag :type="modelStateType(model.state)">{{ modelStateLabel(model.state) }}</el-tag></div>
              <div class="model-card-stats"><span>成本<strong>{{ model.cost_per_1k ? `¥${model.cost_per_1k}/1K` : '免费' }}</strong></span><span>延迟<strong>{{ model.latency_ms }}ms</strong></span><span>质量<strong>{{ model.quality }}</strong></span><span>流量<strong>{{ model.share }}%</strong></span></div>
              <div class="tag-line"><el-tag v-for="task in model.task_types" :key="task" size="small" type="info">{{ task }}</el-tag></div>
              <div class="model-card-foot"><small>{{ model.quota }}</small><el-button v-if="model.state !== 'active'" size="small" type="primary" @click="activateModel(model)">设为活跃</el-button><el-tag v-else type="success" effect="plain">当前活跃</el-tag></div>
            </article>
          </div>
        </el-card>
        <div class="two-column model-route-layout">
          <el-card class="section-card" shadow="never">
            <template #header><strong>模型路由策略</strong><span class="header-meta">70/20/10 分流</span></template>
            <el-table :data="modelRouteData" size="small">
              <el-table-column prop="task_type" label="任务类型" min-width="130" />
              <el-table-column prop="model_name" label="模型" min-width="180" />
              <el-table-column label="流量" width="100"><template #default="{ row }"><el-progress :percentage="row.share" :show-text="false" /><small>{{ row.share }}%</small></template></el-table-column>
              <el-table-column prop="cost" label="成本" width="100" />
              <el-table-column prop="note" label="策略" width="110" />
            </el-table>
          </el-card>
          <el-card class="section-card" shadow="never">
            <template #header><strong>Token 用量趋势</strong><span class="header-meta">近 6 周 · 缓存命中持续提升</span></template>
            <div class="token-trend">
              <div v-for="item in modelTrendData" :key="item.week" class="token-column">
                <span>{{ item.tokens }}M</span><div class="token-bar"><i :style="{ height: `${item.tokens / 10 * 100}%` }" /></div><strong>{{ item.week }}</strong><small>缓存 {{ item.cache }}%</small>
              </div>
            </div>
          </el-card>
        </div>
      </template>

      <!-- 远程 IM 遥控 -->
      <template v-else-if="moduleId === 'remote'">
        <div class="metric-grid remote-kpi-grid">
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">在线渠道</div><div class="metric-value">{{ onlineRemoteChannels }}<span class="metric-unit">/ {{ remoteChannelData.length }}</span></div><div class="metric-trend">微信 / 企业微信在线</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">今日遥控消息</div><div class="metric-value">38<span class="metric-unit">条</span></div><div class="metric-trend">下发 12 · 回传 18</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">平均回传时延</div><div class="metric-value">2.4<span class="metric-unit">s</span></div><div class="metric-trend">P95 5.8s</div></el-card>
          <el-card class="section-card metric-card" shadow="never"><div class="metric-label">待审批推送</div><div class="metric-value">2<span class="metric-unit">项</span></div><div class="metric-trend">企业微信联动</div></el-card>
        </div>
        <el-card class="section-card" shadow="never">
          <template #header><strong>IM 渠道适配器</strong><span class="header-meta">手机遥控桌面 Agent</span></template>
          <div class="remote-channel-grid">
            <article v-for="channel in remoteChannelData" :key="channel.channel_id" class="remote-channel-card" :class="channel.state">
              <div class="channel-head"><span class="channel-icon">{{ channel.name.slice(0, 1) }}</span><div><strong>{{ channel.name }}</strong><small>{{ channel.account }}</small></div><el-tag :type="channelStateType(channel.state)">{{ channelStateLabel(channel.state) }}</el-tag></div>
              <div class="tag-line"><el-tag v-for="cap in channel.capabilities" :key="cap" size="small" type="info">{{ cap }}</el-tag></div>
              <small class="channel-last">最近消息：{{ channel.last_message }}</small>
            </article>
          </div>
        </el-card>
        <el-card class="section-card remote-console-card" shadow="never">
          <template #header><strong>遥控任务流</strong><span class="header-meta">下发 → 执行 → 结果回传</span></template>
          <div class="remote-console">
            <div class="remote-flow">
              <div v-for="(event, index) in remoteFlowData" :key="event.event_id" class="remote-flow-item" :class="event.direction">
                <span class="remote-index">{{ index + 1 }}</span>
                <div class="remote-flow-card"><div><el-tag size="small">{{ event.direction }}</el-tag><strong>{{ event.channel }}</strong><span>{{ event.time }}</span></div><p>{{ event.message }}</p></div>
              </div>
            </div>
            <div class="remote-command-box">
              <span class="remote-kicker">REMOTE COMMAND</span>
              <h3>从手机或 IM 下发新任务</h3>
              <el-input v-model="remoteCommand" type="textarea" :rows="4" placeholder="例如：分析今日科创板行情并生成摘要" @keyup.enter.exact.prevent="sendRemoteCommand" />
              <div class="remote-actions"><el-select model-value="微信" style="width: 120px"><el-option label="微信" value="微信" /><el-option label="企业微信" value="企业微信" /><el-option label="飞书" value="飞书" /></el-select><el-button type="primary" @click="sendRemoteCommand">发送遥控指令</el-button></div>
            </div>
          </div>
        </el-card>
      </template>
      <!-- 灵感案例 -->
      <template v-else-if="moduleId === 'cases'">
        <div v-if="caseData.length" class="card-grid">
          <el-card v-for="item in caseData" :key="item.case_id" class="section-card case-card" shadow="never">
            <div class="card-heading"><strong>{{ item.title }}</strong><el-tag>{{ item.category }}</el-tag></div>
            <div class="metric-trend">已复用 {{ item.reuse_count }} 次</div>
            <p class="case-prompt">{{ item.prompt }}</p>
            <div class="tag-line"><el-tag v-for="expert in item.experts" :key="expert" size="small" type="info">{{ expert }}</el-tag></div>
            <div class="tag-line"><el-tag v-for="skill in item.skills" :key="skill" size="small" effect="plain">{{ skill }}</el-tag></div>
            <el-button type="primary" class="card-action" @click="openCaseAssembly(item)">做同款</el-button>
          </el-card>
        </div>
        <el-empty v-else description="案例库正在建设中" />
      </template>

      <!-- 审批 -->
      <template v-else-if="moduleId === 'approvals'">
        <div class="filter-row">
          <el-radio-group v-model="approvalFilter">
            <el-radio-button value="pending">待审批</el-radio-button>
            <el-radio-button value="L3">L3</el-radio-button>
            <el-radio-button value="L4">L4</el-radio-button>
            <el-radio-button value="all">全部</el-radio-button>
          </el-radio-group>
          <span class="header-meta">L3 必审 · L4 双人复核 · TTL 超时自动挂起</span>
        </div>
        <el-card v-if="filteredApprovals.length" class="section-card" shadow="never">
          <template #header><strong>IN4 免疫审批队列</strong><span class="header-meta">{{ pendingApprovalCount }} 条待处理</span></template>
          <el-table :data="filteredApprovals" stripe>
            <el-table-column prop="approval_id" label="审批 ID" width="110" />
            <el-table-column prop="action" label="动作" min-width="180" />
            <el-table-column label="风险等级" width="110"><template #default="{ row }"><el-tag :type="approvalTagType(row.risk_level)">{{ row.risk_level }}</el-tag></template></el-table-column>
            <el-table-column prop="applicant" label="发起方" width="130" />
            <el-table-column prop="target" label="目标" min-width="160" />
            <el-table-column prop="ttl" label="TTL" width="120" />
            <el-table-column label="复核进度" width="120"><template #default="{ row }">{{ row.approved_by.length }} / {{ row.required_approvals }}</template></el-table-column>
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="approvalStateTagType(row.state)">{{ approvalStateLabel(row.state) }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.state === 'pending'" size="small" type="primary" @click="openApprovalDecision(row)">审批</el-button>
                <el-button size="small" text type="primary" @click="openDetail(`${row.approval_id} 审批详情`, row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
        <el-empty v-else description="当前筛选下无审批记录" />
      </template>
    </template>

    <el-drawer v-model="senseDrawerVisible" :title="`${selectedSense?.name || ''} 渠道详情`" size="420px">
      <div v-if="selectedSense" class="drawer-detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="渠道">{{ selectedSense.source }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ selectedSense.state }}</el-descriptions-item>
          <el-descriptions-item label="采集量">{{ selectedSense.count }} 条</el-descriptions-item>
          <el-descriptions-item label="质检通过率">{{ selectedSense.quality }}%</el-descriptions-item>
          <el-descriptions-item label="原因">{{ selectedSense.reason }}</el-descriptions-item>
        </el-descriptions>
        <h4>最近样本</h4>
        <div v-for="sample in selectedSense.samples" :key="sample.time" class="sample-row"><span>{{ sample.time }} · {{ sample.title }}</span><el-tag size="small">质检 {{ sample.quality }}%</el-tag></div>
      </div>
    </el-drawer>

    <el-dialog v-model="detailVisible" :title="detailTitle" width="620px">
      <pre v-if="detailIsJson" class="detail-pre">{{ prettyDetail }}</pre>
      <div v-else class="plain-detail">{{ detailContent }}</div>
    </el-dialog>

    <el-dialog v-model="schemaVisible" :title="`${selectedExpert?.name || ''} 输出 Schema`" width="640px">
      <pre v-if="selectedExpert" class="detail-pre">{{ pretty(selectedExpert.output_schema) }}</pre>
    </el-dialog>

    <el-dialog v-model="authVisible" title="OAuth 授权" width="520px">
      <div v-if="selectedConnector">
        <p>连接器：<strong>{{ selectedConnector.name }}</strong> · {{ selectedConnector.protocol }}</p>
        <el-alert title="将请求最小权限授权，令牌由连接器网关加密保存。" type="info" :closable="false" show-icon />
        <div class="scope-list"><el-check-tag checked>读取文档列表</el-check-tag><el-check-tag checked>读取选定文档</el-check-tag><el-check-tag>写入任务结果</el-check-tag></div>
      </div>
      <template #footer><el-button @click="authVisible = false">取消</el-button><el-button type="primary" :loading="authorizing" @click="confirmConnectorAuth">确认授权</el-button></template>
    </el-dialog>

    <el-dialog v-model="automationFormVisible" title="新建自动化" width="600px">
      <el-form :model="automationForm" label-width="100px">
        <el-form-item label="任务名称" required><el-input v-model="automationForm.auto_name" placeholder="例如：每日行业简报" /></el-form-item>
        <el-form-item label="Cron" required><el-input v-model="automationForm.cron" placeholder="0 9 * * 1-5" @blur="validateCron" /><span class="form-hint">5 段 cron：分钟 小时 日 月 周</span></el-form-item>
        <el-form-item label="执行动作" required><el-input v-model="automationForm.action" placeholder="检索 + 生成 + 推送" /></el-form-item>
        <el-form-item label="推送渠道"><el-checkbox-group v-model="automationForm.push"><el-checkbox value="工作区">工作区</el-checkbox><el-checkbox value="IM">IM</el-checkbox><el-checkbox value="邮件">邮件</el-checkbox><el-checkbox value="审批中心">审批中心</el-checkbox></el-checkbox-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="automationFormVisible = false">取消</el-button><el-button type="primary" :loading="savingAutomation" @click="createAutomation">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="caseAssemblyVisible" :title="`装配：${selectedCase?.title || ''}`" width="620px">
      <div v-if="selectedCase" class="assembly-panel">
        <el-steps :active="assemblyStep" finish-status="success" simple>
          <el-step title="Prompt" /><el-step title="专家" /><el-step title="技能" /><el-step title="创建任务" />
        </el-steps>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="Prompt">{{ selectedCase.prompt }}</el-descriptions-item>
          <el-descriptions-item label="专家">{{ selectedCase.experts.join('、') }}</el-descriptions-item>
          <el-descriptions-item label="技能">{{ selectedCase.skills.join('、') }}</el-descriptions-item>
          <el-descriptions-item label="预期产物">{{ selectedCase.outcome }}</el-descriptions-item>
        </el-descriptions>
        <el-alert title="装配失败时将自动回滚，不会产生半配置任务。" type="info" :closable="false" show-icon />
      </div>
      <template #footer><el-button @click="caseAssemblyVisible = false">取消</el-button><el-button type="primary" :loading="assembling" @click="reuseCase">确认并创建任务</el-button></template>
    </el-dialog>

    <el-dialog v-model="approvalDecisionVisible" :title="`审批：${selectedApproval?.action || ''}`" width="660px">
      <div v-if="selectedApproval" class="approval-panel">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="等级">{{ selectedApproval.risk_level }}</el-descriptions-item>
          <el-descriptions-item label="发起方">{{ selectedApproval.applicant }}</el-descriptions-item>
          <el-descriptions-item label="目标">{{ selectedApproval.target }}</el-descriptions-item>
          <el-descriptions-item label="TTL">{{ selectedApproval.ttl }}</el-descriptions-item>
          <el-descriptions-item label="影响" :span="2">{{ selectedApproval.impact }}</el-descriptions-item>
          <el-descriptions-item label="参数" :span="2"><pre class="inline-pre">{{ pretty(selectedApproval.parameters) }}</pre></el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="selectedApproval.risk_level === 'L4'" :title="`L4 双人复核：当前 ${selectedApproval.approved_by.length}/${selectedApproval.required_approvals}，本次确认后仍需第二人复核`" type="warning" :closable="false" show-icon />
        <el-radio-group v-model="approvalDecision" class="decision-radio"><el-radio value="approved">通过</el-radio><el-radio value="rejected">拒绝</el-radio></el-radio-group>
        <el-input v-model="approvalReason" type="textarea" :rows="3" :placeholder="approvalDecision === 'rejected' ? '拒绝理由（必填）' : '审批备注（可选）'" />
      </div>
      <template #footer><el-button @click="approvalDecisionVisible = false">取消</el-button><el-button type="primary" :loading="decidingApproval" @click="submitApprovalDecision">提交审批</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Checked, Document, Microphone, Promotion, View } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { dataSourceStatus } from '../api/status'
import {
  approvals as mockApprovals, automations as mockAutomations, brainChain, brainDecision,
  cases as mockCases, collaboration as mockCollaboration, connectors as mockConnectors,
  evolution as mockEvolution, experts as mockExperts, managedModels as mockManagedModels,
  modelRoutes as mockModelRoutes, modelTokenTrend as mockModelTokenTrend, organs as mockOrgans,
  remoteChannels as mockRemoteChannels, remoteFlow as mockRemoteFlow,
  senses as mockSenses, skills as mockSkills, vitalSigns as mockVitalSigns,
} from '../api/mock'
import { moduleMap } from '../config/modules'
import type {
  ApprovalItem, AutomationItem, CaseItem, CollaborationAgent, ConnectorItem, ExpertProfile, HealingRecord,
  ManagedModel, ModelRoute, ModuleId, OrganHealth, RemoteChannel, RemoteFlowEvent, SenseChannel, SkillItem, VitalSign,
} from '../types'

const route = useRoute()
const moduleId = computed(() => (route.meta.module as ModuleId) || 'overview')
const module = computed(() => moduleMap[moduleId.value] || moduleMap.overview)
const iconMap: Record<string, unknown> = { View, Microphone, Document, Promotion, Checked }

const loading = ref(true)
const loadError = ref(false)
// 模块 id 与 provider 中登记的降级 scope 名称对照（仅 collab 不一致）
const degradeScopeAlias: Partial<Record<ModuleId, string>> = { collab: 'collaboration' }
const moduleDegraded = computed(() =>
  dataSourceStatus.degraded.find(item => item.scope === (degradeScopeAlias[moduleId.value] || moduleId.value)) || null,
)
const vitalsOnline = ref(true)
const vitalData = ref<VitalSign[]>(mockVitalSigns.map(item => ({ ...item })))
const organData = ref<OrganHealth[]>(mockOrgans.map(item => ({ ...item })))
const brainData = ref({
  decision: { ...brainDecision, sources: brainDecision.sources.map(item => ({ ...item })) },
  chain: brainChain.map(item => ({ ...item })),
})
const senseData = ref<SenseChannel[]>(mockSenses.map(item => ({ ...item, samples: item.samples.map(sample => ({ ...sample })) })))
const evolutionData = ref({
  metrics: mockEvolution.metrics.map(item => ({ ...item })),
  healings: mockEvolution.healings.map(item => ({ ...item })),
  trend: mockEvolution.trend.map(item => ({ ...item })),
})
const collaborationData = ref(JSON.parse(JSON.stringify(mockCollaboration)))
const expertData = ref<ExpertProfile[]>(mockExperts.map(item => ({ ...item })))
const skillData = ref<SkillItem[]>(mockSkills.map(item => ({ ...item })))
const connectorData = ref<ConnectorItem[]>(mockConnectors.map(item => ({ ...item })))
const automationData = ref<AutomationItem[]>(mockAutomations.map(item => ({ ...item, push: [...item.push] })))
const caseData = ref<CaseItem[]>(mockCases.map(item => ({ ...item })))
const approvalData = ref<ApprovalItem[]>(mockApprovals.map(item => ({ ...item, approved_by: [...item.approved_by], parameters: { ...item.parameters } })))
const modelData = ref<ManagedModel[]>(mockManagedModels.map(item => ({ ...item, task_types: [...item.task_types] })))
const modelRouteData = ref<ModelRoute[]>(mockModelRoutes.map(item => ({ ...item })))
const modelTrendData = ref(mockModelTokenTrend.map(item => ({ ...item })))
const remoteChannelData = ref<RemoteChannel[]>(mockRemoteChannels.map(item => ({ ...item, capabilities: [...item.capabilities] })))
const remoteFlowData = ref<RemoteFlowEvent[]>(mockRemoteFlow.map(item => ({ ...item })))
const remoteCommand = ref('')

const detailVisible = ref(false)
const detailTitle = ref('')
const detailContent = ref('')
const detailPayload = ref<unknown>(null)
const detailIsJson = ref(false)
const senseDrawerVisible = ref(false)
const selectedSense = ref<SenseChannel | null>(null)
const schemaVisible = ref(false)
const selectedExpert = ref<ExpertProfile | null>(null)
const authVisible = ref(false)
const selectedConnector = ref<ConnectorItem | null>(null)
const authorizing = ref(false)
const automationFormVisible = ref(false)
const savingAutomation = ref(false)
const automationForm = reactive({ auto_name: '', cron: '0 9 * * 1-5', action: '', push: ['工作区'] as string[] })
const caseAssemblyVisible = ref(false)
const selectedCase = ref<CaseItem | null>(null)
const assembling = ref(false)
const assemblyStep = ref(0)
const approvalDecisionVisible = ref(false)
const selectedApproval = ref<ApprovalItem | null>(null)
const approvalDecision = ref<'approved' | 'rejected'>('approved')
const approvalReason = ref('')
const decidingApproval = ref(false)
const expertStateFilter = ref('all')
const skillCategory = ref('all')
const skillKeyword = ref('')
const approvalFilter = ref('pending')
const busMode = ref<'fanout' | 'pipeline' | 'negotiate'>('fanout')
const messageFilter = ref<'all' | 'dispatch' | 'result' | 'heartbeat' | 'negotiate'>('all')
const busModes = [
  { id: 'fanout', label: '扇出', english: 'Fan-out', description: '团长拆解 N 个子任务，并行分派给多个 Agent，最后统一回收结果。', use_case: '竞品调研 / 多文档分析 / 多源采集', nodes: ['团长', 'Agent A', 'Agent B', 'Agent C'] },
  { id: 'pipeline', label: '流水线', english: 'Pipeline', description: 'Agent 按依赖顺序接力，前一成员的产物作为下一成员输入。', use_case: '检索 → 分析 → 生成 → 质检', nodes: ['检索', '分析', '生成', '质检'] },
  { id: 'negotiate', label: '协商', english: 'Negotiate', description: '多个 Agent 对同一问题提交方案，由仲裁器按置信度、证据和规则裁决。', use_case: '方案评审 / 冲突消解 / 质量仲裁', nodes: ['Agent A', 'Agent B', '仲裁器', '裁决'] },
] as const
const messageFilters = [
  { id: 'all', label: '全部' },
  { id: 'dispatch', label: 'dispatch' },
  { id: 'result', label: 'result' },
  { id: 'heartbeat', label: 'heartbeat' },
  { id: 'negotiate', label: 'negotiate' },
] as const

const degradedSenses = computed(() => senseData.value.filter(item => item.state !== 'UP'))
const filteredExperts = computed(() => expertStateFilter.value === 'all' ? expertData.value : expertData.value.filter(item => item.state === expertStateFilter.value))
const skillCategories = computed(() => ['all', ...Array.from(new Set(skillData.value.map(item => item.category)))])
const filteredSkills = computed(() => skillData.value.filter(item => (skillCategory.value === 'all' || item.category === skillCategory.value) && (!skillKeyword.value || `${item.name}${item.description}`.toLowerCase().includes(skillKeyword.value.toLowerCase()))))
const failedAutomations = computed(() => automationData.value.filter(item => item.state === 'failed'))
const filteredApprovals = computed(() => {
  if (approvalFilter.value === 'all') return approvalData.value
  if (approvalFilter.value === 'L3' || approvalFilter.value === 'L4') return approvalData.value.filter(item => item.risk_level === approvalFilter.value)
  return approvalData.value.filter(item => item.state === 'pending')
})
const pendingApprovalCount = computed(() => approvalData.value.filter(item => item.state === 'pending').length)
const filteredCollabMessages = computed(() => messageFilter.value === 'all' ? collaborationData.value.messages : collaborationData.value.messages.filter((item: any) => item.type === messageFilter.value))
const activeMode = computed(() => busModes.find(mode => mode.id === busMode.value) || busModes[0])
const activeModeLabel = computed(() => activeMode.value.label)
const activeModelCount = computed(() => modelData.value.filter(item => item.state === 'active').length)
const onlineRemoteChannels = computed(() => remoteChannelData.value.filter(item => item.state === 'online').length)
const prettyDetail = computed(() => pretty(detailPayload.value))

async function loadWorkbench() {
  loading.value = true
  loadError.value = false
  try {
    const data = await dataProvider.getWorkbenchData() as any
    if (Array.isArray(data.vitals) && data.vitals.length) vitalData.value = data.vitals
    if (Array.isArray(data.organs) && data.organs.length) organData.value = data.organs
    if (data.brain?.chain) brainData.value = data.brain
    if (Array.isArray(data.senses) && data.senses.length) senseData.value = data.senses
    if (data.evolution?.metrics) evolutionData.value = data.evolution
    if (data.collaboration?.agents) collaborationData.value = data.collaboration
    if (Array.isArray(data.experts)) expertData.value = data.experts
    if (Array.isArray(data.skills)) skillData.value = data.skills
    if (Array.isArray(data.connectors)) connectorData.value = data.connectors
    if (Array.isArray(data.automations)) automationData.value = data.automations
    if (Array.isArray(data.cases)) caseData.value = data.cases
    if (Array.isArray(data.approvals)) approvalData.value = data.approvals
    if (Array.isArray(data.models)) modelData.value = data.models
    if (Array.isArray(data.model_routes)) modelRouteData.value = data.model_routes
    if (Array.isArray(data.model_token_trend)) modelTrendData.value = data.model_token_trend
    if (Array.isArray(data.remote_channels)) remoteChannelData.value = data.remote_channels
    if (Array.isArray(data.remote_flow)) remoteFlowData.value = data.remote_flow
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function organTagType(row: OrganHealth) {
  if (!row.baseline || row.score === null) return 'info'
  if (row.score >= 90) return 'success'
  if (row.score >= 75) return 'warning'
  return 'danger'
}

function exportOrganReport() {
  ElMessage.success('已打开打印视图，可在系统对话框中选择“另存为 PDF”')
  window.setTimeout(() => window.print(), 120)
}

function reconnectVitals() {
  vitalsOnline.value = true
  ElMessage.success('生命体征实时流已重新连接')
}

function openSenseDetail(item: SenseChannel) {
  selectedSense.value = item
  senseDrawerVisible.value = true
}

function senseTagType(state: SenseChannel['state']) {
  return state === 'UP' ? 'success' : state === 'DEGRADED' ? 'warning' : 'danger'
}

function openHealingDetail(row: HealingRecord) {
  openDetail(`${row.healing_id} 自愈详情`, row)
}

function openProtocol(message: any) {
  openDetail(`${message.message_id} · ${message.type} 协议体`, message.payload)
}

function agentTagType(state: CollaborationAgent['state']) {
  return { running: 'primary', done: 'success', waiting: 'warning', blocked: 'danger' }[state] as 'primary' | 'success' | 'warning' | 'danger'
}

function agentStateLabel(state: CollaborationAgent['state']) {
  return { running: '执行中', done: '已完成', waiting: '等待中', blocked: '阻塞' }[state]
}

function confidenceText(agent: CollaborationAgent) {
  if (agent.confidence <= 0) return '等待结果'
  return `置信度 ${agent.confidence.toFixed(2)}`
}
function messageType(type: string) {
  return type === 'result' ? 'success' : type === 'negotiate' ? 'warning' : 'primary'
}

function edgeStart(edge: string[]) {
  const node = collaborationData.value.dag.nodes.find((item: any) => item.id === edge[0])
  return { x: (node?.x || 0) + 6, y: (node?.y || 0) + 6 }
}

function edgeEnd(edge: string[]) {
  const node = collaborationData.value.dag.nodes.find((item: any) => item.id === edge[1])
  return { x: node?.x || 0, y: (node?.y || 0) + 6 }
}

function openDagNode(node: any) {
  openDetail(`DAG 节点 · ${node.label}`, { node_id: node.id, state: node.state, position: { x: node.x, y: node.y } })
}

function openDetail(title: string, payload: unknown) {
  detailTitle.value = title
  detailPayload.value = payload
  detailIsJson.value = typeof payload === 'object' && payload !== null
  detailContent.value = typeof payload === 'string' ? payload : ''
  detailVisible.value = true
}

function modelStateType(state: ManagedModel['state']) {
  return { active: 'success', standby: 'info', degraded: 'warning', disabled: 'danger' }[state] as 'success' | 'info' | 'warning' | 'danger'
}

function modelStateLabel(state: ManagedModel['state']) {
  return { active: '活跃', standby: '待命', degraded: '降级', disabled: '停用' }[state]
}

function activateModel(model: ManagedModel) {
  modelData.value.forEach(item => { item.state = item.model_id === model.model_id ? 'active' : (item.state === 'degraded' ? 'degraded' : 'standby') })
  ElMessage.success(`${model.name} 已设为活跃模型`)
}

function channelStateType(state: RemoteChannel['state']) {
  return { online: 'success', available: 'primary', offline: 'info' }[state] as 'success' | 'primary' | 'info'
}

function channelStateLabel(state: RemoteChannel['state']) {
  return { online: '在线', available: '可接入', offline: '离线' }[state]
}

function sendRemoteCommand() {
  if (!remoteCommand.value.trim()) {
    ElMessage.warning('请输入遥控指令')
    return
  }
  const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const text = remoteCommand.value.trim()
  remoteFlowData.value.push(
    { event_id: `RM-${Date.now()}-1`, time: now, direction: '下发', channel: '手机微信', message: text, status: 'done' },
    { event_id: `RM-${Date.now()}-2`, time: now, direction: '执行', channel: '桌面 Agent', message: '任务已接入，正在规划并调度专家 Agent。', status: 'running' },
    { event_id: `RM-${Date.now()}-3`, time: now, direction: '回传', channel: '手机微信', message: '任务已受理，结果将通过当前 IM 渠道回传。', status: 'done' },
  )
  remoteCommand.value = ''
  ElMessage.success('遥控指令已下发')
}
function pretty(value: unknown) {
  if (value === null || value === undefined) return '--'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

function expertTagType(state: ExpertProfile['state']) {
  return state === 'active' ? 'success' : state === 'suspended' ? 'warning' : 'info'
}

function expertStateLabel(state: ExpertProfile['state']) {
  return { active: '可用', suspended: '已暂停', deprecated: '已废弃' }[state]
}

function openExpertSchema(expert: ExpertProfile) {
  selectedExpert.value = expert
  schemaVisible.value = true
}

async function installSkill(skill: SkillItem) {
  skill.state = 'installing'
  try {
    await dataProvider.installSkill(skill.skill_id)
    skill.state = 'installed'
    skill.installs += 1
    ElMessage.success(`${skill.name} 安装成功，已加入技能目录`)
  } catch {
    skill.state = 'failed'
    ElMessage.error(`${skill.name} 安装失败，已保留原版本`)
  }
}

function connectorTagType(state: ConnectorItem['state']) {
  return state === 'online' ? 'success' : state === 'pending_auth' ? 'warning' : 'danger'
}

function connectorStateLabel(state: ConnectorItem['state']) {
  return { online: '在线', pending_auth: '待授权', offline: '离线' }[state]
}

function openConnectorAuth(row: ConnectorItem) {
  selectedConnector.value = row
  authVisible.value = true
}

async function confirmConnectorAuth() {
  if (!selectedConnector.value) return
  authorizing.value = true
  try {
    await dataProvider.authorizeConnector(selectedConnector.value.connector_id)
    selectedConnector.value.state = 'online'
    selectedConnector.value.enabled = true
    selectedConnector.value.health_note = 'OAuth 授权完成，等待首次健康检查'
    authVisible.value = false
    ElMessage.success(`${selectedConnector.value.name} 授权成功`)
  } finally {
    authorizing.value = false
  }
}

function toggleConnector(row: ConnectorItem) {
  ElMessage.success(`${row.name} 已${row.enabled ? '启用' : '停用'}`)
}

function automationTagType(state: AutomationItem['state']) {
  return state === 'active' ? 'success' : state === 'failed' ? 'danger' : 'info'
}

function automationStateLabel(state: AutomationItem['state']) {
  return { active: '运行中', paused: '已暂停', failed: '失败' }[state]
}

function openAutomationForm() {
  automationForm.auto_name = ''
  automationForm.cron = '0 9 * * 1-5'
  automationForm.action = ''
  automationForm.push = ['工作区']
  automationFormVisible.value = true
}

function validateCron() {
  const parts = automationForm.cron.trim().split(/\s+/)
  if (parts.length !== 5) {
    ElMessage.warning('Cron 表达式应为 5 段：分钟 小时 日 月 周')
    return false
  }
  return true
}

async function createAutomation() {
  if (!automationForm.auto_name.trim() || !automationForm.action.trim() || !validateCron()) {
    ElMessage.warning('请完整填写任务名称、Cron 和执行动作')
    return
  }
  savingAutomation.value = true
  try {
    await dataProvider.createAutomation({ ...automationForm, name: automationForm.auto_name })
    const item: AutomationItem = {
      automation_id: `AUTO-${automationData.value.length + 10}`,
      auto_name: automationForm.auto_name.trim(),
      cron: automationForm.cron,
      action: automationForm.action,
      state: 'active',
      last_run: '-',
      next_run: '按 Cron 计算',
      push: [...automationForm.push],
    }
    automationData.value.unshift(item)
    automationFormVisible.value = false
    ElMessage.success('自动化任务已创建')
  } finally {
    savingAutomation.value = false
  }
}

function toggleAutomation(row: AutomationItem) {
  ElMessage.success(`${row.auto_name} 已${row.state === 'active' ? '启用' : '暂停'}`)
}

async function retryAutomation(row: AutomationItem) {
  await dataProvider.patchAutomation(row.automation_id, { state: 'active' })
  row.state = 'active'
  row.last_error = ''
  row.next_run = '按 Cron 计算'
  ElMessage.success(`${row.auto_name} 已重新进入调度队列`)
}

function openCaseAssembly(item: CaseItem) {
  selectedCase.value = item
  assemblyStep.value = 1
  caseAssemblyVisible.value = true
}

async function reuseCase() {
  if (!selectedCase.value) return
  assembling.value = true
  try {
    assemblyStep.value = 3
    await dataProvider.reuseCase(selectedCase.value.case_id)
    selectedCase.value.reuse_count += 1
    assemblyStep.value = 4
    caseAssemblyVisible.value = false
    ElMessage.success(`已基于「${selectedCase.value.title}」创建任务`)
  } catch {
    assemblyStep.value = 0
    ElMessage.error('案例装配失败，已回滚所有配置')
  } finally {
    assembling.value = false
  }
}

function approvalTagType(level: ApprovalItem['risk_level']) {
  return level === 'L4' ? 'danger' : level === 'L3' ? 'warning' : 'info'
}

function approvalStateTagType(state: ApprovalItem['state']) {
  return { approved: 'success', rejected: 'danger', expired: 'warning', pending: 'primary' }[state]
}

function approvalStateLabel(state: ApprovalItem['state']) {
  return { pending: '待审批', approved: '已通过', rejected: '已拒绝', expired: '已超时' }[state]
}

function openApprovalDecision(row: ApprovalItem) {
  selectedApproval.value = row
  approvalDecision.value = 'approved'
  approvalReason.value = ''
  approvalDecisionVisible.value = true
}

async function submitApprovalDecision() {
  const item = selectedApproval.value
  if (!item) return
  if (approvalDecision.value === 'rejected' && !approvalReason.value.trim()) {
    ElMessage.warning('拒绝审批时必须填写理由')
    return
  }
  decidingApproval.value = true
  try {
    await dataProvider.decideApproval(item.approval_id, approvalDecision.value, approvalReason.value)
    if (approvalDecision.value === 'rejected') {
      item.state = 'rejected'
      item.reason = approvalReason.value
      ElMessage.success('审批已拒绝并记录审计日志')
    } else if (item.risk_level === 'L4') {
      if (!item.approved_by.includes('current-user')) item.approved_by.push('current-user')
      item.state = item.approved_by.length >= item.required_approvals ? 'approved' : 'pending'
      ElMessage.success(item.state === 'approved' ? 'L4 双人复核完成' : '已记录第一票，等待第二人复核')
    } else {
      if (!item.approved_by.includes('current-user')) item.approved_by.push('current-user')
      item.state = 'approved'
      ElMessage.success('审批通过，动作已进入执行队列')
    }
    approvalDecisionVisible.value = false
  } finally {
    decidingApproval.value = false
  }
}

onMounted(loadWorkbench)
</script>

<style scoped>
.header-action { float: right; }
.header-meta { color: var(--wp-sub); font-size: 12px; }
.metric-card .header-meta { float: right; }
.status-ribbon { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: var(--wp-card); color: var(--wp-sub); font-size: 12px; }
.status-ribbon strong { color: var(--wp-text); }
.status-ribbon > span:last-of-type { margin-left: auto; }
.pulse-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--wp-success); box-shadow: 0 0 12px var(--wp-success); animation: breathe 2s infinite; }
.pulse-dot.offline { background: var(--wp-danger); box-shadow: 0 0 12px var(--wp-danger); }
.vitals-grid { grid-template-columns: repeat(5, minmax(0, 1fr)); }
.vital-card.warning { border-color: rgba(245,158,11,.55) !important; }
.vital-card.critical { border-color: rgba(255,92,122,.65) !important; }
.heartbeat { animation: heartBeat 1.8s ease-in-out infinite; transform-origin: left center; }
@keyframes heartBeat { 0%,100% { text-shadow: none; } 45% { text-shadow: 0 0 22px rgba(94,234,212,.65); } 50% { transform: scale(1.025); } }
.detail-pre, .inline-pre { margin: 0; white-space: pre-wrap; word-break: break-word; color: var(--wp-text); font-family: "Cascadia Code", Consolas, monospace; line-height: 1.7; }
.inline-pre { max-height: 180px; overflow: auto; font-size: 12px; }
.plain-detail { color: var(--wp-sub); line-height: 1.8; }
.sense-grid, .card-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.sense-card { cursor: pointer; transition: transform .2s ease, border-color .2s ease; }
.sense-card:hover { transform: translateY(-3px); border-color: var(--wp-primary) !important; }
.sense-card.degraded { border-color: rgba(245,158,11,.6) !important; }
.sense-card.down { border-color: rgba(255,92,122,.7) !important; }
.sense-top, .card-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.sense-card h3 { margin: 14px 0 6px; }
.sense-value { margin-bottom: 10px; font-family: "Bodoni MT", "Times New Roman", serif; font-size: 28px; }
.sense-value span { margin-left: 4px; color: var(--wp-sub); font-size: 12px; }
.sense-foot { margin-top: 10px; color: var(--wp-sub); font-size: 10px; }
.trend-chart { display: flex; align-items: flex-end; justify-content: space-around; min-height: 260px; padding: 22px 8px 0; }
.trend-column { display: flex; align-items: center; flex-direction: column; width: 13%; min-width: 58px; }
.trend-value { margin-bottom: 8px; font-family: "Bodoni MT", serif; }
.trend-track { display: flex; align-items: flex-end; width: 26px; height: 150px; border-radius: 8px 8px 2px 2px; background: rgba(148,163,184,.09); overflow: hidden; }
.trend-bar { width: 100%; border-radius: 8px 8px 2px 2px; background: linear-gradient(180deg, var(--wp-primary), rgba(212,175,55,.65)); box-shadow: 0 0 18px rgba(94,234,212,.2); }
.trend-column strong { margin-top: 10px; font-size: 12px; }
.trend-column small { margin-top: 3px; color: var(--wp-sub); font-size: 10px; }
.agent-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.agent-card { display: flex; align-items: center; flex-direction: column; gap: 8px; width: 100%; min-height: 142px; padding: 14px; border: 1px solid var(--wp-border); border-radius: 14px; background: rgba(148,163,184,.06); cursor: help; }
.agent-card > div:first-child { display: flex; flex-direction: column; text-align: center; }
.agent-card span, .agent-card small { color: var(--wp-sub); font-size: 11px; }
.dag-card :deep(.el-card__body) { padding: 0; }
.dag-canvas { position: relative; height: 270px; margin: 8px; border: 1px solid var(--wp-border); border-radius: 14px; background-image: linear-gradient(var(--wp-grid-color) 1px, transparent 1px), linear-gradient(90deg, var(--wp-grid-color) 1px, transparent 1px); background-size: 28px 28px; overflow: hidden; }
.dag-lines { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.dag-lines line { stroke: rgba(94,234,212,.45); stroke-width: .45; vector-effect: non-scaling-stroke; }
.dag-node { position: absolute; z-index: 1; transform: translate(-50%, -50%); min-width: 92px; padding: 9px 12px; border: 1px solid var(--wp-border-gold); border-radius: 10px; background: var(--wp-card-solid); color: var(--wp-text); box-shadow: var(--wp-glow); cursor: pointer; }
.dag-node.done { border-color: rgba(52,211,153,.65); }
.dag-node.running { border-color: rgba(94,234,212,.8); animation: breathe 2s infinite; }
.dag-node.waiting { border-color: rgba(245,158,11,.55); }
.message-flow { max-height: 420px; overflow: auto; }
.message-pill { padding: 4px 0; border: 0; background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.gate-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.gate-card { display: flex; flex-direction: column; gap: 5px; padding: 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.05); color: var(--wp-text); cursor: pointer; text-align: left; }
.gate-card span { color: var(--wp-success); font-size: 11px; }
.gate-card.pending span, .gate-card.waiting span { color: #f59e0b; }
.gate-card small { color: var(--wp-sub); }
.filter-row { display: flex; justify-content: space-between; align-items: center; gap: 14px; flex-wrap: wrap; }
.mini-search { width: 220px; }
.expert-card p, .methodology, .case-prompt, .skill-description { color: var(--wp-sub); line-height: 1.6; }
.methodology { margin: 12px 0; }
.skill-meta { margin: 12px 0; color: var(--wp-sub); font-size: 12px; }
.skill-description { min-height: 48px; font-size: 12px; }
.card-action { width: 100%; margin-top: 14px; }
.inline-tag { margin: 2px 4px 2px 0; }
.form-hint { display: block; margin-top: 5px; color: var(--wp-sub); font-size: 11px; }
.scope-list { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; }
.assembly-panel { display: flex; flex-direction: column; gap: 16px; }
.approval-panel { display: flex; flex-direction: column; gap: 14px; }
.decision-radio { margin-top: 4px; }
.drawer-detail h4 { margin: 20px 0 8px; }
.sample-row { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--wp-border); }
@media (max-width: 1300px) { .vitals-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .agent-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .gate-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1100px) { .sense-grid, .card-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .vitals-grid, .sense-grid, .card-grid, .agent-grid, .gate-grid { grid-template-columns: 1fr; } .trend-chart { overflow-x: auto; justify-content: flex-start; gap: 8px; } }
</style>

<style scoped>
.collab-kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.collab-kpi { display: flex; flex-direction: column; gap: 5px; padding: 15px 16px; border: 1px solid var(--wp-border); border-radius: 14px; background: var(--wp-card); box-shadow: var(--wp-shadow); }
.collab-kpi span { color: var(--wp-sub); font-size: 10px; letter-spacing: .12em; text-transform: uppercase; }
.collab-kpi strong { font-family: "Bodoni MT", serif; font-size: 23px; }
.collab-kpi small { color: var(--wp-sub); }
.bus-topology-card :deep(.el-card__body) { padding: 0; }
.bus-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 14px 18px; border-bottom: 1px solid var(--wp-border); background: linear-gradient(90deg, rgba(94,234,212,.05), rgba(212,175,55,.035)); }
.bus-caption { display: flex; flex-direction: column; gap: 3px; }
.bus-caption strong { color: var(--wp-primary); font-size: 13px; }
.bus-caption span { color: var(--wp-sub); font-size: 10px; }
.bus-mode-switch, .message-filter { display: flex; gap: 5px; flex-wrap: wrap; }
.bus-mode-switch button, .message-filter button { padding: 5px 10px; border: 1px solid var(--wp-border); border-radius: 999px; background: transparent; color: var(--wp-sub); cursor: pointer; font-size: 10px; }
.bus-mode-switch button.active, .message-filter button.active { border-color: rgba(94,234,212,.6); background: rgba(94,234,212,.12); color: var(--wp-primary); box-shadow: 0 0 12px rgba(94,234,212,.12); }
.agent-bus { padding: 22px 18px 12px; background: linear-gradient(180deg, rgba(3,7,18,.30), rgba(15,23,42,.12)); }
.agent-bus-row { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(380px, 2fr) minmax(130px, .55fr); align-items: center; gap: 14px; min-height: 72px; }
.agent-bus-row + .agent-bus-row { border-top: 1px dashed rgba(148,163,184,.12); }
.agent-bus-row.leader { background: linear-gradient(90deg, rgba(212,175,55,.07), transparent 44%); }
.bus-agent-node { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 9px; padding: 9px 10px; border: 1px solid transparent; border-radius: 11px; background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.bus-agent-node:hover { border-color: var(--wp-border); background: rgba(148,163,184,.05); }
.bus-agent-node.leader { border-color: rgba(212,175,55,.28); }
.bus-agent-avatar { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: linear-gradient(135deg, rgba(94,234,212,.2), rgba(212,175,55,.17)); color: var(--wp-gold-soft); font-weight: 800; }
.bus-agent-copy { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.bus-agent-copy small { overflow: hidden; color: var(--wp-sub); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.bus-track { position: relative; height: 54px; }
.bus-line-base { position: absolute; left: 0; right: 0; top: 29px; height: 2px; background: repeating-linear-gradient(90deg, rgba(148,163,184,.24) 0 8px, rgba(148,163,184,.08) 8px 14px); }
.bus-line-live { position: absolute; left: 0; top: 28px; height: 4px; max-width: 100%; border-radius: 4px; background: linear-gradient(90deg, var(--wp-success), var(--wp-primary), var(--wp-gold)); box-shadow: 0 0 14px rgba(94,234,212,.4); transition: width .6s ease; }
.bus-line-live.dispatch { background: linear-gradient(90deg, rgba(212,175,55,.55), var(--wp-gold-soft)); }
.bus-line-live.waiting { background: repeating-linear-gradient(90deg, rgba(212,175,55,.55) 0 7px, rgba(212,175,55,.12) 7px 13px); box-shadow: none; }
.bus-packet { position: absolute; top: 25px; width: 10px; height: 10px; margin-left: -5px; border-radius: 50%; background: #fff; box-shadow: 0 0 0 3px rgba(94,234,212,.20), 0 0 14px rgba(94,234,212,.30); }
.bus-message-pill { position: absolute; top: 2px; transform: translateX(-50%); padding: 3px 8px; border: 1px solid var(--wp-border); border-radius: 999px; background: var(--wp-card-solid); color: var(--wp-sub); cursor: pointer; font-size: 9px; white-space: nowrap; }
.bus-message-pill.result { border-color: rgba(52,211,153,.5); color: var(--wp-success); }
.bus-message-pill.running { border-color: rgba(94,234,212,.5); color: var(--wp-primary); }
.bus-message-pill.dispatch { border-color: rgba(212,175,55,.6); color: var(--wp-gold-soft); }
.bus-message-pill.waiting { border-style: dashed; color: #f4d58d; }
.bus-agent-result { display: flex; flex-direction: column; gap: 4px; padding: 10px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(148,163,184,.04); color: var(--wp-text); cursor: pointer; text-align: left; }
.bus-agent-result strong { color: var(--wp-gold-soft); font-size: 12px; }
.bus-agent-result span { color: var(--wp-primary); font-size: 10px; }
.bus-agent-result small { overflow: hidden; color: var(--wp-sub); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.bus-status-bar { display: flex; gap: 18px; align-items: center; padding: 11px 18px; border-top: 1px solid var(--wp-border); color: var(--wp-sub); font-size: 10px; flex-wrap: wrap; }
.bus-status-bar span { display: inline-flex; align-items: center; gap: 6px; }
.bus-status-bar .gold { margin-left: auto; color: var(--wp-gold-soft); }
.collab-mid { grid-template-columns: 1fr; }
.compact-dag { height: 280px; }
.message-stream-card :deep(.el-card__body) { min-height: 330px; }
.message-stream-card *, .bus-message-card * { animation: none !important; transition: none !important; }
.collab-bottom-stack { grid-template-columns: 1fr; }
.message-filter { margin-bottom: 12px; }
.message-pill-flow { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; max-height: 280px; overflow: auto; padding-right: 3px; }
.bus-message-card { display: grid; grid-template-columns: auto auto 1fr; gap: 6px 9px; padding: 10px; border: 1px solid var(--wp-border); border-radius: 11px; background: rgba(148,163,184,.04); color: var(--wp-text); cursor: pointer; text-align: left; }
.bus-message-card:hover { border-color: var(--wp-primary); background: rgba(94,234,212,.05); }
.bus-message-card p { grid-column: 1 / -1; margin: 0; color: var(--wp-sub); font-size: 10px; }
.message-time { color: var(--wp-sub); font-size: 9px; }
.bus-message-card strong { color: var(--wp-primary); font-size: 10px; }
.bus-message-card.result strong { color: var(--wp-success); }
.bus-message-card.negotiate strong { color: #f59e0b; }
.message-route { color: var(--wp-sub); font-size: 9px; text-align: right; }
.compact-gates { grid-template-columns: repeat(2, minmax(0, 1fr)); }
@keyframes packetTravel { 0%, 100% { transform: translateX(-5px); opacity: .45; } 50% { transform: translateX(5px); opacity: 1; } }
@media (max-width: 1200px) { .agent-bus-row { grid-template-columns: minmax(180px, .7fr) minmax(320px, 1.6fr); } .bus-agent-result { grid-column: 1 / -1; margin-left: 44px; } .collab-mid { grid-template-columns: 1fr; } }
@media (max-width: 820px) { .collab-kpi-grid, .message-pill-flow { grid-template-columns: repeat(2, minmax(0, 1fr)); } .agent-bus { overflow-x: auto; } .agent-bus-row { min-width: 720px; } .bus-topbar { align-items: flex-start; flex-direction: column; } }
@media (max-width: 560px) { .collab-kpi-grid, .message-pill-flow, .compact-gates { grid-template-columns: 1fr; } }
</style>

<style scoped>
.collaboration-mode-card :deep(.el-card__body) { padding: 16px 18px; }
.collaboration-mode-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.collaboration-mode-item { display: flex; flex-direction: column; gap: 10px; padding: 14px; border: 1px solid var(--wp-border); border-radius: 14px; background: rgba(148,163,184,.04); color: var(--wp-text); cursor: pointer; text-align: left; transition: transform .2s ease, border-color .2s ease, box-shadow .2s ease; }
.collaboration-mode-item:hover { transform: translateY(-2px); border-color: rgba(94,234,212,.62); }
.collaboration-mode-item.active { border-color: rgba(212,175,55,.65); background: linear-gradient(145deg, rgba(212,175,55,.10), rgba(94,234,212,.045)); box-shadow: 0 0 22px rgba(212,175,55,.10); }
.mode-item-head { display: grid; grid-template-columns: 32px 1fr auto; align-items: center; gap: 9px; }
.mode-number { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 9px; background: rgba(94,234,212,.12); color: var(--wp-primary); font-family: "Bodoni MT", serif; font-size: 15px; }
.mode-title { display: flex; flex-direction: column; gap: 2px; }
.mode-title small { color: var(--wp-sub); font-size: 9px; letter-spacing: .08em; }
.collaboration-mode-item p { min-height: 48px; margin: 0; color: var(--wp-sub); font-size: 10px; line-height: 1.6; }
.mode-use-case { display: flex; gap: 7px; color: var(--wp-sub); font-size: 9px; }
.mode-use-case span { color: var(--wp-gold-soft); }
.mode-mini-topology { display: flex; align-items: center; gap: 4px; min-height: 30px; }
.mode-mini-topology i { padding: 4px 6px; border: 1px solid var(--wp-border); border-radius: 7px; color: var(--wp-sub); font-size: 8px; font-style: normal; white-space: nowrap; }
.mode-mini-topology i:not(:last-child)::after { content: "→"; margin-left: 6px; color: var(--wp-primary); }
.mode-mini-topology.pipeline i:not(:last-child)::after { color: var(--wp-gold-soft); }
.mode-mini-topology.negotiate i:nth-child(2)::after { content: "⇄"; color: #f59e0b; }
.active-mode-bar { display: grid; grid-template-columns: auto auto 1fr auto; align-items: center; gap: 10px; margin-top: 13px; padding-top: 12px; border-top: 1px solid var(--wp-border); }
.active-mode-bar > span { color: var(--wp-sub); font-size: 10px; }
.active-mode-bar > strong { color: var(--wp-primary); }
.active-mode-bar > em { color: var(--wp-sub); font-size: 10px; font-style: normal; }
.bus-active-mode { flex-shrink: 0; }
@media (max-width: 900px) { .collaboration-mode-grid { grid-template-columns: 1fr; } .collaboration-mode-item p { min-height: auto; } .active-mode-bar { grid-template-columns: 1fr; } }
</style>

<style scoped>
.managed-model-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.managed-model-card { display: flex; flex-direction: column; gap: 11px; padding: 14px; border: 1px solid var(--wp-border); border-radius: 14px; background: rgba(148,163,184,.04); transition: transform .2s ease, border-color .2s ease; }
.managed-model-card:hover { transform: translateY(-2px); border-color: var(--wp-primary); }
.managed-model-card.active { border-color: rgba(52,211,153,.5); background: linear-gradient(145deg, rgba(52,211,153,.07), rgba(94,234,212,.025)); }
.managed-model-card.degraded { border-color: rgba(245,158,11,.55); }
.model-card-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.model-card-head > div { display: flex; flex-direction: column; gap: 3px; }
.model-card-head small { color: var(--wp-sub); font-size: 9px; }
.model-tier { width: fit-content; padding: 2px 6px; border-radius: 6px; background: rgba(94,234,212,.12); color: var(--wp-primary); font-size: 9px; }
.model-card-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; }
.model-card-stats > span { display: flex; flex-direction: column; gap: 2px; color: var(--wp-sub); font-size: 8px; }
.model-card-stats strong { color: var(--wp-text); font-size: 10px; }
.model-card-foot { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.model-card-foot small { color: var(--wp-sub); font-size: 9px; }
.model-route-layout { grid-template-columns: 1.15fr .85fr; }
.token-trend { display: flex; align-items: flex-end; justify-content: space-around; min-height: 250px; padding-top: 18px; }
.token-column { display: flex; align-items: center; flex-direction: column; width: 14%; }
.token-column > span { margin-bottom: 7px; color: var(--wp-gold-soft); font-family: "Bodoni MT", serif; }
.token-bar { display: flex; align-items: flex-end; width: 28px; height: 145px; border-radius: 8px 8px 2px 2px; background: rgba(148,163,184,.08); }
.token-bar i { display: block; width: 100%; border-radius: 8px 8px 2px 2px; background: linear-gradient(180deg, var(--wp-primary), var(--wp-gold)); box-shadow: 0 0 14px rgba(94,234,212,.22); }
.token-column strong { margin-top: 8px; font-size: 10px; }
.token-column small { color: var(--wp-sub); font-size: 8px; }
.remote-channel-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.remote-channel-card { display: flex; flex-direction: column; gap: 9px; padding: 12px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.04); }
.remote-channel-card.online { border-color: rgba(52,211,153,.38); }
.remote-channel-card.offline { opacity: .65; }
.channel-head { display: grid; grid-template-columns: 30px 1fr auto; align-items: center; gap: 8px; }
.channel-icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 9px; background: linear-gradient(135deg, rgba(94,234,212,.18), rgba(212,175,55,.16)); color: var(--wp-gold-soft); font-weight: 800; }
.channel-head > div { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
.channel-head small, .channel-last { overflow: hidden; color: var(--wp-sub); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.remote-console { display: grid; grid-template-columns: 1.2fr .8fr; gap: 18px; }
.remote-flow { display: flex; flex-direction: column; gap: 9px; }
.remote-flow-item { display: grid; grid-template-columns: 28px 1fr; gap: 9px; align-items: start; }
.remote-index { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 50%; background: var(--wp-primary); color: #04111b; font-size: 10px; font-weight: 800; }
.remote-flow-item.回传 .remote-index { background: var(--wp-success); }
.remote-flow-item.执行 .remote-index { background: var(--wp-gold-soft); }
.remote-flow-card { padding: 10px 12px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(148,163,184,.04); }
.remote-flow-card > div { display: flex; align-items: center; gap: 8px; }
.remote-flow-card > div span:last-child { margin-left: auto; color: var(--wp-sub); font-size: 9px; }
.remote-flow-card p { margin: 7px 0 0; color: var(--wp-sub); font-size: 10px; line-height: 1.55; }
.remote-command-box { display: flex; flex-direction: column; gap: 10px; padding: 16px; border: 1px solid rgba(212,175,55,.38); border-radius: 14px; background: radial-gradient(circle at 85% 10%, rgba(212,175,55,.14), transparent 45%), rgba(148,163,184,.035); }
.remote-kicker { color: var(--wp-primary); font-size: 9px; letter-spacing: .16em; }
.remote-command-box h3 { margin: 0; font-family: "Bodoni MT", serif; }
.remote-actions { display: flex; gap: 8px; }
.remote-actions .el-button { flex: 1; }
@media (max-width: 1200px) { .managed-model-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .remote-channel-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .model-route-layout, .remote-console { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .managed-model-grid, .remote-channel-grid { grid-template-columns: 1fr; } .model-card-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>

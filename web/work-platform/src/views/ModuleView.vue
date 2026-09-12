<template>
  <div class="page module-page">
    <div class="hero-command compact-hero">
      <div class="hero-kicker">{{ module.group }} / {{ module.phase }}</div>
      <h2 class="hero-title">{{ module.title }}</h2>
      <p class="hero-desc">{{ module.description }} · {{ module.status }}</p>
    </div>
    <el-alert
      v-if="module.status === 'prototype'"
      title="当前为 Vue 3 + Element Plus 展示版，业务数据仍使用 MockDataSource。"
      type="info"
      :closable="false"
      show-icon
    />

    <!-- 生命体征 -->
    <template v-if="moduleId === 'vitals'">
      <div class="metric-grid">
        <el-card v-for="item in vitalSigns" :key="item.key" class="section-card metric-card" shadow="never">
          <div class="metric-label">{{ item.label }}</div>
          <div class="metric-value">{{ item.value }}<span class="metric-unit">{{ item.unit }}</span></div>
          <div class="metric-trend">{{ item.trend }} · {{ item.status }}</div>
        </el-card>
      </div>
      <el-card class="section-card" shadow="never">
        <template #header><strong>器官健康报告</strong><el-button text type="primary" class="header-action">导出 PDF</el-button></template>
        <el-table :data="organs" stripe>
          <el-table-column prop="organ" label="器官" width="100" />
          <el-table-column prop="score" label="评分" width="100" />
          <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.score >= 90 ? 'success' : row.score >= 75 ? 'warning' : 'danger'">{{ row.state }}</el-tag></template></el-table-column>
          <el-table-column prop="note" label="说明" />
        </el-table>
      </el-card>
    </template>

    <!-- 决策沙盘 -->
    <template v-else-if="moduleId === 'brain'">
      <div class="metric-grid">
        <el-card class="section-card metric-card" shadow="never"><div class="metric-label">决策置信度</div><div class="metric-value">93<span class="metric-unit">%</span></div></el-card>
        <el-card class="section-card metric-card" shadow="never"><div class="metric-label">审计记录</div><div class="metric-value">365<span class="metric-unit">天</span></div></el-card>
        <el-card class="section-card metric-card" shadow="never"><div class="metric-label">回放延迟</div><div class="metric-value">420<span class="metric-unit">ms</span></div></el-card>
        <el-card class="section-card metric-card" shadow="never"><div class="metric-label">来源覆盖</div><div class="metric-value">12<span class="metric-unit">条</span></div></el-card>
      </div>
      <el-card class="section-card" shadow="never">
        <template #header><strong>思考链回放</strong><span class="header-meta">decision_id: DEC-20260912-0042</span></template>
        <el-collapse>
          <el-collapse-item v-for="step in brainChain" :key="step.step" :title="`${step.step} · ${step.model} · ${step.latency} · 置信度 ${step.confidence}`">
            <div class="chain-io">{{ step.io }}</div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      <div class="metric-grid">
        <el-card class="section-card metric-card" shadow="never"><div class="metric-label">合规审计</div><div class="metric-value">PASS</div><div class="metric-trend">AuditLog 可回放</div></el-card>
        <el-card class="section-card metric-card" shadow="never"><div class="metric-label">归因溯源</div><div class="metric-value">12</div><div class="metric-trend">来源引用</div></el-card>
      </div>
    </template>

    <!-- 五感矩阵 -->
    <template v-else-if="moduleId === 'senses'">
      <div class="sense-grid">
        <el-card v-for="item in senses" :key="item.name" class="section-card sense-card" shadow="never">
          <div class="sense-top"><el-icon size="24"><component :is="iconMap[item.icon]" /></el-icon><el-tag :type="item.tone">{{ item.state }}</el-tag></div>
          <h3>{{ item.name }}</h3>
          <div class="sense-value">{{ item.count }}<span>条</span></div>
          <el-progress :percentage="item.quality" :status="item.quality >= 90 ? 'success' : 'warning'" />
          <div class="metric-trend">{{ item.recent }}</div>
        </el-card>
      </div>
    </template>

    <!-- 进化视图 -->
    <template v-else-if="moduleId === 'evolution'">
      <div class="metric-grid">
        <el-card v-for="item in evolution.metrics" :key="item.label" class="section-card metric-card" shadow="never">
          <div class="metric-label">{{ item.label }}</div><div class="metric-value">{{ item.value }}<span class="metric-unit">{{ item.unit }}</span></div>
        </el-card>
      </div>
      <el-card class="section-card" shadow="never">
        <template #header><strong>知识自愈记录</strong></template>
        <el-table :data="evolution.healings" stripe>
          <el-table-column prop="doc" label="文档" min-width="220" />
          <el-table-column prop="issue" label="问题" width="140" />
          <el-table-column prop="action" label="清除方式" min-width="180" />
          <el-table-column prop="recurrence" label="是否复发" width="100" />
        </el-table>
      </el-card>
    </template>

    <!-- 协作总线 -->
    <template v-else-if="moduleId === 'collab'">
      <el-card class="section-card" shadow="never">
        <template #header><strong>协作域 {{ collaboration.domain_id }}</strong><span class="header-meta">{{ collaboration.mode }} · task {{ collaboration.task_id }}</span></template>
        <div class="agent-grid">
          <div v-for="agent in collaboration.agents" :key="agent.name" class="agent-card">
            <div><strong>{{ agent.name }}</strong><span>{{ agent.role }}</span></div>
            <el-progress type="dashboard" :percentage="agent.progress" :width="78" />
          </div>
        </div>
      </el-card>
      <div class="two-column">
        <el-card class="section-card" shadow="never">
          <template #header><strong>MC-P 消息流</strong></template>
          <el-timeline>
            <el-timeline-item v-for="message in collaboration.messages" :key="message.time" :timestamp="message.time" :type="message.type === 'result' ? 'success' : message.type === 'negotiate' ? 'warning' : 'primary'">
              <el-tag size="small">{{ message.type }}</el-tag> {{ message.text }}
            </el-timeline-item>
          </el-timeline>
        </el-card>
        <el-card class="section-card" shadow="never">
          <template #header><strong>共享工件层</strong></template>
          <el-table :data="collaboration.artifacts" size="small">
            <el-table-column prop="name" label="工件" />
            <el-table-column prop="source" label="来源" />
            <el-table-column prop="state" label="状态" width="90" />
          </el-table>
        </el-card>
      </div>
      <el-card class="section-card" shadow="never">
        <template #header><strong>验收门</strong></template>
        <div class="gate-row"><el-tag v-for="gate in collaboration.gates" :key="gate.name" :type="gate.state === 'PENDING' ? 'warning' : 'success'">{{ gate.name }} · {{ gate.state }}</el-tag></div>
      </el-card>
    </template>

    <!-- 专家团队 -->
    <template v-else-if="moduleId === 'experts'">
      <div class="card-grid">
        <el-card v-for="expert in experts" :key="expert.expert_id" class="section-card expert-card" shadow="never">
          <div class="card-heading"><strong>{{ expert.name }}</strong><el-tag :type="expert.state === 'active' ? 'success' : 'warning'">{{ expert.state }}</el-tag></div>
          <p>{{ expert.domain }} · {{ expert.persona }}</p>
          <div class="tag-line"><el-tag v-for="tool in expert.tools" :key="tool" size="small" type="info">{{ tool }}</el-tag></div>
          <div class="methodology">{{ expert.methodology }}</div>
          <el-button text type="primary">查看输出 Schema</el-button>
        </el-card>
      </div>
    </template>

    <!-- 技能市场 -->
    <template v-else-if="moduleId === 'skills'">
      <div class="card-grid">
        <el-card v-for="skill in skills" :key="skill.skill_id" class="section-card skill-card" shadow="never">
          <div class="card-heading"><strong>{{ skill.name }}</strong><el-tag>{{ skill.category }}</el-tag></div>
          <div class="skill-meta">v{{ skill.version }} · 安装 {{ skill.installs }} · 评分 {{ skill.rating }}</div>
          <el-button type="primary" :disabled="skill.state === 'installed'" @click="installSkill(skill)">{{ skill.state === 'installed' ? '已安装' : '一键安装' }}</el-button>
        </el-card>
      </div>
    </template>

    <!-- 连接器 -->
    <template v-else-if="moduleId === 'connectors'">
      <el-card class="section-card" shadow="never">
        <template #header><strong>MCP 连接器管理</strong></template>
        <el-table :data="connectors" stripe>
          <el-table-column prop="name" label="连接器" />
          <el-table-column prop="protocol" label="协议" width="90" />
          <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="row.state === 'online' ? 'success' : row.state === 'pending_auth' ? 'warning' : 'danger'">{{ row.state }}</el-tag></template></el-table-column>
          <el-table-column prop="latency" label="延迟(ms)" width="110" />
          <el-table-column prop="calls" label="调用量" width="100" />
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button v-if="row.state === 'pending_auth'" size="small" type="primary" @click="authorizeConnector(row)">授权</el-button><el-switch v-else v-model="row.enabled" active-text="启用" /></template></el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 自动化 -->
    <template v-else-if="moduleId === 'automation'">
      <el-card class="section-card" shadow="never">
        <template #header><strong>定时任务</strong><el-button type="primary" class="header-action">新建自动化</el-button></template>
        <el-table :data="automations" stripe>
          <el-table-column prop="auto_name" label="任务" min-width="180" />
          <el-table-column prop="cron" label="Cron" width="140" />
          <el-table-column prop="action" label="动作" min-width="180" />
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.state === 'active' ? 'success' : row.state === 'failed' ? 'danger' : 'info'">{{ row.state }}</el-tag></template></el-table-column>
          <el-table-column prop="next_run" label="下次运行" width="120" />
          <el-table-column prop="push" label="推送" width="130" />
        </el-table>
      </el-card>
    </template>

    <!-- 灵感案例 -->
    <template v-else-if="moduleId === 'cases'">
      <div class="card-grid">
        <el-card v-for="item in cases" :key="item.case_id" class="section-card case-card" shadow="never">
          <div class="card-heading"><strong>{{ item.title }}</strong><el-tag>{{ item.category }}</el-tag></div>
          <div class="metric-trend">已复用 {{ item.reuse_count }} 次</div>
          <div class="tag-line"><el-tag v-for="expert in item.experts" :key="expert" size="small" type="info">{{ expert }}</el-tag></div>
          <el-button type="primary" @click="reuseCase(item)">做同款</el-button>
        </el-card>
      </div>
    </template>

    <!-- 审批 -->
    <template v-else-if="moduleId === 'approvals'">
      <el-card class="section-card" shadow="never">
        <template #header><strong>免疫审批队列</strong><span class="header-meta">L3 必审 · L4 双人复核</span></template>
        <el-table :data="approvals" stripe>
          <el-table-column prop="approval_id" label="审批 ID" width="110" />
          <el-table-column prop="action" label="动作" min-width="180" />
          <el-table-column label="风险等级" width="110"><template #default="{ row }"><el-tag :type="row.risk_level === 'L4' ? 'danger' : row.risk_level === 'L3' ? 'warning' : 'info'">{{ row.risk_level }}</el-tag></template></el-table-column>
          <el-table-column prop="applicant" label="发起方" width="130" />
          <el-table-column prop="context" label="上下文" min-width="180" />
          <el-table-column label="操作" width="180"><template #default="{ row }"><el-button size="small" type="success" @click="decide(row, 'approved')">通过</el-button><el-button size="small" type="danger" @click="decide(row, 'rejected')">拒绝</el-button></template></el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Checked, Document, Microphone, Promotion, View } from '@element-plus/icons-vue'
import {
  automations, approvals, brainChain, cases, collaboration, connectors, evolution,
  experts, organs, senses, skills, vitalSigns,
} from '../api/mock'
import { moduleMap } from '../config/modules'
import type { ModuleId } from '../types'

const route = useRoute()
const moduleId = computed(() => (route.meta.module as ModuleId) || 'overview')
const module = computed(() => moduleMap[moduleId.value] || moduleMap.overview)
const iconMap: Record<string, unknown> = { View, Microphone, Document, Promotion, Checked }

async function installSkill(skill: any) {
  skill.state = 'installed'
  ElMessage.success(`${skill.name} 安装成功`)
}

async function authorizeConnector(row: any) {
  row.state = 'online'
  ElMessage.success(`${row.name} 授权成功`)
}

async function reuseCase(item: any) {
  await ElMessageBox.confirm(`确认基于「${item.title}」创建任务？`, '做同款', { type: 'info' })
  item.reuse_count += 1
  ElMessage.success('任务已创建')
}

async function decide(row: any, decision: string) {
  await ElMessageBox.confirm(`确认${decision === 'approved' ? '通过' : '拒绝'}「${row.action}」？`, '审批确认', { type: decision === 'approved' ? 'success' : 'warning' })
  row.state = decision
  ElMessage.success(`已${decision === 'approved' ? '通过' : '拒绝'}`)
}
</script>

<style scoped>
.header-action { float: right; }
.header-meta { float: right; color: var(--wp-sub); font-size: 12px; }
.chain-io { color: var(--wp-sub); padding: 8px 0; }
.sense-grid, .card-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.sense-top, .card-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.sense-card h3 { margin: 14px 0 6px; }
.sense-value { margin-bottom: 10px; font-family: "Bodoni MT", "Times New Roman", serif; font-size: 28px; }
.sense-value span { margin-left: 4px; color: var(--wp-sub); font-size: 12px; }
.agent-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.agent-card { padding: 14px; border: 1px solid var(--wp-border); border-radius: 14px; background: rgba(148,163,184,.06); }
.agent-card > div { display: flex; flex-direction: column; margin-bottom: 8px; }
.agent-card span { color: var(--wp-sub); font-size: 11px; }
.gate-row, .tag-line { display: flex; gap: 8px; flex-wrap: wrap; }
.expert-card p, .methodology { color: var(--wp-sub); }
.skill-meta { margin: 12px 0 16px; color: var(--wp-sub); font-size: 12px; }
@media (max-width: 1100px) { .sense-grid, .card-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .sense-grid, .card-grid, .agent-grid { grid-template-columns: 1fr; } }
</style>


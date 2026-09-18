<template>
  <div class="page overview-cockpit">
    <div class="hero-command">
      <div class="hero-kicker">LIFEFORM COMMAND COCKPIT</div>
      <h2 class="hero-title">生命体总览</h2>
      <p class="hero-desc">团队协作、模型调用、运行状态与优化建议 · {{ dataProvider.mode.toUpperCase() }} 数据源 · {{ online ? '实时同步' : '离线降级' }}</p>
      <div class="hero-telemetry">
        <span><i class="live-dot" /> WS 在线</span>
        <span>刷新 {{ refreshCount }}</span>
        <span>最后更新 {{ workflow.last_update }}</span>
        <span class="gold">AURELION CONTROL</span>
      </div>
    </div>

    <el-alert v-if="error" title="总览数据加载失败，当前显示最近一次可用快照" type="warning" :closable="false" show-icon>
      <template #default><el-button size="small" text type="primary" @click="loadOverview">重试</el-button></template>
    </el-alert>

    <el-skeleton v-if="loading" :rows="8" animated />
    <template v-else>
      <section v-if="observability" aria-label="观测区实时摘要" class="observability-strip">
        <div class="section-heading">
          <span>OPS 观测区实时摘要 · 真实数据</span>
          <el-button text type="primary" @click="router.push('/middleware')">进入中间件监控 <el-icon><ArrowRight /></el-icon></el-button>
        </div>
        <div class="observability-grid">
          <button type="button" class="observability-card" @click="router.push('/middleware')">
            <span>中间件端口可达</span>
            <strong>{{ observability.middleware.up }}<small>/ {{ observability.middleware.total }}</small></strong>
            <em>探针 {{ observability.middleware.probe_mode.toUpperCase() }} · 启动中 {{ observability.middleware.pending }}</em>
          </button>
          <button type="button" class="observability-card" @click="router.push('/tracing')">
            <span>Jaeger 注册服务</span>
            <strong>{{ observability.tracing.services }}<small> 个</small></strong>
            <em>{{ observability.tracing.enabled ? `采样 Span ${observability.tracing.spans_sampled}` : 'Jaeger 未启用' }}</em>
          </button>
          <button type="button" class="observability-card" :class="{ warn: observability.tracing.recent_errors > 0 }" @click="router.push('/tracing')">
            <span>最近异常链路</span>
            <strong>{{ observability.tracing.recent_errors }}<small> 条</small></strong>
            <em>P99 {{ observability.tracing.p99_ms }}ms（采样分位）</em>
          </button>
          <div class="observability-card static">
            <span>待接入数据域</span>
            <strong>{{ overviewGaps.length }}<small> 项</small></strong>
            <em>其余总览数据仍为 Mock，见顶栏降级提示</em>
          </div>
        </div>
      </section>

      <section aria-label="生命体征摘要">
        <div class="section-heading"><span>D1 生命体征摘要</span><el-button text type="primary" @click="router.push('/vitals')">进入体征舱 <el-icon><ArrowRight /></el-icon></el-button></div>
        <div class="vital-summary-grid">
          <button v-for="item in vitals" :key="item.key" type="button" class="vital-summary-card" :class="item.status" @click="router.push('/vitals')">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}<small>{{ item.unit }}</small></strong>
            <em>{{ item.trend }} · {{ item.threshold }}</em>
          </button>
        </div>
      </section>

      <div class="metric-grid">
        <button v-for="metric in metrics" :key="metric.label" type="button" class="section-card metric-card metric-button" @click="openMetric(metric.label)">
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ metric.value }}<span class="metric-unit">{{ metric.unit }}</span></div>
          <div class="metric-trend">{{ metric.trend }}</div>
        </button>
      </div>

      <el-card class="section-card today-summary-card" shadow="never">
        <div class="today-summary-head">
          <div>
            <span class="today-kicker">DAILY BRIEF · {{ todaySummary.date }}</span>
            <h3>今日摘要</h3>
            <p>{{ todaySummary.headline }}</p>
          </div>
          <div class="today-index">
            <strong>{{ todaySummary.running_index }}</strong>
            <span>今日运行指数</span>
            <em>+6 较昨日</em>
          </div>
        </div>
        <div class="today-summary-body">
          <div class="today-metrics">
            <button v-for="metric in todaySummary.metrics" :key="metric.label" type="button" class="today-metric" :class="metric.tone" @click="openDetail(`今日指标 · ${metric.label}`, metric)">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}<small>{{ metric.unit }}</small></strong>
              <em>{{ metric.trend }}</em>
            </button>
          </div>
          <div class="today-events">
            <div v-for="event in todaySummary.events" :key="event.time" class="today-event" :class="event.type">
              <span class="today-event-time">{{ event.time }}</span>
              <i />
              <div><strong>{{ event.title }}</strong><p>{{ event.detail }}</p></div>
            </div>
          </div>
          <div class="today-attention">
            <div class="today-attention-title"><strong>待关注</strong><span>{{ todaySummary.attention.length }} 项</span></div>
            <button v-for="(item, index) in todaySummary.attention" :key="item" type="button" @click="openDetail('今日待关注', item)">
              <span>{{ String(index + 1).padStart(2, '0') }}</span><p>{{ item }}</p>
            </button>
          </div>
        </div>
      </el-card>
      <div class="cockpit-grid">
        <el-card class="section-card model-monitor-card" shadow="never">
          <template #header>
            <div class="card-title-row"><strong>大模型调用监控</strong><span class="header-meta">滚动 45 分钟</span></div>
          </template>
          <div class="monitor-metrics">
            <div><span>调用 / min</span><strong>{{ latestCall.calls }}</strong><em :class="{ up: callDelta >= 0 }">{{ callDelta >= 0 ? '+' : '' }}{{ callDelta }}%</em></div>
            <div><span>Token / s</span><strong>{{ latestCall.tokens.toFixed(1) }}k</strong><em>L2 82%</em></div>
            <div><span>P95 延迟</span><strong>{{ latestCall.latency }}ms</strong><em :class="{ warn: latestCall.latency > 300 }">SLO 500ms</em></div>
            <div><span>成本 / h</span><strong>¥{{ latestCall.cost.toFixed(2) }}</strong><em class="gold">预算内</em></div>
          </div>
          <svg class="model-chart" viewBox="0 0 620 230" preserveAspectRatio="none" aria-label="大模型调用趋势图">
            <defs>
              <linearGradient id="callArea" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="rgba(94,234,212,.42)" />
                <stop offset="100%" stop-color="rgba(94,234,212,0)" />
              </linearGradient>
              <linearGradient id="latencyLine" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0%" stop-color="#f4d58d" />
                <stop offset="100%" stop-color="#d4af37" />
              </linearGradient>
            </defs>
            <line v-for="line in chartGridLines" :key="line" x1="28" :y1="line" x2="596" :y2="line" class="chart-grid-line" />
            <path :d="callAreaPath" fill="url(#callArea)" />
            <path :d="callLinePath" class="chart-line call-line" />
            <path :d="latencyLinePath" class="chart-line latency-line" />
            <circle :cx="latestPoint.x" :cy="latestPoint.callY" r="4" class="chart-point call-point" />
            <circle :cx="latestPoint.x" :cy="latestPoint.latencyY" r="4" class="chart-point latency-point" />
            <text x="30" y="16" class="chart-label">调用量 / req·min</text>
            <text x="500" y="16" class="chart-label latency-label">P95 / ms</text>
            <text v-for="point in chartXLabels" :key="point.time" :x="point.x" y="222" class="chart-axis">{{ point.time }}</text>
          </svg>
          <div class="chart-legend"><span class="call"><i />调用量</span><span class="latency"><i />P95 延迟</span><span>峰值 {{ peakCalls }} req/min</span></div>
        </el-card>
      </div>

      <div class="two-column cockpit-bottom">
        <el-card class="section-card model-status-card" shadow="never">
          <template #header><strong>大模型工作状态</strong><span class="header-meta">Router → Plan → RAG → Generate → Judge</span></template>
          <div class="model-status-layout">
            <div class="model-donut" :style="donutStyle">
              <div class="donut-core"><strong>{{ activeModelCount }}</strong><span>活跃节点</span></div>
            </div>
            <div class="model-status-legend">
              <div><i class="healthy" /><span>健康</span><strong>{{ modelStateCount.healthy }}</strong></div>
              <div><i class="busy" /><span>高负载</span><strong>{{ modelStateCount.busy }}</strong></div>
              <div><i class="degraded" /><span>降级</span><strong>{{ modelStateCount.degraded }}</strong></div>
              <div><i class="queue" /><span>队列任务</span><strong>{{ totalQueue }}</strong></div>
            </div>
          </div>
          <div class="model-node-grid">
            <button v-for="node in modelRuntime" :key="node.node_id" type="button" class="model-node" :class="node.state" @click="openDetail(`${node.name} 运行详情`, node)">
              <div class="node-head"><span>{{ node.role }}</span><el-tag size="small" :type="modelTagType(node.state)">{{ modelStateLabel(node.state) }}</el-tag></div>
              <strong>{{ node.name }}</strong><small>{{ node.model }}</small>
              <div class="node-load"><span>负载 {{ node.load }}%</span><div><i :style="{ width: `${node.load}%` }" /></div></div>
              <div class="node-meta"><span>{{ node.throughput }} req/min</span><span>{{ node.latency }}ms</span><span>Q{{ node.queue }}</span></div>
            </button>
          </div>
        </el-card>

        <el-card class="section-card suggestion-card" shadow="never">
          <template #header><strong>建议优化功能</strong><span class="header-meta">基于调用监控 / 协作 / 审计生成</span></template>
          <div class="suggestion-list">
            <article v-for="item in suggestions" :key="item.suggestion_id" class="suggestion-item" :class="[item.priority, item.status]">
              <div class="suggestion-head"><div><el-tag size="small" effect="plain">{{ item.category }}</el-tag><span class="priority">{{ priorityLabel(item.priority) }}</span></div><strong>{{ item.confidence }}%</strong></div>
              <h4>{{ item.title }}</h4>
              <p>{{ item.impact }}</p>
              <div class="suggestion-meta"><span>投入 {{ item.effort }}</span><span>{{ item.source }}</span></div>
              <div class="suggestion-actions">
                <div class="suggestion-btns">
                  <el-button v-if="item.status === 'pending' && !executingIds.has(item.suggestion_id)" size="small" type="primary" @click="applySuggestion(item)">应用优化</el-button>
                  <el-tag v-else-if="executingIds.has(item.suggestion_id)" type="warning">执行中</el-tag>
                  <el-tag v-else type="success">已应用</el-tag>
                  <el-button v-if="item.status === 'pending' && item.target" size="small" text type="warning" @click="goHandle(item)">去处理</el-button>
                  <el-button size="small" text type="primary" @click="openDetail(`${item.suggestion_id} 优化依据`, item)">查看依据</el-button>
                </div>
              </div>
            </article>
          </div>
        </el-card>
      </div>

      <el-card v-if="executions.length" class="section-card execution-card" shadow="never">
        <template #header><strong>优化执行队列</strong><span class="header-meta">建议应用后的自动化处理进度</span></template>
        <div class="execution-list">
          <article v-for="exec in executions" :key="exec.execution_id" class="execution-item" :class="exec.state">
            <div class="execution-head">
              <div class="execution-title">
                <el-tag size="small" :type="execTagType(exec.state)">{{ execStateLabel(exec.state) }}</el-tag>
                <strong>{{ exec.title }}</strong>
                <span class="execution-action">{{ exec.action }}</span>
              </div>
              <span class="execution-time">{{ exec.execution_id }} · {{ exec.started_at }}</span>
            </div>
            <div class="execution-steps">
              <div v-for="(step, index) in exec.steps" :key="step.name" class="execution-step" :class="step.state">
                <i>{{ step.state === 'done' ? '✓' : index + 1 }}</i><span>{{ step.name }}</span>
              </div>
            </div>
            <el-progress :percentage="Math.round(exec.progress)" :stroke-width="6" :show-text="false" :class="exec.state" />
            <div class="execution-log">
              <p v-for="(log, index) in exec.logs.slice(-3)" :key="index"><span>{{ log.time }}</span>{{ log.text }}</p>
            </div>
            <div v-if="exec.result" class="execution-result"><el-tag type="success" size="small">回执</el-tag><span>{{ exec.result }}</span></div>
          </article>
        </div>
      </el-card>

      <div class="two-column">
        <el-card class="section-card metric-card" shadow="never">
          <template #header><strong>服务健康</strong><span class="header-meta">Gateway → 能力层</span></template>
          <el-table :data="services" size="small">
            <el-table-column prop="name" label="服务" />
            <el-table-column prop="port" label="端口" width="90" />
            <el-table-column prop="latency" label="P95" width="90" />
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'UP' ? 'success' : 'danger'">{{ row.status }}</el-tag></template></el-table-column>
          </el-table>
        </el-card>

        <el-card class="section-card metric-card" shadow="never">
          <template #header><strong>生命体成长时间轴</strong><span class="header-meta">D7</span></template>
          <el-timeline>
            <el-timeline-item v-for="item in timeline" :key="item.phase" :timestamp="item.phase" :type="timelineType(item.status)">
              <button class="timeline-node" type="button" @click="openTimeline(item)"><strong>{{ item.title }}</strong><span>{{ item.desc }}</span></button>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </div>
    </template>

    <el-dialog v-model="detailVisible" :title="detailTitle" width="620px">
      <pre v-if="detailIsJson" class="command-pre">{{ prettyDetail }}</pre>
      <p v-else class="detail-text">{{ detailContent }}</p>
    </el-dialog>

    <el-dialog v-model="timelineVisible" :title="selectedTimeline?.title || '阶段详情'" width="520px">
      <div class="timeline-detail"><el-tag>{{ selectedTimeline?.phase }}</el-tag><p>{{ selectedTimeline?.desc }}</p><el-descriptions :column="1" border><el-descriptions-item label="阶段状态">{{ stageLabel(selectedTimeline?.status) }}</el-descriptions-item><el-descriptions-item label="平台交付">模块导航、实时驾驶舱、依赖服务与阶段验收记录。</el-descriptions-item><el-descriptions-item label="下一步">进入对应模块查看任务、协作或优化执行结果。</el-descriptions-item></el-descriptions></div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Checked, Refresh } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import {
  executionLogTemplates,
  growthTimeline as fallbackTimeline,
  metrics as fallbackMetrics,
  modelCallSeries as fallbackModelCalls,
  modelRuntimeNodes as fallbackModelRuntime,
  optimizationSuggestions as fallbackSuggestions,
  serviceHealth as fallbackServices,
  teamWorkflow as fallbackWorkflow,
  todaySummary as fallbackTodaySummary,
  vitalSigns as fallbackVitals,
} from '../api/mock'
import type {
  MetricCard, ModelCallPoint, ModelRuntimeNode, OptimizationSuggestion, OverviewObservability,
  OverviewWorkflow, OverviewWorkflowAgent, SuggestionExecution, TodaySummary, VitalSign,
} from '../types'

type TimelineItem = { phase: string; title: string; desc: string; status: string }
type ServiceItem = { name: string; port: number; status: string; latency?: string }

const router = useRouter()
const loading = ref(true)
const error = ref('')
const online = ref(navigator.onLine)
const refreshCount = ref(0)
const todaySummary = ref<TodaySummary>(JSON.parse(JSON.stringify(fallbackTodaySummary)))
const metrics = ref<MetricCard[]>(fallbackMetrics)
const services = ref<ServiceItem[]>(fallbackServices)
const vitals = ref<VitalSign[]>(fallbackVitals.map(item => ({ ...item })))
const timeline = ref<TimelineItem[]>(fallbackTimeline)
const workflow = ref<OverviewWorkflow>(JSON.parse(JSON.stringify(fallbackWorkflow)))
const modelCalls = ref<ModelCallPoint[]>(fallbackModelCalls.map(item => ({ ...item })))
const modelRuntime = ref<ModelRuntimeNode[]>(fallbackModelRuntime.map(item => ({ ...item })))
const suggestions = ref<OptimizationSuggestion[]>(fallbackSuggestions.map(item => ({ ...item })))
const executions = ref<SuggestionExecution[]>([])
let execTimer: number | undefined
const executingIds = computed(() => new Set(executions.value.filter(item => item.state === 'queued' || item.state === 'running').map(item => item.suggestion_id)))
const detailVisible = ref(false)
const detailTitle = ref('')
const detailPayload = ref<unknown>(null)
const detailIsJson = ref(false)
const detailContent = ref('')
const timelineVisible = ref(false)
const selectedTimeline = ref<TimelineItem | null>(null)
let liveTimer: number | undefined

const chartLeft = 28
const chartRight = 596
const chartTop = 30
const chartBottom = 190
const chartGridLines = [30, 70, 110, 150, 190]
const chartXLabels = computed(() => modelCalls.value.map((item, index) => ({ time: item.time, x: chartX(index, modelCalls.value.length) })))
const maxCalls = computed(() => Math.max(...modelCalls.value.map(item => item.calls), 10) * 1.18)
const maxLatency = computed(() => Math.max(...modelCalls.value.map(item => item.latency), 100) * 1.18)
const callLinePath = computed(() => linePath('calls', maxCalls.value))
const latencyLinePath = computed(() => linePath('latency', maxLatency.value))
const callAreaPath = computed(() => {
  if (!modelCalls.value.length) return ''
  const lastX = chartX(modelCalls.value.length - 1, modelCalls.value.length)
  const firstX = chartX(0, modelCalls.value.length)
  return `${callLinePath.value} L ${lastX} ${chartBottom} L ${firstX} ${chartBottom} Z`
})
const latestCall = computed<ModelCallPoint>(() => modelCalls.value[modelCalls.value.length - 1] || { time: '--', calls: 0, tokens: 0, latency: 0, cost: 0 })
const callDelta = computed(() => {
  const previous = modelCalls.value[modelCalls.value.length - 2]
  if (!previous) return 0
  return Math.round(((latestCall.value.calls - previous.calls) / previous.calls) * 100)
})
const peakCalls = computed(() => Math.max(...modelCalls.value.map(item => item.calls)))
const latestPoint = computed(() => {
  const index = Math.max(0, modelCalls.value.length - 1)
  return {
    x: chartX(index, modelCalls.value.length),
    callY: chartY(latestCall.value.calls, maxCalls.value),
    latencyY: chartY(latestCall.value.latency, maxLatency.value),
  }
})
const activeModelCount = computed(() => modelRuntime.value.filter(item => item.state !== 'offline').length)
const totalQueue = computed(() => modelRuntime.value.reduce((sum, item) => sum + item.queue, 0))
const modelStateCount = computed(() => ({
  healthy: modelRuntime.value.filter(item => item.state === 'healthy').length,
  busy: modelRuntime.value.filter(item => item.state === 'busy').length,
  degraded: modelRuntime.value.filter(item => item.state === 'degraded').length,
  offline: modelRuntime.value.filter(item => item.state === 'offline').length,
}))
const donutStyle = computed(() => {
  const total = Math.max(1, modelRuntime.value.length)
  const healthyEnd = (modelStateCount.value.healthy / total) * 100
  const busyEnd = healthyEnd + (modelStateCount.value.busy / total) * 100
  const degradedEnd = busyEnd + (modelStateCount.value.degraded / total) * 100
  return {
    background: `conic-gradient(var(--wp-success) 0 ${healthyEnd}%, var(--wp-gold-soft) ${healthyEnd}% ${busyEnd}%, #f97316 ${busyEnd}% ${degradedEnd}%, rgba(148,163,184,.20) ${degradedEnd}% 100%)`,
  }
})
const prettyDetail = computed(() => pretty(detailPayload.value))

function chartX(index: number, total: number) {
  if (total <= 1) return chartLeft
  return chartLeft + index * ((chartRight - chartLeft) / (total - 1))
}

function chartY(value: number, max: number) {
  return chartBottom - (value / Math.max(max, 1)) * (chartBottom - chartTop)
}

function linePath(key: 'calls' | 'latency', max: number) {
  return modelCalls.value.map((item, index) => `${index === 0 ? 'M' : 'L'} ${chartX(index, modelCalls.value.length)} ${chartY(item[key], max)}`).join(' ')
}

const observability = ref<OverviewObservability | null>(null)
const overviewGaps = ref<string[]>([])

async function loadOverview() {
  loading.value = true
  error.value = ''
  try {
    const data = await dataProvider.getOverview() as {
      metrics?: MetricCard[]
      services?: ServiceItem[]
      vitals?: VitalSign[]
      timeline?: TimelineItem[]
      today_summary?: TodaySummary
      workflow?: OverviewWorkflow
      model_calls?: ModelCallPoint[]
      model_runtime?: ModelRuntimeNode[]
      optimization_suggestions?: OptimizationSuggestion[]
      observability?: OverviewObservability
      gaps?: string[]
    }
    metrics.value = data.metrics?.length ? data.metrics : fallbackMetrics
    services.value = data.services?.length ? data.services : fallbackServices
    vitals.value = data.vitals?.length ? data.vitals : fallbackVitals
    timeline.value = data.timeline?.length ? data.timeline : fallbackTimeline
    todaySummary.value = data.today_summary || JSON.parse(JSON.stringify(fallbackTodaySummary))
    workflow.value = data.workflow || JSON.parse(JSON.stringify(fallbackWorkflow))
    modelCalls.value = data.model_calls?.length ? data.model_calls : fallbackModelCalls.map(item => ({ ...item }))
    modelRuntime.value = data.model_runtime?.length ? data.model_runtime : fallbackModelRuntime.map(item => ({ ...item }))
    suggestions.value = data.optimization_suggestions?.length ? data.optimization_suggestions : fallbackSuggestions.map(item => ({ ...item }))
    observability.value = data.observability ?? null
    overviewGaps.value = data.gaps ?? []
  } catch {
    error.value = 'overview'
  } finally {
    loading.value = false
  }
}

function startLiveUpdates() {
  stopLiveUpdates()
  liveTimer = window.setInterval(() => {
    refreshCount.value += 1
    const phase = refreshCount.value
    const last = modelCalls.value[modelCalls.value.length - 1]
    const next: ModelCallPoint = {
      time: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      calls: Math.max(24, Math.round(last.calls + Math.sin(phase / 1.7) * 5 + 2)),
      tokens: Math.max(4, Number((last.tokens + Math.cos(phase / 2.1) * 0.5 + 0.2).toFixed(1))),
      latency: Math.max(120, Math.round(last.latency + Math.sin(phase / 2.3) * 8)),
      cost: Math.max(0.2, Number((last.cost + Math.cos(phase / 2.8) * 0.03 + 0.01).toFixed(2))),
    }
    modelCalls.value = [...modelCalls.value.slice(1), next]
    modelRuntime.value = modelRuntime.value.map((node, index) => {
      const delta = Math.round(Math.sin(phase / (2 + index)) * 3)
      const load = Math.min(96, Math.max(18, node.load + delta))
      const state: ModelRuntimeNode['state'] = load >= 85 ? 'busy' : node.state === 'offline' ? 'offline' : 'healthy'
      return { ...node, load, state, latency: Math.max(10, node.latency + Math.round(Math.cos(phase / (2.3 + index)) * 6)), queue: Math.max(0, node.queue + (load >= 80 ? 1 : -1)) }
    })
    workflow.value.last_update = '刚刚'
    workflow.value.progress = Math.min(96, Number((workflow.value.progress + 0.04).toFixed(2)))
    workflow.value.steps = workflow.value.steps.map(step => {
      if (step.state !== 'running' && step.state !== 'waiting') return step
      const nextProgress = step.state === 'running' ? Math.min(96, step.progress + 0.15) : Math.min(36, step.progress + 0.06)
      return { ...step, progress: Number(nextProgress.toFixed(2)) }
    })
    workflow.value.agents = workflow.value.agents.map((agent, index) => agent.state === 'done' ? agent : ({ ...agent, progress: Math.min(96, Number((agent.progress + 0.12 + index * 0.01).toFixed(2))) }))
  }, 2200)
}

function stopLiveUpdates() {
  if (liveTimer) window.clearInterval(liveTimer)
  liveTimer = undefined
}

function openMetric(label: string) {
  if (label.includes('任务')) router.push('/tasks')
  else if (label.includes('Agent')) router.push('/collab')
  else if (label.includes('知识')) router.push('/skills')
  else router.push('/overview')
}

function openDetail(title: string, payload: unknown) {
  detailTitle.value = title
  detailPayload.value = payload
  detailIsJson.value = typeof payload === 'object' && payload !== null
  detailContent.value = typeof payload === 'string' ? payload : ''
  detailVisible.value = true
}

function pretty(value: unknown) {
  if (value === null || value === undefined) return '--'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

async function applySuggestion(item: OptimizationSuggestion) {
  let execution: SuggestionExecution
  try {
    execution = await dataProvider.applySuggestion(item)
  } catch (error) {
    // `/suggestions/{id}/apply` 在契约中仍为 planned：失败不进队列，避免"看起来已执行"
    ElMessage.error((error as Error)?.message || '下发优化动作失败，请稍后再试')
    return
  }
  executions.value = [execution, ...executions.value]
  ElMessage.success(`${item.title} 已进入优化执行队列`)
  startExecLoop()
}

function execStamp() {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

function startExecLoop() {
  if (execTimer) return
  execTimer = window.setInterval(tickExecutions, 900)
}

function stopExecLoop() {
  if (execTimer) window.clearInterval(execTimer)
  execTimer = undefined
}

function tickExecutions() {
  if (!executingIds.value.size) {
    stopExecLoop()
    return
  }
  const finished: SuggestionExecution[] = []
  executions.value = executions.value.map(exec => {
    if (exec.state === 'done' || exec.state === 'failed') return exec
    const steps = exec.steps.map(step => ({ ...step }))
    const logs = [...exec.logs]
    const perStep = 100 / steps.length
    let progress = Math.min(100, exec.progress + 4 + Math.random() * 5)
    let index = steps.findIndex(step => step.state === 'running')
    if (index === -1) {
      index = Math.min(steps.length - 1, Math.floor(progress / perStep))
      steps[index].state = 'running'
      logs.push({ time: execStamp(), text: `开始执行：${steps[index].name}` })
    }
    while (progress >= (index + 1) * perStep - 0.01 && steps[index].state === 'running') {
      steps[index].state = 'done'
      logs.push({ time: execStamp(), text: executionLogTemplates[exec.category][index] || `${steps[index].name} 完成` })
      index += 1
      if (index < steps.length) {
        steps[index].state = 'running'
        logs.push({ time: execStamp(), text: `开始执行：${steps[index].name}` })
      } else {
        break
      }
    }
    const done = steps.every(step => step.state === 'done')
    if (done) {
      progress = 100
      finished.push(exec)
    }
    return {
      ...exec,
      state: done ? 'done' as const : 'running' as const,
      progress,
      steps,
      logs,
      finished_at: done ? execStamp() : null,
      result: done ? (suggestions.value.find(entry => entry.suggestion_id === exec.suggestion_id)?.impact || `${exec.action} 已生效`) : null,
    }
  })
  finished.forEach(exec => {
    const suggestion = suggestions.value.find(entry => entry.suggestion_id === exec.suggestion_id)
    if (suggestion) suggestion.status = 'applied'
    ElMessage.success(`「${exec.title}」优化执行完成`)
  })
}

function execStateLabel(state: SuggestionExecution['state']) {
  return { queued: '排队中', running: '执行中', done: '已完成', failed: '失败' }[state]
}

function execTagType(state: SuggestionExecution['state']) {
  return { queued: 'info', running: 'primary', done: 'success', failed: 'danger' }[state] as 'info' | 'primary' | 'success' | 'danger'
}

function goHandle(item: OptimizationSuggestion) {
  if (!item.target) return
  router.push(item.target)
  ElMessage.info(`已跳转到「${item.title}」的处理模块`)
}

function priorityLabel(priority: OptimizationSuggestion['priority']) {
  return { high: '高优先级', medium: '中优先级', low: '低优先级' }[priority]
}

function stepNode(stepId: string) {
  return workflow.value.steps.find(step => step.step_id === stepId)
}

function agentStateLabel(state: OverviewWorkflowAgent['state']) {
  return { running: '执行中', done: '已完成', waiting: '等待中' }[state]
}
function modelTagType(state: ModelRuntimeNode['state']) {
  return { healthy: 'success', busy: 'warning', degraded: 'danger', offline: 'info' }[state] as 'success' | 'warning' | 'danger' | 'info'
}

function modelStateLabel(state: ModelRuntimeNode['state']) {
  return { healthy: '健康', busy: '高负载', degraded: '降级', offline: '离线' }[state]
}

function openTimeline(item: TimelineItem) {
  selectedTimeline.value = item
  timelineVisible.value = true
}

function timelineType(status?: string) {
  return status === 'done' ? 'success' : status === 'next' ? 'primary' : 'info'
}

function stageLabel(status?: string) {
  return ({ done: '已完成', next: '下一阶段', planned: '规划中' } as Record<string, string>)[status || ''] || '规划中'
}

onMounted(async () => {
  await loadOverview()
  startLiveUpdates()
})

onUnmounted(() => {
  stopLiveUpdates()
  stopExecLoop()
})
</script>

<style scoped>
.overview-cockpit { --cockpit-gap: 18px; }
.hero-telemetry { display: flex; gap: 18px; margin-top: 18px; font-size: 11px; color: var(--wp-sub); letter-spacing: .06em; flex-wrap: wrap; }
.hero-telemetry span { display: inline-flex; align-items: center; gap: 6px; }
.hero-telemetry .gold { margin-left: auto; color: var(--wp-gold-soft); letter-spacing: .16em; }
.live-dot, .live-badge i { width: 7px; height: 7px; border-radius: 50%; background: var(--wp-success); box-shadow: 0 0 12px var(--wp-success); animation: breathe 1.8s ease-in-out infinite; }
.section-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; color: var(--wp-sub); font-size: 12px; letter-spacing: .12em; }
.vital-summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.vital-summary-card { position: relative; display: flex; flex-direction: column; gap: 6px; padding: 14px; border: 1px solid var(--wp-border); border-radius: 14px; background: var(--wp-card); color: var(--wp-text); cursor: pointer; text-align: left; overflow: hidden; }
.vital-summary-card::after { content: ""; position: absolute; inset: auto 0 0; height: 2px; background: var(--wp-success); }
.vital-summary-card.warning::after { background: #f59e0b; }
.vital-summary-card.critical::after { background: var(--wp-danger); }
.vital-summary-card span { color: var(--wp-sub); font-size: 11px; }
.vital-summary-card strong { font-family: "Bodoni MT", "Times New Roman", serif; font-size: 24px; }
.vital-summary-card small { margin-left: 4px; color: var(--wp-sub); font-size: 11px; }
.vital-summary-card em { color: var(--wp-sub); font-size: 10px; font-style: normal; }
.metric-button { display: block; width: 100%; min-height: 132px; padding: 20px; border-radius: 16px; font: inherit; color: var(--wp-text); cursor: pointer; text-align: left; }
.metric-button > * { position: relative; z-index: 1; }
.metric-button:hover, .workflow-step:hover, .model-node:hover { transform: translateY(-2px); border-color: var(--wp-primary) !important; }
.header-meta { color: var(--wp-sub); font-size: 11px; }
.card-title-row { display: flex; align-items: center; gap: 10px; }
.live-badge { display: inline-flex; align-items: center; gap: 5px; padding: 2px 8px; border: 1px solid rgba(52,211,153,.35); border-radius: 999px; color: var(--wp-success); font-size: 9px; letter-spacing: .16em; }
.cockpit-grid { display: grid; grid-template-columns: 1fr; gap: var(--cockpit-gap); align-items: stretch; }
.workflow-card, .model-monitor-card { min-height: 510px; }
.workflow-summary { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; margin: 4px 0 18px; }
.workflow-summary > div { display: flex; flex-direction: column; gap: 3px; }
.workflow-summary span { color: var(--wp-sub); font-size: 10px; letter-spacing: .1em; }
.workflow-summary strong { font-family: "Bodoni MT", serif; font-size: 25px; }
.workflow-eta { text-align: right; }
.workflow-rail { position: relative; display: grid; grid-template-columns: repeat(7, minmax(92px, 1fr)); gap: 8px; overflow-x: auto; padding: 10px 2px 16px; }
.workflow-rail::before { content: ""; position: absolute; left: 5%; right: 5%; top: 27px; height: 1px; background: linear-gradient(90deg, rgba(52,211,153,.55), rgba(94,234,212,.5), rgba(212,175,55,.45), rgba(148,163,184,.15)); }
.workflow-step { position: relative; z-index: 1; display: flex; align-items: center; flex-direction: column; gap: 6px; min-width: 92px; padding: 0 4px; border: 1px solid transparent; border-radius: 12px; background: transparent; color: var(--wp-text); cursor: pointer; text-align: center; transition: .2s ease; }
.workflow-step:hover { background: rgba(94,234,212,.05); }
.step-index { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid var(--wp-border); border-radius: 50%; background: var(--wp-card-solid); color: var(--wp-sub); box-shadow: 0 0 0 5px var(--wp-bg); }
.workflow-step.done .step-index { border-color: rgba(52,211,153,.6); color: var(--wp-success); }
.workflow-step.running .step-index { border-color: rgba(94,234,212,.8); color: var(--wp-primary); box-shadow: 0 0 0 5px var(--wp-bg), 0 0 22px rgba(94,234,212,.3); }
.workflow-step.waiting .step-index { border-color: rgba(212,175,55,.5); color: var(--wp-gold-soft); }
.workflow-step.blocked .step-index { border-color: rgba(255,92,122,.6); color: var(--wp-danger); }
.workflow-step strong { font-size: 12px; }
.workflow-step small { min-height: 28px; color: var(--wp-sub); font-size: 9px; line-height: 1.45; }
.workflow-step .el-progress { width: 100%; }
.agent-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 9px; margin-top: 6px; }
.agent-pulse-card { display: grid; grid-template-columns: 30px 1fr auto; align-items: center; gap: 8px; padding: 10px; border: 1px solid var(--wp-border); border-radius: 12px; background: linear-gradient(140deg, rgba(148,163,184,.07), rgba(94,234,212,.025)); }
.agent-pulse-card.running { border-color: rgba(94,234,212,.35); }
.agent-pulse-card.done { border-color: rgba(52,211,153,.28); }
.agent-avatar { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 9px; background: linear-gradient(135deg, rgba(94,234,212,.18), rgba(212,175,55,.18)); color: var(--wp-gold-soft); font-size: 12px; font-weight: 800; }
.agent-copy { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.agent-copy strong { font-size: 11px; }
.agent-copy span { overflow: hidden; color: var(--wp-sub); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.workflow-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; color: var(--wp-sub); font-size: 10px; }
.monitor-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-bottom: 8px; }
.monitor-metrics > div { display: flex; flex-direction: column; gap: 3px; padding: 9px 10px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(148,163,184,.05); }
.monitor-metrics span { color: var(--wp-sub); font-size: 9px; letter-spacing: .08em; }
.monitor-metrics strong { font-family: "Bodoni MT", serif; font-size: 20px; }
.monitor-metrics em { color: var(--wp-success); font-size: 9px; font-style: normal; }
.monitor-metrics em.up { color: var(--wp-primary); }
.monitor-metrics em.warn { color: #f59e0b; }
.monitor-metrics em.gold { color: var(--wp-gold-soft); }
.model-chart { width: 100%; height: 292px; overflow: visible; }
.chart-grid-line { stroke: var(--wp-border); stroke-width: .6; stroke-dasharray: 3 6; vector-effect: non-scaling-stroke; }
.chart-line { fill: none; stroke-width: 2; vector-effect: non-scaling-stroke; stroke-linecap: round; stroke-linejoin: round; }
.call-line { stroke: var(--wp-primary); filter: drop-shadow(0 0 5px rgba(94,234,212,.5)); }
.latency-line { stroke: url(#latencyLine); stroke-dasharray: 5 4; }
.chart-point { stroke: var(--wp-bg); stroke-width: 2; vector-effect: non-scaling-stroke; }
.call-point { fill: var(--wp-primary); }
.latency-point { fill: var(--wp-gold-soft); }
.chart-label, .chart-axis { fill: var(--wp-sub); font-size: 9px; letter-spacing: .06em; }
.latency-label { fill: var(--wp-gold-soft); }
.chart-axis { text-anchor: middle; }
.chart-legend { display: flex; align-items: center; gap: 16px; margin-top: -8px; color: var(--wp-sub); font-size: 10px; }
.chart-legend span { display: inline-flex; align-items: center; gap: 6px; }
.chart-legend span:last-child { margin-left: auto; }
.chart-legend i { width: 16px; height: 2px; background: var(--wp-primary); box-shadow: 0 0 8px rgba(94,234,212,.45); }
.chart-legend .latency i { background: var(--wp-gold-soft); box-shadow: 0 0 8px rgba(212,175,55,.35); }
.cockpit-bottom { grid-template-columns: .9fr 1.1fr; }
.model-status-layout { display: grid; grid-template-columns: 180px 1fr; align-items: center; gap: 18px; }
.model-donut { position: relative; display: grid; place-items: center; width: 154px; height: 154px; margin: 4px auto; border-radius: 50%; box-shadow: 0 0 36px rgba(94,234,212,.12); }
.model-donut::before { content: ""; position: absolute; inset: 10px; border-radius: 50%; background: var(--wp-card-solid); box-shadow: inset 0 0 24px rgba(0,0,0,.18); }
.donut-core { position: relative; z-index: 1; display: flex; flex-direction: column; text-align: center; }
.donut-core strong { font-family: "Bodoni MT", serif; font-size: 31px; }
.donut-core span { color: var(--wp-sub); font-size: 10px; }
.model-status-legend { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 18px; }
.model-status-legend > div { display: grid; grid-template-columns: 9px 1fr auto; align-items: center; gap: 8px; }
.model-status-legend i { width: 8px; height: 8px; border-radius: 50%; background: var(--wp-success); }
.model-status-legend i.busy { background: var(--wp-gold-soft); }
.model-status-legend i.degraded { background: #f97316; }
.model-status-legend i.queue { background: var(--wp-primary); }
.model-status-legend span { color: var(--wp-sub); font-size: 11px; }
.model-status-legend strong { font-family: "Bodoni MT", serif; font-size: 18px; }
.model-node-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 16px; }
.model-node { display: flex; flex-direction: column; gap: 6px; padding: 11px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.045); color: var(--wp-text); cursor: pointer; text-align: left; transition: .2s ease; }
.model-node.busy { border-color: rgba(212,175,55,.4); }
.model-node.degraded { border-color: rgba(249,115,22,.55); }
.node-head, .node-meta { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.node-head > span { color: var(--wp-sub); font-size: 9px; letter-spacing: .12em; text-transform: uppercase; }
.model-node > strong { font-size: 12px; }
.model-node > small { color: var(--wp-sub); font-size: 10px; }
.node-load > span { color: var(--wp-sub); font-size: 9px; }
.node-load > div { height: 3px; margin-top: 4px; border-radius: 3px; background: rgba(148,163,184,.12); overflow: hidden; }
.node-load i { display: block; height: 100%; border-radius: 3px; background: linear-gradient(90deg, var(--wp-primary), var(--wp-gold)); box-shadow: 0 0 9px rgba(94,234,212,.35); }
.node-meta { color: var(--wp-sub); font-size: 9px; }
.suggestion-list { display: flex; flex-direction: column; gap: 10px; max-height: 480px; overflow: auto; padding-right: 4px; }
.suggestion-item { position: relative; padding: 14px; border: 1px solid var(--wp-border); border-radius: 14px; background: linear-gradient(135deg, rgba(148,163,184,.06), rgba(94,234,212,.025)); }
.suggestion-item.high { border-color: rgba(212,175,55,.42); }
.suggestion-item.applied { opacity: .68; border-color: rgba(52,211,153,.45); }
.suggestion-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.suggestion-head > div { display: flex; align-items: center; gap: 8px; }
.priority { color: var(--wp-gold-soft); font-size: 9px; letter-spacing: .1em; }
.suggestion-head > strong { color: var(--wp-primary); font-family: "Bodoni MT", serif; font-size: 18px; }
.suggestion-item h4 { margin: 9px 0 5px; font-size: 14px; }
.suggestion-item p { margin: 0; color: var(--wp-sub); font-size: 11px; line-height: 1.65; }
.suggestion-meta { display: flex; justify-content: space-between; gap: 8px; margin-top: 9px; color: var(--wp-sub); font-size: 9px; }
.suggestion-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 11px; }
.suggestion-btns { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.execution-card { margin-top: var(--cockpit-gap); }
.execution-list { display: flex; flex-direction: column; gap: 12px; }
.execution-item { padding: 14px 16px; border: 1px solid var(--wp-border); border-radius: 14px; background: linear-gradient(135deg, rgba(94,234,212,.045), rgba(148,163,184,.05)); }
.execution-item.done { border-color: rgba(52,211,153,.4); }
.execution-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.execution-title { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.execution-title strong { font-size: 13px; }
.execution-action { color: var(--wp-sub); font-size: 10px; padding: 2px 8px; border: 1px solid var(--wp-border); border-radius: 999px; }
.execution-time { color: var(--wp-sub); font-size: 10px; }
.execution-steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; margin: 12px 0 10px; }
.execution-step { display: flex; align-items: center; gap: 7px; padding: 7px 9px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(148,163,184,.05); color: var(--wp-sub); font-size: 11px; }
.execution-step i { display: grid; place-items: center; width: 18px; height: 18px; border-radius: 50%; border: 1px solid var(--wp-border); font-size: 9px; font-style: normal; }
.execution-step.running { border-color: rgba(94,234,212,.6); color: var(--wp-text); }
.execution-step.running i { border-color: rgba(94,234,212,.8); color: var(--wp-primary); }
.execution-step.done { border-color: rgba(52,211,153,.35); color: var(--wp-text); }
.execution-step.done i { border-color: rgba(52,211,153,.6); color: var(--wp-success); }
.execution-log { display: flex; flex-direction: column; gap: 4px; margin-top: 10px; }
.execution-log p { margin: 0; color: var(--wp-sub); font-size: 10px; line-height: 1.6; }
.execution-log p span { margin-right: 8px; color: var(--wp-gold-soft); font-size: 9px; }
.execution-result { display: flex; align-items: center; gap: 9px; margin-top: 10px; color: var(--wp-text); font-size: 11px; }
@media (max-width: 760px) { .execution-steps { grid-template-columns: 1fr; } }
.command-pre { margin: 0; padding: 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: var(--wp-bg-soft); color: var(--wp-text); white-space: pre-wrap; line-height: 1.7; }
.detail-text { color: var(--wp-sub); }
.timeline-node { display: flex; flex-direction: column; gap: 4px; width: 100%; padding: 0; border: 0; background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.timeline-node span { color: var(--wp-sub); font-size: 12px; }
.timeline-detail p { color: var(--wp-sub); line-height: 1.8; }
@media (max-width: 1450px) { .cockpit-grid { grid-template-columns: 1fr; } .workflow-card, .model-monitor-card { min-height: auto; } }
@media (max-width: 1180px) { .vital-summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .agent-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); } .cockpit-bottom { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .vital-summary-grid, .agent-strip, .model-node-grid { grid-template-columns: 1fr; } .hero-telemetry .gold { width: 100%; margin-left: 0; } .monitor-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .model-status-layout { grid-template-columns: 1fr; } .workflow-summary { grid-template-columns: 1fr; } .workflow-eta { text-align: left; } }

/* 观测区实时摘要（真实数据：wp-bff /overview 聚合） */
.observability-strip { margin-bottom: 18px; }
.observability-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
.observability-card {
  display: flex; flex-direction: column; gap: 4px; padding: 14px 16px; text-align: left;
  border: 1px solid rgba(212, 175, 55, .32); border-radius: 14px; cursor: pointer;
  background: linear-gradient(150deg, rgba(212, 175, 55, .10), rgba(15, 23, 42, .35));
  color: var(--wp-text); transition: transform .18s ease, border-color .18s ease;
}
.observability-card:hover { transform: translateY(-2px); border-color: var(--wp-gold-soft, #d4af37); }
.observability-card.static { cursor: default; }
.observability-card.static:hover { transform: none; }
.observability-card.warn { border-color: rgba(239, 68, 68, .55); background: linear-gradient(150deg, rgba(239, 68, 68, .14), rgba(15, 23, 42, .35)); }
.observability-card span { color: var(--wp-sub); font-size: 11px; letter-spacing: .08em; }
.observability-card strong { font-size: 26px; font-weight: 650; }
.observability-card strong small { font-size: 12px; font-weight: 400; color: var(--wp-sub); }
.observability-card em { color: var(--wp-sub); font-size: 11px; font-style: normal; }
</style>

<style scoped>
.mode-value { font-size: 17px !important; color: var(--wp-gold-soft); letter-spacing: .04em; }
.activity-legend { display: flex; align-items: center; gap: 14px; margin: 2px 0 14px; color: var(--wp-sub); font-size: 10px; flex-wrap: wrap; }
.activity-legend span { display: inline-flex; align-items: center; gap: 5px; }
.activity-legend i { width: 7px; height: 7px; border-radius: 50%; background: var(--wp-success); box-shadow: 0 0 8px rgba(52,211,153,.4); }
.activity-legend i.running { background: var(--wp-primary); box-shadow: 0 0 8px rgba(94,234,212,.5); }
.activity-legend i.waiting { background: #f4d58d; box-shadow: 0 0 8px rgba(212,175,55,.4); }
.activity-legend i.blocked { background: var(--wp-danger); }
.activity-legend em { margin-left: auto; font-style: normal; color: var(--wp-gold-soft); }
.activity-diagram { position: relative; padding: 14px; border: 1px solid var(--wp-border); border-radius: 16px; background: linear-gradient(145deg, rgba(8,15,28,.32), rgba(15,23,42,.16)); overflow: hidden; }
.activity-diagram::before { content: ""; position: absolute; inset: 0; pointer-events: none; background-image: linear-gradient(var(--wp-grid-color) 1px, transparent 1px), linear-gradient(90deg, var(--wp-grid-color) 1px, transparent 1px); background-size: 24px 24px; mask-image: linear-gradient(to bottom, #000, transparent 94%); }
.activity-stage { position: relative; z-index: 1; display: flex; align-items: center; justify-content: center; gap: 8px; }
.activity-node { position: relative; display: inline-flex; align-items: center; justify-content: center; flex-direction: column; gap: 2px; min-width: 92px; min-height: 50px; padding: 8px 10px; border: 1px solid var(--wp-border); border-radius: 12px; background: var(--wp-card-solid); color: var(--wp-text); box-shadow: 0 8px 24px rgba(0,0,0,.12); cursor: pointer; text-align: center; transition: transform .2s ease, border-color .2s ease, box-shadow .2s ease; }
.activity-node:hover { transform: translateY(-2px); border-color: var(--wp-primary); box-shadow: var(--wp-glow); }
.activity-node small { color: var(--wp-sub); font-size: 9px; }
.activity-node.done { border-color: rgba(52,211,153,.48); }
.activity-node.done::after { content: ""; position: absolute; inset: -1px; border-radius: inherit; box-shadow: inset 0 0 16px rgba(52,211,153,.08); pointer-events: none; }
.activity-node.running { border-color: rgba(94,234,212,.75); box-shadow: 0 0 18px rgba(94,234,212,.18); }
.activity-node.waiting { border-color: rgba(212,175,55,.42); border-style: dashed; color: var(--wp-sub); }
.activity-node.blocked { border-color: rgba(255,92,122,.7); }
.start-node, .end-node { min-width: 68px; min-height: 42px; border-radius: 999px; }
.fanout-node { border-color: rgba(212,175,55,.6); background: linear-gradient(135deg, var(--wp-card-solid), rgba(212,175,55,.08)); }
.activity-arrow { position: relative; width: 30px; height: 1px; background: var(--wp-border); }
.activity-arrow::after { content: ""; position: absolute; right: -1px; top: -3px; border-width: 3px 0 3px 5px; border-style: solid; border-color: transparent transparent transparent var(--wp-sub); }
.activity-arrow.complete { background: linear-gradient(90deg, rgba(52,211,153,.65), rgba(94,234,212,.75)); }
.activity-arrow.complete::after { border-left-color: var(--wp-primary); }
.activity-arrow.waiting { background: repeating-linear-gradient(90deg, rgba(212,175,55,.55) 0 4px, transparent 4px 8px); }
.activity-arrow.waiting::after { border-left-color: var(--wp-gold-soft); }
.parallel-zone { position: relative; z-index: 1; margin: 14px 0; padding: 12px; border-top: 1px solid rgba(94,234,212,.18); border-bottom: 1px solid rgba(212,175,55,.2); background: linear-gradient(90deg, rgba(94,234,212,.035), rgba(212,175,55,.025)); }
.parallel-header { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; color: var(--wp-sub); font-size: 9px; letter-spacing: .13em; }
.parallel-header strong { color: var(--wp-primary); font-size: 11px; }
.parallel-header em { margin-left: auto; color: var(--wp-gold-soft); font-style: normal; letter-spacing: 0; }
.activity-lane { position: relative; display: grid; grid-template-columns: minmax(190px, .8fr) minmax(430px, 2fr) 76px; align-items: center; gap: 10px; min-height: 70px; margin: 8px 0; padding: 8px; border: 1px solid var(--wp-border); border-radius: 13px; background: rgba(8,15,28,.24); }
.activity-lane.running { border-color: rgba(94,234,212,.30); }
.activity-lane.done { border-color: rgba(52,211,153,.25); }
.activity-lane.waiting { opacity: .82; }
.lane-identity { display: grid; grid-template-columns: 32px 1fr auto; align-items: center; gap: 8px; padding: 7px 8px; border: 0; border-right: 1px solid var(--wp-border); background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.lane-copy { display: flex; flex-direction: column; min-width: 0; gap: 3px; }
.lane-copy small { overflow: hidden; color: var(--wp-sub); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.lane-track { display: grid; grid-template-columns: 56px 28px minmax(150px, 1fr) 28px 56px; align-items: center; gap: 4px; }
.lane-track .activity-node { min-width: 56px; min-height: 40px; padding: 6px 7px; font-size: 10px; }
.activity-work { min-width: 150px !important; }
.activity-work strong { overflow: hidden; max-width: 170px; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }
.lane-connector { position: relative; height: 2px; border-radius: 2px; background: rgba(148,163,184,.14); overflow: visible; }
.lane-connector i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--wp-primary), var(--wp-gold)); box-shadow: 0 0 8px rgba(94,234,212,.42); }
.lane-connector.done i { background: linear-gradient(90deg, var(--wp-success), var(--wp-primary)); }
.lane-connector.waiting i { background: rgba(212,175,55,.35); box-shadow: none; }
.lane-progress { display: flex; flex-direction: column; gap: 5px; }
.lane-progress span { color: var(--wp-gold-soft); font-family: "Bodoni MT", serif; font-size: 15px; text-align: right; }
.activity-bottom { padding-top: 4px; }
.activity-bottom .activity-node { min-width: 106px; }
@media (max-width: 1350px) {
  .activity-lane { grid-template-columns: 1fr; gap: 4px; }
  .lane-identity { border-right: 0; border-bottom: 1px solid var(--wp-border); }
  .lane-track { grid-template-columns: 56px 24px minmax(130px, 1fr) 24px 56px; }
}
@media (max-width: 900px) {
  .activity-stage { justify-content: flex-start; overflow-x: auto; padding-bottom: 4px; }
  .parallel-header em { display: none; }
  .activity-lane { min-width: 620px; }
  .parallel-zone { overflow-x: auto; }
}
</style>

<style scoped>
.today-summary-card :deep(.el-card__body) { padding: 20px 22px; }
.today-summary-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 18px; padding-bottom: 16px; border-bottom: 1px solid var(--wp-border); }
.today-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .18em; }
.today-summary-head h3 { margin: 6px 0 7px; font-family: "Bodoni MT", serif; font-size: 25px; }
.today-summary-head p { max-width: 760px; margin: 0; color: var(--wp-sub); line-height: 1.7; }
.today-index { display: flex; align-items: center; flex-direction: column; min-width: 126px; padding: 10px 14px; border: 1px solid rgba(212,175,55,.38); border-radius: 14px; background: radial-gradient(circle at 50% 20%, rgba(212,175,55,.17), transparent 72%); text-align: center; }
.today-index strong { color: var(--wp-gold-soft); font-family: "Bodoni MT", serif; font-size: 34px; }
.today-index span { color: var(--wp-sub); font-size: 10px; }
.today-index em { margin-top: 5px; color: var(--wp-success); font-size: 9px; font-style: normal; }
.today-summary-body { display: grid; grid-template-columns: 1.1fr 1.35fr .9fr; gap: 18px; padding-top: 16px; }
.today-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.today-metric { display: flex; flex-direction: column; gap: 4px; padding: 11px; border: 1px solid var(--wp-border); border-radius: 11px; background: rgba(148,163,184,.04); color: var(--wp-text); cursor: pointer; text-align: left; }
.today-metric:hover, .today-attention button:hover { border-color: var(--wp-primary); background: rgba(94,234,212,.05); }
.today-metric span { color: var(--wp-sub); font-size: 10px; }
.today-metric strong { font-family: "Bodoni MT", serif; font-size: 22px; }
.today-metric small { margin-left: 4px; color: var(--wp-sub); font-size: 10px; }
.today-metric em { color: var(--wp-success); font-size: 9px; font-style: normal; }
.today-metric.warning { border-color: rgba(212,175,55,.38); }
.today-metric.warning em { color: var(--wp-gold-soft); }
.today-metric.danger { border-color: rgba(255,92,122,.42); }
.today-events { display: flex; flex-direction: column; gap: 8px; }
.today-event { display: grid; grid-template-columns: 42px 10px 1fr; gap: 8px; align-items: start; }
.today-event-time { color: var(--wp-sub); font-size: 9px; }
.today-event > i { width: 8px; height: 8px; margin-top: 4px; border-radius: 50%; background: var(--wp-primary); box-shadow: 0 0 10px rgba(94,234,212,.45); }
.today-event.knowledge > i { background: var(--wp-success); }
.today-event.model > i { background: var(--wp-gold-soft); }
.today-event.governance > i { background: var(--wp-danger); }
.today-event strong { font-size: 11px; }
.today-event p { margin: 3px 0 0; color: var(--wp-sub); font-size: 10px; line-height: 1.55; }
.today-attention { padding-left: 16px; border-left: 1px solid var(--wp-border); }
.today-attention-title { display: flex; justify-content: space-between; margin-bottom: 8px; }
.today-attention-title span { color: var(--wp-sub); font-size: 10px; }
.today-attention button { display: grid; grid-template-columns: 24px 1fr; gap: 7px; width: 100%; padding: 8px 6px; border: 1px solid transparent; border-radius: 9px; background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.today-attention button > span { color: var(--wp-gold-soft); font-family: "Bodoni MT", serif; font-size: 14px; }
.today-attention p { margin: 0; color: var(--wp-sub); font-size: 10px; line-height: 1.5; }
@media (max-width: 1180px) { .today-summary-body { grid-template-columns: 1fr; } .today-attention { padding-left: 0; border-left: 0; border-top: 1px solid var(--wp-border); padding-top: 12px; } }
@media (max-width: 720px) { .today-summary-head { flex-direction: column; } .today-index { width: 100%; } .today-metrics { grid-template-columns: 1fr; } }
</style>

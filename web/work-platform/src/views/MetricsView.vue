<template>
  <div class="metrics-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">OBSERVABILITY · METRICS</span>
        <h2>指标监控</h2>
        <p class="view-sub">Prometheus 固定查询 · Java Micrometer / Python prometheus_client · 系统级指标</p>
      </div>
      <div class="head-actions">
        <el-tag :type="overview?.available ? 'success' : overview ? 'danger' : 'info'" effect="dark">
          {{ overview?.available ? 'Prometheus 在线' : overview ? 'Prometheus 不可达' : '加载中' }}
        </el-tag>
        <el-tag type="info" effect="plain">{{ overview?.support?.metric_scope || 'system' }} 级指标</el-tag>
        <el-select v-model="selectedJob" style="width: 180px" @change="load">
          <el-option label="全部服务" value="" />
          <el-option v-for="job in jobOptions" :key="job" :label="job" :value="job" />
        </el-select>
        <el-radio-group v-model="selectedWindow" size="small" @change="load">
          <el-radio-button v-for="item in windows" :key="item" :value="item">{{ item }}</el-radio-button>
        </el-radio-group>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-alert
      v-if="overview && !overview.available"
      type="error"
      show-icon
      :closable="false"
      title="指标监控不可读"
      :description="overview.reason || 'Prometheus 不可达；页面不显示 0 或 Mock 指标。'"
    />
    <el-alert
      v-else-if="loadError"
      type="warning"
      show-icon
      :closable="false"
      title="指标刷新失败"
      :description="loadError"
    />

    <template v-if="overview?.available">
      <section class="summary-grid">
        <article v-for="card in summaryCards" :key="card.label" class="summary-card" :class="card.tone">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}<small>{{ card.unit }}</small></strong>
          <em>{{ card.hint }}</em>
        </article>
      </section>

      <section class="panel-grid">
        <el-card class="metric-card" shadow="never">
          <template #header><div class="card-head"><strong>HTTP 指标</strong><span>Micrometer · Java 服务</span></div></template>
          <div class="metric-grid">
            <div><span>QPS</span><strong>{{ formatNumber(currentHttp.qps, 2) }}</strong></div>
            <div><span>错误率</span><strong>{{ formatPercent(currentHttp.error_rate) }}</strong></div>
            <div><span>平均延迟</span><strong>{{ formatMs(currentHttp.avg_latency_ms) }}</strong></div>
            <div><span>最大延迟</span><strong>{{ formatMs(currentHttp.max_latency_ms) }}</strong></div>
            <div><span>P95 延迟</span><strong>{{ formatMs(currentHttp.http_p95_ms) }}</strong></div>
          </div>
          <p class="support-note">
            HTTP P95：{{ overview.support?.http_p95 ? '可用' : `未启用（${overview.support?.http_p95_reason || 'histogram bucket 未暴露'}）` }}
          </p>
        </el-card>

        <el-card class="metric-card" shadow="never">
          <template #header><div class="card-head"><strong>JVM / Micrometer</strong><span>堆、线程、GC</span></div></template>
          <div class="metric-grid">
            <div><span>堆使用</span><strong>{{ formatBytes(overview.summary.jvm_heap_used_bytes) }}</strong></div>
            <div><span>堆上限</span><strong>{{ formatBytes(overview.summary.jvm_heap_max_bytes) }}</strong></div>
            <div><span>堆使用率</span><strong>{{ formatPercent(overview.summary.jvm_heap_used_ratio) }}</strong></div>
            <div><span>活跃线程</span><strong>{{ formatNumber(overview.summary.jvm_threads, 0) }}</strong></div>
            <div><span>GC 平均暂停</span><strong>{{ formatMs(overview.summary.gc_pause_avg_ms) }}</strong></div>
            <div><span>GC 最大暂停</span><strong>{{ formatMs(overview.summary.gc_pause_max_ms) }}</strong></div>
            <div><span>GC P95</span><strong>{{ formatMs(overview.summary.gc_p95_ms) }}</strong></div>
          </div>
        </el-card>

        <el-card class="metric-card" shadow="never">
          <template #header><div class="card-head"><strong>数据库连接池</strong><span>HikariCP</span></div></template>
          <div class="metric-grid">
            <div><span>活跃连接</span><strong>{{ formatNumber(overview.summary.hikari_active, 0) }}</strong></div>
            <div><span>连接上限</span><strong>{{ formatNumber(overview.summary.hikari_max, 0) }}</strong></div>
            <div><span>等待请求</span><strong>{{ formatNumber(overview.summary.hikari_pending, 0) }}</strong></div>
          </div>
        </el-card>

        <el-card class="metric-card" shadow="never">
          <template #header><div class="card-head"><strong>业务指标</strong><span>LLM · Collab · Tool</span></div></template>
          <div class="metric-grid">
            <div><span>LLM QPS</span><strong>{{ formatNumber(overview.summary.llm_qps, 2) }}</strong></div>
            <div><span>LLM 失败率</span><strong>{{ formatPercent(overview.summary.llm_failure_rate) }}</strong></div>
            <div><span>LLM 降级 QPS</span><strong>{{ formatNumber(overview.summary.llm_degraded_qps, 2) }}</strong></div>
            <div><span>协作域</span><strong>{{ formatNumber(overview.summary.collab_domains, 0) }}</strong></div>
            <div><span>心跳积压</span><strong>{{ formatNumber(overview.summary.heartbeat_pending, 0) }}</strong></div>
            <div><span>工具熔断 QPS</span><strong>{{ formatNumber(overview.summary.tool_circuit_open_qps, 2) }}</strong></div>
          </div>
        </el-card>
      </section>

      <el-card class="metric-card service-panel" shadow="never">
        <template #header><div class="card-head"><strong>服务指标</strong><span>按 Prometheus job 聚合</span></div></template>
        <el-table :data="overview.services" size="small" empty-text="暂无服务指标">
          <el-table-column prop="job" label="服务" min-width="160" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.health === 'up' ? 'success' : 'danger'" effect="plain">{{ row.health }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="QPS" width="100">
            <template #default="{ row }">{{ formatNumber(row.qps, 2) }}</template>
          </el-table-column>
          <el-table-column label="错误率" width="100">
            <template #default="{ row }">{{ formatPercent(row.error_rate) }}</template>
          </el-table-column>
          <el-table-column label="平均延迟" width="110">
            <template #default="{ row }">{{ formatMs(row.avg_latency_ms) }}</template>
          </el-table-column>
          <el-table-column label="P95" width="100">
            <template #default="{ row }">{{ formatMs(row.http_p95_ms) }}</template>
          </el-table-column>
          <el-table-column label="堆使用率" width="110">
            <template #default="{ row }">{{ formatPercent(row.heap_used_ratio) }}</template>
          </el-table-column>
          <el-table-column label="线程" width="80">
            <template #default="{ row }">{{ formatNumber(row.threads, 0) }}</template>
          </el-table-column>
          <el-table-column label="DB 活跃/上限" width="120">
            <template #default="{ row }">{{ formatNumber(row.hikari_active, 0) }} / {{ formatNumber(row.hikari_max, 0) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <section class="bottom-grid">
        <el-card class="metric-card" shadow="never">
          <template #header><div class="card-head"><strong>Prometheus Targets</strong><span>{{ overview.summary.targets_up }}/{{ overview.summary.targets_total }} up</span></div></template>
          <el-table :data="overview.targets" size="small" empty-text="暂无 target">
            <el-table-column prop="job" label="Job" min-width="130" />
            <el-table-column prop="instance" label="Instance" min-width="170" show-overflow-tooltip />
            <el-table-column label="状态" width="80">
              <template #default="{ row }"><el-tag size="small" :type="row.health === 'up' ? 'success' : 'danger'">{{ row.health }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="last_error" label="最近错误" min-width="160" show-overflow-tooltip />
          </el-table>
        </el-card>

        <el-card class="metric-card" shadow="never">
          <template #header><div class="card-head"><strong>活动告警</strong><span>{{ overview.alerts.length }} 条</span></div></template>
          <el-table :data="overview.alerts" size="small" empty-text="暂无活动告警">
            <el-table-column prop="name" label="告警" min-width="150" />
            <el-table-column prop="severity" label="级别" width="90" />
            <el-table-column prop="job" label="Job" width="130" />
            <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-card>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { dataProvider } from '../api/provider'
import type { MetricsOverview, MetricsServiceItem } from '../types'

const windows = ['5m', '15m', '1h', '6h', '24h']
const fallbackJobs = ['gateway-service', 'session-manager', 'sense-service', 'body-service', 'tool-executor', 'collab-bus', 'nlp-service', 'wp-bff']
const selectedJob = ref('')
const selectedWindow = ref('15m')
const overview = ref<MetricsOverview | null>(null)
const loading = ref(false)
const loadError = ref('')
let timer: number | undefined

const jobOptions = computed(() => {
  const jobs = (overview.value?.services || []).map(item => item.job).filter(Boolean)
  return jobs.length ? [...new Set(jobs)].sort() : fallbackJobs
})

const selectedService = computed<MetricsServiceItem | null>(() => {
  if (!selectedJob.value) return null
  return overview.value?.services.find(item => item.job === selectedJob.value) || null
})

const currentHttp = computed(() => selectedService.value || overview.value?.summary || {
  qps: null, error_rate: null, avg_latency_ms: null, max_latency_ms: null, http_p95_ms: null,
})

const summaryCards = computed(() => {
  const s = overview.value?.summary
  return [
    { label: 'Target 健康', value: `${s?.targets_up ?? 0}/${s?.targets_total ?? 0}`, unit: '', hint: 'Prometheus 抓取目标', tone: s && s.targets_up === s.targets_total ? 'good' : 'bad' },
    { label: '活动告警', value: String(s?.active_alerts ?? 0), unit: '', hint: 'Prometheus 当前告警', tone: (s?.active_alerts || 0) > 0 ? 'warn' : 'good' },
    { label: '总 QPS', value: formatNumber(s?.qps, 2), unit: '', hint: selectedWindow.value + ' 窗口', tone: 'primary' },
    { label: '错误率', value: formatPercent(s?.error_rate), unit: '', hint: 'HTTP 服务端错误', tone: (s?.error_rate || 0) > 0.05 ? 'warn' : 'good' },
    { label: '平均延迟', value: formatMs(s?.avg_latency_ms), unit: '', hint: 'HTTP 请求', tone: 'primary' },
    { label: 'JVM 堆使用率', value: formatPercent(s?.jvm_heap_used_ratio), unit: '', hint: '所有 Java 服务', tone: (s?.jvm_heap_used_ratio || 0) > 0.85 ? 'warn' : 'good' },
  ]
})

function formatNumber(value: number | null | undefined, digits = 0): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return Number(value).toFixed(digits)
}

function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return `${(Number(value) * 100).toFixed(1)}%`
}

function formatMs(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  const ms = Number(value)
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms.toFixed(ms >= 10 ? 0 : 1)}ms`
}

function formatBytes(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let current = Number(value)
  let index = 0
  while (current >= 1024 && index < units.length - 1) { current /= 1024; index += 1 }
  return `${current.toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    overview.value = await dataProvider.getMetricsOverview(selectedJob.value, selectedWindow.value)
  } catch (error) {
    loadError.value = (error as Error)?.message || '指标加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  timer = window.setInterval(() => { void load() }, 15000)
})
onUnmounted(() => { if (timer !== undefined) window.clearInterval(timer) })
</script>

<style scoped>
.metrics-view { display: flex; flex-direction: column; gap: 16px; }
.view-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.view-head h2 { margin: 2px 0 4px; font-size: 20px; letter-spacing: .04em; }
.view-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .22em; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(130px, 1fr)); gap: 12px; }
.summary-card { position: relative; overflow: hidden; padding: 14px 15px; border: 1px solid var(--wp-border); border-radius: 14px; background: linear-gradient(145deg, rgba(148,163,184,.07), rgba(148,163,184,.025)); box-shadow: var(--wp-shadow); }
.summary-card::after { content: ""; position: absolute; inset: auto 12px 0; height: 1px; background: linear-gradient(90deg, transparent, var(--wp-primary), transparent); opacity: .45; }
.summary-card span { color: var(--wp-sub); font-size: 10px; letter-spacing: .1em; text-transform: uppercase; }
.summary-card strong { display: block; margin-top: 7px; font-family: "Bodoni MT", serif; font-size: 24px; color: var(--wp-text); }
.summary-card small { margin-left: 3px; font-size: 11px; }
.summary-card em { display: block; margin-top: 5px; color: var(--wp-sub); font-size: 10px; font-style: normal; }
.summary-card.good strong { color: var(--wp-success); }
.summary-card.warn strong { color: var(--wp-gold-soft); }
.summary-card.bad strong { color: var(--wp-danger); }
.summary-card.primary strong { color: var(--wp-primary); }
.panel-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.metric-card :deep(.el-card__header) { padding: 12px 16px; }
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.card-head strong { font-size: 14px; }
.card-head span { color: var(--wp-sub); font-size: 10px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.metric-grid > div { padding: 10px 12px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(148,163,184,.04); }
.metric-grid span { display: block; color: var(--wp-sub); font-size: 10px; }
.metric-grid strong { display: block; margin-top: 5px; font-size: 16px; color: var(--wp-text); }
.support-note { margin: 12px 0 0; color: var(--wp-sub); font-size: 10px; }
.bottom-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
@media (max-width: 1400px) { .summary-grid { grid-template-columns: repeat(3, minmax(130px, 1fr)); } }
@media (max-width: 1000px) { .panel-grid, .bottom-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .summary-grid, .metric-grid { grid-template-columns: 1fr; } }
</style>

<template>
  <div class="middleware-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">OBSERVABILITY</span>
        <h2>中间件监控</h2>
        <p class="view-sub">基础设施真实启停状态（TCP 探针） · 检测于 {{ overview?.checked_at || '—' }}</p>
      </div>
      <div class="head-actions">
        <el-tag v-if="liveData" type="success" effect="dark">真实探针</el-tag>
        <el-tag v-else-if="overview" type="warning" effect="dark">演示数据 · BFF 未连接</el-tag>
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="plain">{{ store.dataSource.toUpperCase() }}</el-tag>
        <el-button size="small" type="success" plain :icon="VideoPlay" :disabled="batchRunning || !downCount" @click="runBatch('start')">
          一键启动（{{ downCount }} 个未启用）
        </el-button>
        <el-button size="small" type="danger" plain :icon="VideoPause" :disabled="batchRunning || !upCount" @click="confirmStopAll">
          一键终止（{{ upCount }} 个运行中）
        </el-button>
        <el-button size="small" :loading="loading" @click="refresh">刷新检测</el-button>
      </div>
    </header>

    <el-alert
      v-if="overview && !liveData"
      :closable="false"
      class="fallback-alert"
      type="warning"
      show-icon
      title="当前卡片为演示回落数据，并非中间件真实状态"
      description="未检测到 wp-bff 观测服务（127.0.0.1:8090）。启动 BFF 后卡片将自动切换为真实探针结果：cd services/node/wp-bff && npm start"
    />

    <el-alert v-if="batch" :closable="false" class="batch-alert" :type="batch.action === 'start' ? 'success' : 'warning'" show-icon>
      <template #title>
        串行{{ batch.action === 'start' ? '启动' : '终止' }}中 {{ batch.done + 1 }}/{{ batch.total }}：{{ batch.current }}
        <span class="batch-hint">（等待健康检查通过后继续下一个）</span>
      </template>
      <el-progress :percentage="Math.round(((batch.done + (batch.current ? 0.5 : 1)) / batch.total) * 100)" :stroke-width="6" :show-text="false" />
    </el-alert>

    <section v-if="overview?.enabled" class="summary-row">
      <div class="summary-chip ok"><strong>{{ overview.summary.up }}</strong><span>运行中</span></div>
      <div class="summary-chip bad"><strong>{{ overview.summary.down }}</strong><span>未启用</span></div>
      <div class="summary-chip"><strong>{{ overview.summary.total }}</strong><span>监测总数</span></div>
      <div class="summary-chip"><strong>{{ upRatio }}%</strong><span>可用率</span></div>
    </section>

    <el-empty
      v-if="!loading && overview && !overview.enabled"
      description="中间件观测服务未启用"
      :image-size="96"
      class="disabled-empty"
    >
      <div class="disabled-panel">
        <p>当前未检测到中间件观测数据源（BFF 未就绪或观测服务未启动）。</p>
        <p class="hint">可执行以下命令拉起基础设施后重试：</p>
        <code>docker compose up -d</code>
        <div class="disabled-actions">
          <el-button type="primary" size="small" @click="refresh">重新检测</el-button>
          <el-button size="small" @click="goOverview">返回总览</el-button>
        </div>
      </div>
    </el-empty>

    <section v-else class="mw-grid">
      <article v-for="item in overview?.items || []" :key="item.key" class="mw-card" :class="item.state">
        <div class="mw-head">
          <div class="mw-title">
            <i class="state-dot" :class="item.state" />
            <strong>{{ item.name }}</strong>
            <el-tag size="small" effect="plain" class="port-tag">:{{ item.port }}</el-tag>
          </div>
          <el-tag size="small" :type="stateTagType(item.state)" effect="dark">
            {{ stateLabel(item.state) }}
          </el-tag>
        </div>
        <p class="mw-role">{{ item.role }}</p>

        <template v-if="item.state === 'up'">
          <div class="mw-metrics">
            <div v-for="m in item.metrics" :key="m.label" class="mw-metric">
              <span>{{ m.label }}</span>
              <strong>{{ m.value }}</strong>
            </div>
          </div>
          <div class="mw-foot">
            <span class="check-time">{{ item.last_check }} 检测</span>
            <div class="foot-actions">
              <a v-if="item.console_url" :href="item.console_url" target="_blank" rel="noreferrer">
                <el-button size="small" text type="primary">{{ item.console_label || '控制台' }} ↗</el-button>
              </a>
              <el-button size="small" text type="danger" :disabled="busy || batchRunning" @click="confirmStop(item)">终止</el-button>
            </div>
          </div>
        </template>

        <div v-else class="mw-disabled" :class="{ starting: item.state !== 'down' }">
          <template v-if="item.state === 'starting' || item.state === 'stopping'">
            <p class="starting-title">{{ item.state === 'starting' ? `正在启动 ${item.name}…` : `正在终止 ${item.name}…` }}</p>
            <el-progress :percentage="100" indeterminate :show-text="false" :stroke-width="5" status="warning" :duration="2" />
            <code class="startup-log">$ docker compose {{ item.state === 'starting' ? 'up -d' : 'stop' }} {{ item.key }}<br />{{ item.state === 'starting' ? '拉起容器并等待健康检查通过…' : '停止容器并确认探针不再响应…' }}</code>
          </template>
          <template v-else>
            <p>该中间件未启动，相关功能不可用。</p>
            <div class="mw-start-row">
              <el-button type="warning" size="small" :icon="VideoPlay" :disabled="busy || batchRunning" @click="start(item)">启动</el-button>
              <code>docker compose up -d {{ item.key }}</code>
            </div>
          </template>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { useAppStore } from '../stores/app'
import type { MiddlewareNode, MiddlewareOverview } from '../types'

const store = useAppStore()
const router = useRouter()
const loading = ref(false)
const overview = ref<MiddlewareOverview | null>(null)
const busy = ref(false)
let startPoll: number | undefined
let lastAction: 'start' | 'stop' = 'start'
const batch = ref<{ action: 'start' | 'stop', current: string, done: number, total: number } | null>(null)
const batchRunning = computed(() => batch.value !== null)
const upCount = computed(() => overview.value?.summary.up ?? 0)
const downCount = computed(() => overview.value?.summary.down ?? 0)
// live=wp-bff 真实探针数据；false 表示 BFF 不可达、正在展示演示回落数据
const liveData = computed(() => overview.value?.data_source !== 'mock')
const upRatio = computed(() => {
  if (!overview.value || !overview.value.summary.total) return 0
  return Math.round((overview.value.summary.up / overview.value.summary.total) * 100)
})

async function fetchOverview() {
  overview.value = await dataProvider.getMiddleware()
}

async function refresh() {
  loading.value = true
  try {
    await fetchOverview()
  } finally {
    loading.value = false
  }
}

async function start(item: MiddlewareNode) {
  if (item.state !== 'down' || busy.value) return
  busy.value = true
  try {
    await dataProvider.startMiddleware(item.key)
    lastAction = 'start'
    item.state = 'starting'
    item.last_check = '刚刚'
    ElMessage.info(`正在启动 ${item.name}，等待容器健康检查…`)
    startPollTracking()
  } catch {
    ElMessage.error(`启动 ${item.name} 失败，请确认 wp-bff 与 Docker 引擎可用`)
  } finally {
    busy.value = false
  }
}

function confirmStop(item: MiddlewareNode) {
  ElMessageBox.confirm(
    `确定终止 ${item.name}？终止期间依赖它的功能将不可用，可随时重新启动。`,
    '终止中间件',
    { type: 'warning', confirmButtonText: '终止', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
  ).then(() => stop(item)).catch(() => {})
}

function confirmStopAll() {
  ElMessageBox.confirm(
    `确定串行终止全部 ${upCount.value} 个运行中的中间件？终止期间整条业务链路将不可用，可一键重新拉起。`,
    '一键终止中间件',
    { type: 'warning', confirmButtonText: '全部终止', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
  ).then(() => runBatch('stop')).catch(() => {})
}

const sleep = (ms: number) => new Promise(resolve => window.setTimeout(resolve, ms))

async function waitForState(key: string, target: 'up' | 'down', timeoutMs = 180000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    await sleep(1500)
    try {
      await fetchOverview()
    } catch { continue }
    const item = overview.value?.items.find(entry => entry.key === key)
    if (!item) return false
    if (item.state === target) return true
    // 已脱离中间态但未达目标态（如 compose 失败）——提前结束等待
    if (item.state === 'up' || item.state === 'down') return false
  }
  return false
}

async function runBatch(action: 'start' | 'stop') {
  if (batch.value || busy.value) return
  const items = overview.value?.items || []
  const targets = action === 'start'
    ? items.filter(item => item.state === 'down')
    : items.filter(item => item.state === 'up')
  if (!targets.length) {
    ElMessage.info(action === 'start' ? '没有未启用的中间件' : '没有运行中的中间件')
    return
  }
  batch.value = { action, current: '', done: 0, total: targets.length }
  let ok = 0
  for (const item of targets) {
    batch.value.current = item.name
    try {
      if (action === 'start') await dataProvider.startMiddleware(item.key)
      else await dataProvider.stopMiddleware(item.key)
    } catch {
      continue
    }
    if (await waitForState(item.key, action === 'start' ? 'up' : 'down')) ok += 1
    batch.value.done += 1
    batch.value.current = ''
  }
  batch.value = null
  await fetchOverview()
  if (ok === targets.length) {
    ElMessage.success(`串行${action === 'start' ? '启动' : '终止'}完成：${ok}/${targets.length} 全部成功`)
  } else {
    ElMessage.warning(`串行${action === 'start' ? '启动' : '终止'}结束：${ok}/${targets.length} 成功，其余请查看卡片状态或审计日志`)
  }
}

async function stop(item: MiddlewareNode) {
  if (item.state !== 'up' || busy.value) return
  busy.value = true
  try {
    await dataProvider.stopMiddleware(item.key)
    lastAction = 'stop'
    item.state = 'stopping'
    item.last_check = '刚刚'
    ElMessage.info(`正在终止 ${item.name}…`)
    startPollTracking()
  } catch {
    ElMessage.error(`终止 ${item.name} 失败，请确认 wp-bff 与 Docker 引擎可用`)
  } finally {
    busy.value = false
  }
}

function startPollTracking() {
  if (startPoll) return
  startPoll = window.setInterval(async () => {
    try {
      await fetchOverview()
      const inFlight = overview.value?.items.some(entry => entry.state === 'starting' || entry.state === 'stopping')
      if (!inFlight) {
        window.clearInterval(startPoll)
        startPoll = undefined
        if (lastAction === 'stop') ElMessage.success('中间件已停止')
        else ElMessage.success('中间件已启动并通过健康检查')
      }
    } catch {
      window.clearInterval(startPoll)
      startPoll = undefined
    }
  }, 1400)
}

function stateLabel(state: MiddlewareNode['state']) {
  return { up: '运行中', down: '未启用', starting: '启动中', stopping: '停止中' }[state]
}

function stateTagType(state: MiddlewareNode['state']) {
  return { up: 'success', down: 'info', starting: 'warning', stopping: 'warning' }[state] as 'success' | 'info' | 'warning'
}

function goOverview() {
  router.push('/overview')
}

onMounted(refresh)

onUnmounted(() => {
  if (startPoll) window.clearInterval(startPoll)
  startPoll = undefined
})
</script>

<style scoped>
.middleware-view { display: flex; flex-direction: column; gap: 16px; }
.view-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
.view-head h2 { margin: 2px 0 4px; font-size: 20px; letter-spacing: .04em; }
.view-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .22em; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.batch-alert { border-radius: 10px; }
.fallback-alert { border-radius: 10px; }
.fallback-alert :deep(.el-alert__description) { font-size: 12px; }
.batch-alert :deep(.el-alert__title) { display: flex; align-items: center; gap: 6px; width: 100%; }
.batch-hint { color: var(--wp-sub); font-size: 11px; font-weight: 400; }
.batch-alert :deep(.el-progress) { margin-top: 6px; }
.summary-row { display: flex; gap: 12px; flex-wrap: wrap; }
.summary-chip { display: flex; align-items: baseline; gap: 8px; padding: 10px 16px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.05); }
.summary-chip strong { font-size: 20px; }
.summary-chip span { color: var(--wp-sub); font-size: 11px; }
.summary-chip.ok strong { color: var(--wp-success); }
.summary-chip.bad strong { color: var(--wp-danger); }
.mw-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.mw-card { padding: 16px; border: 1px solid var(--wp-border); border-radius: 14px; background: linear-gradient(135deg, rgba(148,163,184,.06), rgba(94,234,212,.025)); }
.mw-card.down { opacity: .78; border-style: dashed; }
.mw-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.mw-title { display: flex; align-items: center; gap: 8px; }
.mw-title strong { font-size: 14px; }
.state-dot { width: 8px; height: 8px; border-radius: 50%; }
.state-dot.up { background: var(--wp-success); box-shadow: 0 0 10px rgba(52,211,153,.55); }
.state-dot.down { background: #64748b; }
.state-dot.starting, .state-dot.stopping { background: var(--wp-gold-soft); box-shadow: 0 0 10px rgba(212,175,55,.55); animation: mw-breathe 1.2s ease-in-out infinite; }
@keyframes mw-breathe { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }
.port-tag { font-family: ui-monospace, monospace; }
.mw-role { margin: 8px 0 0; color: var(--wp-sub); font-size: 11px; }
.mw-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 12px; }
.mw-metric { display: flex; flex-direction: column; gap: 2px; padding: 8px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(8,15,28,.25); }
.mw-metric span { color: var(--wp-sub); font-size: 9px; }
.mw-metric strong { font-size: 13px; }
.mw-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; }
.check-time { color: var(--wp-sub); font-size: 10px; }
.foot-actions { display: flex; align-items: center; gap: 4px; }
.mw-disabled { margin-top: 12px; padding: 12px; border: 1px dashed var(--wp-border); border-radius: 10px; }
.mw-disabled.starting { border-color: rgba(212,175,55,.5); border-style: solid; }
.mw-disabled p { margin: 0 0 8px; color: var(--wp-sub); font-size: 11px; }
.mw-disabled code { display: block; padding: 7px 10px; border-radius: 8px; background: rgba(8,15,28,.4); color: var(--wp-gold-soft); font-size: 11px; }
.starting-title { color: var(--wp-gold-soft) !important; font-weight: 600; }
.startup-log { margin-top: 10px; line-height: 1.7; }
.mw-start-row { display: flex; align-items: center; gap: 10px; }
.mw-start-row code { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.disabled-empty :deep(.el-empty__description) { margin-top: 8px; }
.disabled-panel { display: flex; flex-direction: column; align-items: center; gap: 10px; margin-top: 4px; }
.disabled-panel p { margin: 0; color: var(--wp-sub); font-size: 12px; }
.disabled-panel .hint { font-size: 11px; }
.disabled-panel code { padding: 8px 14px; border-radius: 8px; background: rgba(8,15,28,.4); color: var(--wp-gold-soft); font-size: 12px; }
.disabled-actions { display: flex; gap: 10px; margin-top: 4px; }
</style>

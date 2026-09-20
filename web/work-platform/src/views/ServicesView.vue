<template>
  <div class="services-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">RUNTIME CONTROL</span>
        <h2>后台服务控制台</h2>
        <p class="view-sub">应用服务端口探针 · 本地受控启动 / 停止 · 检测于 {{ overview?.checked_at || '—' }}</p>
      </div>
      <div class="head-actions">
        <el-tag v-if="live" type="success" effect="dark">真实探针</el-tag>
        <el-tag v-else-if="overview" type="warning" effect="dark">演示目录 · BFF 未连接</el-tag>
        <el-tag :type="overview?.control_enabled ? 'success' : 'info'" effect="plain">
          {{ overview?.control_enabled ? '可控制' : '只读' }}
        </el-tag>
        <el-button size="small" :loading="loading" @click="refresh">刷新检测</el-button>
      </div>
    </header>

    <el-alert
      v-if="overview && !live"
      :closable="false"
      class="fallback-alert"
      type="warning"
      show-icon
      title="当前显示的是服务目录降级数据，并非真实进程状态"
      description="启动 wp-bff 后本页自动切换为真实 TCP 探针结果。"
    />

    <el-alert
      v-if="overview && !overview.control_enabled"
      :closable="false"
      class="fallback-alert"
      type="info"
      show-icon
      title="服务控制已关闭（WP_BFF_APP_CONTROL=false）"
      description="当前只允许查看状态，所有启动/停止按钮均不可用。"
    />

    <section v-if="overview" class="summary-row">
      <div class="summary-chip ok"><strong>{{ overview.summary.up }}</strong><span>运行中</span></div>
      <div class="summary-chip bad"><strong>{{ overview.summary.down }}</strong><span>未运行</span></div>
      <div class="summary-chip"><strong>{{ overview.summary.total }}</strong><span>服务总数</span></div>
      <div class="summary-chip"><strong>{{ controlledCount }}</strong><span>BFF 托管</span></div>
    </section>

    <section class="service-grid">
      <article
        v-for="item in overview?.items || []"
        :key="item.key"
        class="service-card"
        :class="item.state"
        :data-testid="`service-${item.key}`"
      >
        <div class="service-head">
          <div class="service-title">
            <i class="state-dot" :class="item.state" />
            <strong>{{ item.name }}</strong>
            <el-tag size="small" effect="plain">:{{ item.port }}</el-tag>
          </div>
          <el-tag size="small" :type="stateType(item.state)" effect="dark">{{ stateLabel(item.state) }}</el-tag>
        </div>
        <p class="service-role">{{ item.role }}</p>
        <div class="service-metrics">
          <span v-for="metric in item.metrics" :key="metric.label">{{ metric.label }}<strong>{{ metric.value }}</strong></span>
        </div>
        <div class="service-foot">
          <span class="control-note" :class="item.control_status">
            {{ controlLabel(item) }}
          </span>
          <div class="service-actions">
            <el-button
              v-if="item.state === 'down'"
              size="small"
              type="success"
              plain
              :icon="VideoPlay"
              :disabled="busy || !item.controllable"
              @click="start(item)"
            >启动</el-button>
            <el-button
              v-else
              size="small"
              type="danger"
              plain
              :icon="VideoPause"
              :disabled="busy || !item.controllable || item.control_status === 'external'"
              @click="stop(item)"
            >停止</el-button>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import type { ManagedService, ManagedServiceOverview } from '../types'

const overview = ref<ManagedServiceOverview | null>(null)
const loading = ref(false)
const busy = ref(false)
const live = computed(() => overview.value?.data_source === 'live')
const controlledCount = computed(() => (overview.value?.items || []).filter(item => item.controlled).length)

async function refresh() {
  loading.value = true
  try {
    overview.value = await dataProvider.getServices()
  } finally {
    loading.value = false
  }
}

async function start(item: ManagedService) {
  busy.value = true
  try {
    await dataProvider.startService(item.key)
    item.state = 'starting'
    ElMessage.info(`正在启动 ${item.name}，请等待端口探针恢复`)
    window.setTimeout(() => void refresh(), 1200)
  } catch (error) {
    ElMessage.error((error as Error)?.message || `${item.name} 启动失败`)
  } finally {
    busy.value = false
  }
}

async function stop(item: ManagedService) {
  try {
    await ElMessageBox.confirm(
      `确认停止 ${item.name}？该操作仅适用于由 wp-bff 启动的本地进程。`,
      '停止后台服务',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    await dataProvider.stopService(item.key)
    item.state = 'stopping'
    ElMessage.warning(`正在停止 ${item.name}`)
    window.setTimeout(() => void refresh(), 1200)
  } catch (error) {
    ElMessage.error((error as Error)?.message || `${item.name} 停止失败`)
  } finally {
    busy.value = false
  }
}

function stateLabel(state: ManagedService['state']) {
  return { up: '运行中', down: '未运行', starting: '启动中', stopping: '停止中' }[state]
}

function stateType(state: ManagedService['state']) {
  return state === 'up' ? 'success' : state === 'down' ? 'info' : 'warning'
}

function controlLabel(item: ManagedService) {
  if (item.control_status === 'controlled') return `由 BFF 托管${item.pid ? ` · PID ${item.pid}` : ''}`
  if (item.control_status === 'external') return '外部进程 · 只能监控'
  return '只监控'
}

onMounted(refresh)
</script>

<style scoped>
.services-view { display: flex; flex-direction: column; gap: 18px; }
.view-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 18px; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.view-kicker { color: var(--wp-gold-soft); font-size: 10px; letter-spacing: .18em; }
h2 { margin: 6px 0; color: var(--wp-text); font-size: 24px; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.fallback-alert { margin: 0; }
.summary-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.summary-chip { padding: 12px 16px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.04); }
.summary-chip strong { display: block; font-size: 22px; color: var(--wp-text); }
.summary-chip span { color: var(--wp-sub); font-size: 11px; }
.summary-chip.ok strong { color: #5eead4; }
.summary-chip.bad strong { color: #fb7185; }
.service-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.service-card { padding: 16px; border: 1px solid var(--wp-border); border-radius: 16px; background: linear-gradient(145deg, rgba(15,23,42,.82), rgba(30,41,59,.45)); }
.service-card.up { border-color: rgba(94,234,212,.36); }
.service-head, .service-title, .service-foot, .service-actions { display: flex; align-items: center; gap: 8px; }
.service-head, .service-foot { justify-content: space-between; }
.service-title strong { color: var(--wp-text); }
.service-role { min-height: 36px; margin: 10px 0; color: var(--wp-sub); font-size: 12px; }
.state-dot { width: 8px; height: 8px; border-radius: 50%; background: #64748b; }
.state-dot.up { background: #5eead4; box-shadow: 0 0 8px rgba(94,234,212,.8); }
.state-dot.starting, .state-dot.stopping { background: #f4d58d; }
.service-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin: 10px 0 14px; }
.service-metrics span { color: var(--wp-sub); font-size: 10px; }
.service-metrics strong { display: block; color: var(--wp-text); font-size: 12px; }
.control-note { font-size: 11px; color: var(--wp-sub); }
.control-note.controlled { color: #5eead4; }
.control-note.external { color: #f4d58d; }
@media (max-width: 760px) { .view-head { flex-direction: column; } .summary-row { grid-template-columns: repeat(2, 1fr); } }
</style>
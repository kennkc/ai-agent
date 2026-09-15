<template>
  <div class="tracing-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">OBSERVABILITY</span>
        <h2>链路追踪</h2>
        <p class="view-sub">Jaeger 分布式链路 · 检测于 {{ overview?.checked_at || '—' }}</p>
      </div>
      <div class="head-actions">
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="plain">{{ store.dataSource.toUpperCase() }}</el-tag>
        <el-button size="small" :loading="loading" @click="refresh">刷新检测</el-button>
      </div>
    </header>

    <el-empty
      v-if="!loading && overview && !overview.enabled"
      description="链路追踪服务（Jaeger）未启用"
      :image-size="96"
      class="disabled-empty"
    >
      <div class="disabled-panel">
        <p>未检测到 Jaeger 服务（端口 16686）。链路追踪功能当前不可用。</p>
        <p class="hint">可执行以下命令拉起后重试：</p>
        <code>docker compose up -d jaeger</code>
        <div class="disabled-actions">
          <el-button type="primary" size="small" @click="refresh">重新检测</el-button>
          <el-button size="small" @click="goOverview">返回总览</el-button>
        </div>
      </div>
    </el-empty>

    <template v-else-if="overview?.enabled">
      <section class="toolbar">
        <el-select v-model="activeService" size="small" class="service-select" placeholder="选择服务">
          <el-option v-for="s in overview.services" :key="s.name" :label="s.name" :value="s.name" />
        </el-select>
        <span v-if="onlyJaeger" class="toolbar-warn">仅 Jaeger 自身注册 — 业务服务（gateway/session/sense/body）未启动或未上报链路</span>
        <span class="toolbar-hint">内嵌 Jaeger UI，亦可独立打开</span>
        <a :href="embedUrl" target="_blank" rel="noreferrer">
          <el-button size="small" type="primary" plain>在新窗口打开 ↗</el-button>
        </a>
      </section>

      <section class="stat-row">
        <article v-for="s in overview.services" :key="s.name" class="stat-card" :class="{ active: s.name === activeService }" @click="selectService(s.name)">
          <strong>{{ s.name }}</strong>
          <div class="stat-line"><span>Span/24h</span><em>{{ s.spans_24h.toLocaleString() }}</em></div>
          <div class="stat-line"><span>错误率</span><em :class="{ err: s.error_rate > 1 }">{{ s.error_rate }}%</em></div>
          <div class="stat-line"><span>P99</span><em>{{ s.p99_ms }}ms</em></div>
        </article>
      </section>

      <el-card class="section-card" shadow="never">
        <template #header><strong>最近链路</strong><span class="header-meta">点击行在 Jaeger 中查看完整 Trace</span></template>
        <el-table :data="overview.recent" size="small" @row-click="openTrace">
          <el-table-column prop="time" label="时间" width="96" />
          <el-table-column prop="trace_id" label="Trace ID" width="170">
            <template #default="{ row }"><code class="trace-id">{{ row.trace_id }}</code></template>
          </el-table-column>
          <el-table-column prop="service" label="服务" width="150" />
          <el-table-column prop="operation" label="操作" min-width="220" />
          <el-table-column prop="spans" label="Span" width="70" />
          <el-table-column prop="duration_ms" label="耗时" width="90">
            <template #default="{ row }">{{ row.duration_ms }}ms</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="86">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ok' ? 'success' : 'danger'" effect="plain">
                {{ row.status === 'ok' ? '正常' : '异常' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <section class="embed-frame" :class="{ ready: frameReady }">
        <iframe
          :key="activeService"
          :src="embedUrl"
          title="Jaeger UI"
          @load="frameReady = true"
        />
        <div v-if="!frameReady" class="frame-loading">
          <el-skeleton :rows="5" animated />
          <p>正在加载 Jaeger UI…若长时间无响应，说明 Jaeger 未启动，可点击"刷新检测"。</p>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dataProvider } from '../api/provider'
import { useAppStore } from '../stores/app'
import type { TracingOverview } from '../types'

const store = useAppStore()
const router = useRouter()
const loading = ref(false)
const overview = ref<TracingOverview | null>(null)
const activeService = ref('')
const frameReady = ref(false)

const onlyJaeger = computed(() => {
  const names = overview.value?.services.map(item => item.name) || []
  return names.length > 0 && names.every(name => name.includes('jaeger'))
})

const embedUrl = computed(() => {
  if (!overview.value?.enabled) return ''
  return `${overview.value.ui_url}/search?service=${encodeURIComponent(activeService.value)}&limit=20&lookback=1h`
})

function selectService(name: string) {
  activeService.value = name
  frameReady.value = false
}

function openTrace(row: { trace_id: string }) {
  if (!overview.value?.enabled) return
  window.open(`${overview.value.ui_url}/trace/${row.trace_id}`, '_blank', 'noreferrer')
}

async function refresh() {
  loading.value = true
  frameReady.value = false
  try {
    overview.value = await dataProvider.getTracing()
    const names = overview.value?.services.map(item => item.name) || []
    if (!names.includes(activeService.value)) activeService.value = names[0] || ''
  } finally {
    loading.value = false
  }
}

function goOverview() {
  router.push('/overview')
}

onMounted(refresh)
</script>

<style scoped>
.tracing-view { display: flex; flex-direction: column; gap: 16px; }
.view-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
.view-head h2 { margin: 2px 0 4px; font-size: 20px; letter-spacing: .04em; }
.view-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .22em; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.toolbar { display: flex; align-items: center; gap: 12px; }
.service-select { width: 220px; }
.toolbar-hint { color: var(--wp-sub); font-size: 11px; margin-right: auto; }
.toolbar-warn { color: var(--wp-warning, #d4af37); font-size: 11px; }
.stat-row { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
.stat-card { padding: 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.05); cursor: pointer; }
.stat-card:hover { border-color: var(--wp-primary); }
.stat-card.active { border-color: var(--wp-primary); background: rgba(94,234,212,.06); }
.stat-card strong { font-size: 13px; }
.stat-line { display: flex; justify-content: space-between; margin-top: 7px; font-size: 11px; }
.stat-line span { color: var(--wp-sub); }
.stat-line em { font-style: normal; }
.stat-line em.err { color: var(--wp-danger); }
.trace-id { font-family: ui-monospace, monospace; font-size: 11px; color: var(--wp-gold-soft); }
.embed-frame { position: relative; height: 560px; border: 1px solid var(--wp-border); border-radius: 14px; overflow: hidden; background: rgba(8,15,28,.3); }
.embed-frame iframe { width: 100%; height: 100%; border: 0; opacity: 0; transition: opacity .4s ease; }
.embed-frame.ready iframe { opacity: 1; }
.frame-loading { position: absolute; inset: 0; display: flex; flex-direction: column; justify-content: center; gap: 14px; padding: 24px; }
.frame-loading p { margin: 0; color: var(--wp-sub); font-size: 12px; }
.section-card :deep(.el-card__header) { display: flex; justify-content: space-between; align-items: center; }
.header-meta { color: var(--wp-sub); font-size: 11px; margin-left: 10px; }
.disabled-empty :deep(.el-empty__description) { margin-top: 8px; }
.disabled-panel { display: flex; flex-direction: column; align-items: center; gap: 10px; margin-top: 4px; }
.disabled-panel p { margin: 0; color: var(--wp-sub); font-size: 12px; }
.disabled-panel .hint { font-size: 11px; }
.disabled-panel code { padding: 8px 14px; border-radius: 8px; background: rgba(8,15,28,.4); color: var(--wp-gold-soft); font-size: 12px; }
.disabled-actions { display: flex; gap: 10px; margin-top: 4px; }
</style>

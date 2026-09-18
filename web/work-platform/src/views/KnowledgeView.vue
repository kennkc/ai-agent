<template>
  <div class="knowledge-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">BODY · KNOWLEDGE</span>
        <h2>躯体知识库</h2>
        <p class="view-sub">
          知识量 / 检索 P99 / 命中率 · 检测于 {{ stats?.checked_at || '—' }}
          <span v-if="stats?.tenant_id" class="tenant-tag">租户 {{ stats.tenant_id }}</span>
        </p>
      </div>
      <div class="head-actions">
        <el-tag v-if="live" type="success" effect="dark">真实体层</el-tag>
        <el-tag v-else-if="stats && unavailable" type="danger" effect="dark">体层不可用</el-tag>
        <el-tag v-else-if="stats" type="warning" effect="dark">演示数据</el-tag>
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="plain">{{ store.dataSource.toUpperCase() }}</el-tag>
        <el-button size="small" :loading="loading" @click="refresh">刷新状态</el-button>
      </div>
    </header>

    <el-alert
      v-if="unavailable"
      :closable="false"
      class="state-alert"
      type="error"
      show-icon
      title="体层不可用：知识量与检索指标无法上报"
      :description="`${stats?.reason || 'body-service 未启动'}。启动后本页将自动切换为真实指标：cd services/java && ../../scripts/mvn-dev.sh -pl body-service spring-boot:run`"
    />

    <el-alert
      v-else-if="stats && !live"
      :closable="false"
      class="state-alert"
      type="warning"
      show-icon
      title="当前为演示数据，并非体层真实指标"
      description="未检测到 wp-bff 或未开启 API 数据源（VITE_DATA_SOURCE=api）。卡片右上角标注了数据来源，避免把演示值当作线上知识量。"
    />

    <!-- 知识量与检索指标 -->
    <section class="summary-row">
      <div class="summary-chip">
        <strong>{{ metricValue(knowledgeCount) }}</strong>
        <span>知识文档 / 切片</span>
      </div>
      <div class="summary-chip">
        <strong>{{ stats?.retrieval ? `${stats.retrieval.latency_p99_ms}ms` : '—' }}</strong>
        <span>检索 P99</span>
      </div>
      <div class="summary-chip">
        <strong>{{ percent(stats?.retrieval?.search_hit_rate) }}</strong>
        <span>检索命中率</span>
      </div>
      <div class="summary-chip">
        <strong>{{ percent(stats?.retrieval?.cache_hit_rate) }}</strong>
        <span>缓存命中率</span>
      </div>
      <div class="summary-chip">
        <strong>{{ metricValue(vectorPoints) }}</strong>
        <span>向量点</span>
      </div>
    </section>

    <p v-if="stats?.retrieval" class="sampling-hint">
      <strong>口径说明</strong>：{{ stats.note || '检索指标为采样值' }} ·
      P50 {{ stats.retrieval.latency_p50_ms }}ms / P95 {{ stats.retrieval.latency_p95_ms }}ms /
      P99 {{ stats.retrieval.latency_p99_ms }}ms（样本 {{ stats.retrieval.sample_size }} 次，
      {{ stats.retrieval.p99_basis }}）· 检索 {{ stats.retrieval.searches }} 次，重排降级 {{ stats.retrieval.rerank_degraded }} 次
    </p>

    <!-- 三层存储 -->
    <section class="tier-grid">
      <article v-for="tier in tiers" :key="tier.key" class="tier-card" :class="tier.key">
        <div class="tier-head">
          <strong>{{ tier.label }}</strong>
          <el-tag size="small" :type="tier.available ? 'success' : 'danger'" effect="dark">
            {{ tier.available ? '可用' : '不可用' }}
          </el-tag>
        </div>
        <p class="tier-role">{{ tier.role }}</p>
        <div class="tier-meta">
          <span>后端</span>
          <em>{{ tier.backend }}</em>
        </div>
        <div v-if="tier.extra" class="tier-meta">
          <span>详情</span>
          <em>{{ tier.extra }}</em>
        </div>
      </article>
    </section>

    <!-- 检索测试 -->
    <el-card shadow="never" class="search-card">
      <template #header>
        <div class="card-head">
          <span>检索测试</span>
          <span class="card-hint">语义检索（缓存优先 → 向量召回 → 重排）· 命中片段按 matched_terms 高亮</span>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="query"
          placeholder="输入问题，例如：知识以什么方式入库？"
          clearable
          @keyup.enter="runSearch"
        />
        <el-select v-model="topK" class="topk-select" size="default">
          <el-option v-for="option in [3, 5, 10, 20]" :key="option" :label="`TOP ${option}`" :value="option" />
        </el-select>
        <el-button type="primary" :loading="searching" :disabled="!query.trim()" @click="runSearch">检索</el-button>
      </div>

      <div v-if="result" class="result-meta">
        <span>命中 {{ result.hit_count ?? result.hits.length }} 条</span>
        <span>耗时 {{ result.latency_ms ?? 0 }}ms</span>
        <span v-if="result.parsed_terms?.length">解析词：{{ result.parsed_terms.slice(0, 8).join(' / ') }}</span>
        <el-tag v-if="result.data_source === 'mock'" size="small" type="warning" effect="plain">演示结果</el-tag>
      </div>

      <el-alert
        v-if="result && !result.available"
        :closable="false"
        type="error"
        show-icon
        class="result-alert"
        :title="result.reason || '体层检索不可用'"
        description="检索依赖 body-service 与 Qdrant；请先启动中间件（docker compose up -d qdrant redis postgres）与服务本体。"
      />

      <ol v-else-if="result?.hits?.length" class="hit-list">
        <li v-for="hit in result.hits" :key="hit.chunk_id" class="hit-item">
          <div class="hit-head">
            <span class="hit-rank">#{{ hit.rank }}</span>
            <strong class="hit-title">{{ hit.title }}</strong>
            <el-tag v-if="hit.heading" size="small" effect="plain">{{ hit.heading }}</el-tag>
            <span class="hit-score">
              召回 {{ formatScore(hit.score) }} · 重排 {{ formatScore(hit.rerank_score) }}
            </span>
          </div>
          <p class="hit-snippet">
            <template v-for="(segment, index) in highlightSegments(hit.snippet, hit.matched_terms)" :key="index">
              <mark v-if="segment.hit">{{ segment.text }}</mark>
              <template v-else>{{ segment.text }}</template>
            </template>
          </p>
          <div class="hit-foot">
            <span>{{ hit.doc_id }} · 块 {{ hit.chunk_index ?? 0 }} · 来源 {{ hit.source || '-' }}</span>
            <span v-if="hit.ingest_time_iso">入库 {{ hit.ingest_time_iso }}</span>
          </div>
        </li>
      </ol>

      <el-empty
        v-else-if="result && result.available"
        :image-size="72"
        description="未检索到相关内容：知识库可能为空，或该问题与已入库文档不相关"
      />
      <p v-else-if="!result" class="search-empty">输入问题后点击「检索」，将按语义相似度返回 TOP-K 片段</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { dataProvider } from '../api/provider'
import { useAppStore } from '../stores/app'
import type { KnowledgeSearchResult, KnowledgeStats } from '../types'

const store = useAppStore()
const loading = ref(false)
const searching = ref(false)
const stats = ref<KnowledgeStats | null>(null)
const result = ref<KnowledgeSearchResult | null>(null)
const query = ref('')
const topK = ref(5)

const live = computed(() => stats.value?.data_source === 'live' && stats.value?.available === true)
const unavailable = computed(() => stats.value !== null && stats.value.available === false)

const knowledgeCount = computed(() => {
  const knowledge = stats.value?.knowledge
  return knowledge ? `${knowledge.documents} / ${knowledge.chunks}` : null
})

const vectorPoints = computed(() => stats.value?.knowledge?.vector_points ?? null)

const tiers = computed(() => {
  const storage = stats.value?.storage
  return [
    { key: 'hot', label: 'HOT · 热层', role: '缓存优先策略，命中即返回', data: storage?.hot },
    { key: 'warm', label: 'WARM · 温层', role: '向量主库，HNSW 语义召回', data: storage?.warm },
    { key: 'cold', label: 'COLD · 冷层', role: '元数据真相源，全量归档', data: storage?.cold },
  ].map(item => ({
    key: item.key,
    label: item.label,
    role: item.role,
    available: item.data?.available === true,
    backend: item.data?.store || item.data?.backend || '—',
    extra: item.data?.vector_size
      ? `${item.data?.collection || 'collection'} · ${item.data.vector_size} 维`
      : (item.data?.detail || ''),
  }))
})

function metricValue(value: string | number | null | undefined) {
  return value === null || value === undefined ? '—' : String(value)
}

function percent(value?: number) {
  if (value === undefined || value === null) return '—'
  return `${Math.round(value * 1000) / 10}%`
}

function formatScore(score?: number) {
  return score === undefined || score === null ? '—' : score.toFixed(3)
}

/**
 * 将片段按命中词切分为「命中 / 非命中」段，交由模板渲染 <mark>。
 * 用分段而不是 v-html，避免把上游正文当作 HTML 注入。
 */
function highlightSegments(text: string, terms: string[] = []): Array<{ text: string; hit: boolean }> {
  const source = String(text ?? '')
  if (!source) return [{ text: '', hit: false }]
  const valid = [...new Set(terms.filter(term => term && source.includes(term)))]
    .sort((a, b) => b.length - a.length)
  if (!valid.length) return [{ text: source, hit: false }]
  const segments: Array<{ text: string; hit: boolean }> = []
  let index = 0
  while (index < source.length) {
    const matched = valid.find(term => source.startsWith(term, index))
    if (matched) {
      segments.push({ text: matched, hit: true })
      index += matched.length
      continue
    }
    const last = segments[segments.length - 1]
    if (last && !last.hit) last.text += source[index]
    else segments.push({ text: source[index], hit: false })
    index += 1
  }
  return segments
}

async function refresh() {
  loading.value = true
  try {
    stats.value = await dataProvider.getKnowledge()
  } finally {
    loading.value = false
  }
}

async function runSearch() {
  const value = query.value.trim()
  if (!value) return
  searching.value = true
  try {
    result.value = await dataProvider.searchKnowledge(value, topK.value)
  } finally {
    searching.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.knowledge-view { display: flex; flex-direction: column; gap: 16px; }
.view-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
.view-head h2 { margin: 2px 0 4px; font-size: 20px; letter-spacing: .04em; }
.view-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .22em; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.tenant-tag { margin-left: 8px; color: var(--wp-gold-soft); }
.head-actions { display: flex; align-items: center; gap: 10px; }
.state-alert :deep(.el-alert__description) { font-family: ui-monospace, monospace; font-size: 11px; }
.summary-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.summary-chip { padding: 14px 16px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .05); display: flex; flex-direction: column; gap: 6px; }
.summary-chip strong { font-size: 20px; color: var(--wp-text); }
.summary-chip span { color: var(--wp-sub); font-size: 11px; }
.sampling-hint { margin: 0; padding: 8px 12px; border-left: 2px solid var(--wp-gold-soft); border-radius: 0 8px 8px 0; background: rgba(148, 163, 184, .06); color: var(--wp-sub); font-size: 11px; line-height: 1.7; }
.sampling-hint strong { color: var(--wp-gold-soft); }
.tier-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.tier-card { padding: 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .05); display: flex; flex-direction: column; gap: 8px; }
.tier-card.hot { border-left: 2px solid var(--wp-danger); }
.tier-card.warm { border-left: 2px solid var(--wp-primary); }
.tier-card.cold { border-left: 2px solid var(--wp-gold-soft); }
.tier-head { display: flex; justify-content: space-between; align-items: center; }
.tier-role { margin: 0; color: var(--wp-sub); font-size: 11px; }
.tier-meta { display: flex; justify-content: space-between; font-size: 11px; }
.tier-meta span { color: var(--wp-sub); }
.tier-meta em { font-style: normal; color: var(--wp-text); }
.search-card :deep(.el-card__header) { padding: 12px 16px; }
.card-head { display: flex; align-items: baseline; gap: 10px; }
.card-hint { color: var(--wp-sub); font-size: 11px; }
.search-bar { display: flex; gap: 10px; }
.topk-select { width: 110px; flex: none; }
.result-meta { display: flex; flex-wrap: wrap; gap: 14px; margin: 12px 0 4px; color: var(--wp-sub); font-size: 11px; }
.result-alert { margin-top: 10px; }
.hit-list { list-style: none; margin: 12px 0 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.hit-item { padding: 12px 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .04); }
.hit-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.hit-rank { color: var(--wp-primary); font-family: ui-monospace, monospace; font-size: 12px; }
.hit-title { font-size: 13px; }
.hit-score { margin-left: auto; color: var(--wp-sub); font-size: 11px; }
.hit-snippet { margin: 8px 0 6px; font-size: 12px; line-height: 1.8; color: var(--wp-text); word-break: break-word; }
.hit-snippet mark { padding: 0 2px; border-radius: 3px; background: rgba(244, 213, 141, .28); color: inherit; }
.hit-foot { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; color: var(--wp-sub); font-size: 11px; }
.search-empty { margin: 6px 0 0; color: var(--wp-sub); font-size: 12px; }
</style>

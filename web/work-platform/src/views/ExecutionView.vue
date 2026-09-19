<template>
  <div class="execution-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">EFFECTORS · R5 / IN-06</span>
        <h2>执行视图</h2>
        <p class="view-sub">
          工具调用流与沙箱隔离状态（四肢层 tool-executor 真实数据） · 检测于 {{ overview?.checked_at || '—' }}
        </p>
      </div>
      <div class="head-actions">
        <el-tag v-if="liveData && available" type="success" effect="dark">真实调用记录</el-tag>
        <el-tag v-else-if="overview" type="warning" effect="dark">演示数据 · 四肢层未连接</el-tag>
        <el-tag v-if="partial" type="warning" effect="plain">分片缺失</el-tag>
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="plain">{{ store.dataSource.toUpperCase() }}</el-tag>
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </header>

    <el-alert
      v-if="overview && !liveData"
      :closable="false"
      class="pad-alert"
      type="warning"
      show-icon
      title="当前为演示数据，并非工具的真实执行记录"
      description="未检测到 wp-bff（127.0.0.1:8090）。启动 BFF 与 tool-executor 后本页自动切换为真实数据：cd services/node/wp-bff && npm start"
    />

    <el-alert
      v-if="partial"
      :closable="false"
      class="pad-alert"
      type="warning"
      show-icon
      :title="`部分数据域未取到：${partialReasonText}`"
      :description="partialDescription"
    />

    <!-- 四肢层整体不可用：不编造任何工具与调用记录 -->
    <el-empty v-if="overview && !available" description="四肢层不可用" :image-size="96">
      <div class="down-panel">
        <p>{{ overview.reason }}</p>
        <p v-if="overview.reason_code" class="hint">
          失败原因码 <code>{{ overview.reason_code }}</code>
          <template v-if="overview.reason_code === 'timeout'"> —— 对端在预算内未返回（冷启动 / 负载高），重试通常即恢复。</template>
          <template v-else-if="overview.reason_code === 'endpoint_missing'"> —— 端点不存在，多半是 tool-executor 版本旧于本 BFF。</template>
        </p>
        <p class="hint">工具调用记录与沙箱状态均无法上报，故本页不展示任何指标。可执行以下命令后重试：</p>
        <code>cd services/java && ../scripts/mvn-dev.sh -pl tool-executor spring-boot:run</code>
        <div class="down-actions">
          <el-button type="primary" size="small" @click="refresh">重新检测</el-button>
          <el-button size="small" @click="goOverview">返回总览</el-button>
        </div>
      </div>
    </el-empty>

    <template v-else-if="overview">
      <section class="summary-row">
        <div class="summary-chip"><strong>{{ tools.length }}</strong><span>已注册工具</span></div>
        <div class="summary-chip ok"><strong>{{ successRateText }}</strong><span>窗口成功率</span></div>
        <div class="summary-chip bad"><strong>{{ metrics?.blocked_calls ?? '—' }}</strong><span>守卫拦截</span></div>
        <div class="summary-chip"><strong>{{ metrics?.total_calls ?? '—' }}</strong><span>累计调用</span></div>
        <div class="summary-chip"><strong>{{ p99Text }}</strong><span>P99 耗时</span></div>
      </section>

      <!-- 沙箱隔离边界：degraded=true 时必须说清「非隔离」而不是继续打绿点 -->
      <section class="sandbox-card" :class="{ degraded: sandbox?.degraded }">
        <div class="sb-head">
          <strong>代码执行沙箱</strong>
          <el-tag size="small" :type="sandboxTag.type" effect="dark">{{ sandboxTag.text }}</el-tag>
        </div>
        <p v-if="!sandbox" class="sb-note">沙箱状态未取到（该分片不可用）。</p>
        <template v-else>
          <div class="sb-grid">
            <div class="sb-item"><span>生效后端</span><strong>{{ sandbox.active_backend }}</strong></div>
            <div class="sb-item"><span>真隔离边界</span><strong>{{ sandbox.isolated ? '是' : '否' }}</strong></div>
            <div class="sb-item"><span>单次超时</span><strong>{{ sandbox.timeout_ms }} ms</strong></div>
            <div class="sb-item"><span>内存上限</span><strong>{{ sandbox.memory_mb }} MB</strong></div>
            <div class="sb-item"><span>Docker 可用</span><strong>{{ sandbox.docker_available ? '是' : '否' }}</strong></div>
            <div class="sb-item"><span>子进程兜底</span><strong>{{ sandbox.process_fallback_available ? '可用' : '不可用' }}</strong></div>
          </div>
          <p class="sb-note">{{ sandbox.note }}</p>
        </template>
      </section>

      <section class="tool-section">
        <h3 class="section-title">工具注册表<span class="section-sub">schema 指纹变更即触发 L1 契约测试（IN-06）</span></h3>
        <div class="tool-grid">
          <article v-for="tool in tools" :key="tool.name" class="tool-card" :class="{ deprecated: tool.deprecated }">
            <div class="tool-head">
              <div class="tool-title">
                <strong>{{ tool.name }}</strong>
                <el-tag size="small" effect="plain" class="ver-tag">v{{ tool.version }}</el-tag>
              </div>
              <el-tag size="small" :type="breakerType(tool.name)" effect="plain">
                {{ breakerLabel(tool.name) }}
              </el-tag>
            </div>
            <p class="tool-desc">{{ tool.description || '（未登记描述）' }}</p>
            <div class="tool-tags">
              <el-tag v-if="tool.sandbox_required" size="small" type="warning" effect="plain">需沙箱</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">沙箱外</el-tag>
              <el-tag v-if="tool.deprecated" size="small" type="danger" effect="plain">
                已废弃 · {{ tool.removed_after || '待定' }} 移除
              </el-tag>
              <el-tag size="small" effect="plain">超时 {{ tool.timeout_ms }}ms</el-tag>
            </div>
            <div v-if="tool.whitelist_domains?.length" class="tool-domains">
              <span>域名白名单</span>
              <code v-for="domain in tool.whitelist_domains" :key="domain">{{ domain }}</code>
            </div>
            <div class="tool-foot">
              <span class="schema-hash" :title="tool.schema_hash || ''">schema {{ (tool.schema_hash || '').slice(0, 8) || '—' }}</span>
              <div class="foot-actions">
                <el-button size="small" plain @click="openImpact(tool)">影响面</el-button>
                <el-button size="small" type="primary" plain @click="openRun(tool)">试运行</el-button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="audit-section">
        <h3 class="section-title">
          调用审计
          <span class="section-sub">
            最近 {{ auditItems.length }} 条 · 落库 {{ auditStats?.backend || '—' }}
            <template v-if="auditStats"> · 累计 {{ auditStats.total }} 次（拦截 {{ auditStats.blocked }} / 沙箱 {{ auditStats.sandboxed }}）</template>
          </span>
        </h3>
        <p v-if="!auditItems.length" class="empty-line">暂无调用记录（或审计分片不可用）。</p>
        <ul v-else class="audit-list">
          <li v-for="entry in auditItems" :key="entry.audit_id" class="audit-row" :class="{ blocked: !entry.success }">
            <div class="audit-main">
              <div class="audit-line1">
                <span class="state-dot" :class="entry.success ? 'ok' : 'bad'" />
                <strong>{{ entry.tool_name }}</strong>
                <el-tag v-if="entry.error_code" size="small" type="danger" effect="plain">{{ entry.error_code }}</el-tag>
                <el-tag v-if="entry.sandboxed" size="small" type="warning" effect="plain">{{ entry.sandbox_backend || 'sandbox' }}</el-tag>
                <span class="audit-time">{{ formatTime(entry.created_at) }}</span>
              </div>
              <code class="audit-args">{{ entry.args || '—' }}</code>
              <p v-if="!entry.success" class="audit-error">{{ entry.error_message }}</p>
              <p v-else-if="entry.output" class="audit-output">{{ entry.output }}</p>
            </div>
            <div class="audit-meta">
              <span>{{ entry.latency_ms }} ms</span>
              <span class="audit-id">{{ entry.audit_id }}</span>
            </div>
          </li>
        </ul>
      </section>

      <section v-if="changeLog.length" class="changelog-section">
        <h3 class="section-title">
          注册表变更
          <span class="section-sub">{{ registry?.deprecation_policy || '' }}</span>
        </h3>
        <ul class="changelog-list">
          <li v-for="(item, index) in changeLog" :key="`${item.tool_name}-${index}`">
            <span class="cl-action">{{ item.action }}</span>
            <strong>{{ item.tool_name }}</strong>
            <span class="cl-ver">v{{ item.version }}</span>
            <span class="cl-impact">{{ impactText(item.impact) }}</span>
            <span class="cl-time">{{ timeText(item.changed_at) }}</span>
          </li>
        </ul>
      </section>
    </template>

    <!-- IN-06 变更影响分析：谁会被这次工具契约变更波及 -->
    <el-drawer v-model="impactVisible" :title="`变更影响分析 · ${impactTool?.name || ''}`" size="440px">
      <div v-if="impactLoading" class="impact-loading">正在读取影响面…</div>
      <template v-else-if="impactReport">
        <el-alert
          v-if="impactReport.data_source === 'mock'"
          :closable="false"
          type="warning"
          show-icon
          class="pad-alert"
          title="当前为演示数据"
          :description="impactReport.note || '未连到真实 tool-executor，影响面为本地演示值'"
        />
        <div class="impact-grid">
          <div class="impact-item"><span>当前版本</span><strong>{{ impactReport.current_version || '—' }}</strong></div>
          <div class="impact-item"><span>schema 变更史</span><strong>{{ impactReport.schema_changed ? '有' : '无' }}</strong></div>
          <div class="impact-item"><span>影响分析来源</span><strong>{{ impactReport.data_source === 'mock' ? '演示' : '真实' }}</strong></div>
        </div>

        <h4 class="impact-title">受影响 Agent</h4>
        <p v-if="!impactReport.affected_agents.length" class="impact-empty">无登记消费方</p>
        <ul v-else class="impact-list">
          <li v-for="agent in impactReport.affected_agents" :key="agent">
            <el-tag size="small" effect="plain">{{ agent }}</el-tag>
          </li>
        </ul>

        <h4 class="impact-title">受影响流程</h4>
        <p v-if="!impactReport.affected_flows.length" class="impact-empty">无登记消费方</p>
        <ul v-else class="impact-list">
          <li v-for="flow in impactReport.affected_flows" :key="flow">
            <el-tag size="small" type="warning" effect="plain">{{ flow }}</el-tag>
          </li>
        </ul>

        <el-alert
          v-if="impactReport.contract_test_required"
          :closable="false"
          type="error"
          show-icon
          class="pad-alert"
          title="schema 已变更且存在消费方：需回归其 L1 契约测试"
        />

        <p class="impact-note">{{ impactReport.note }}</p>
        <p class="impact-sub">schema 指纹 {{ impactReport.schema_hash || '—' }}</p>
      </template>
      <p v-else class="impact-empty">影响面不可用（端点未实现或上游不可达）。</p>
    </el-drawer>

    <!-- 试运行：写路径，真实模式下需控制令牌（开发环境由 Vite 代理注入） -->
    <el-dialog v-model="runVisible" :title="`试运行 ${runTool?.name || ''}`" width="560px">
      <p class="dialog-hint">
        参数按 JSON 传入。Mock 数据源下只做守卫复演、不真实执行；切换 <code>VITE_DATA_SOURCE=api</code> 后经 BFF 触达 tool-executor。
      </p>
      <el-input v-model="runArgs" type="textarea" :rows="4" spellcheck="false" />
      <template v-if="runResult" #footer>
        <div class="run-result" :class="{ ok: runResult.success, bad: runResult.success === false }">
          <div class="run-line">
            <el-tag size="small" :type="runResult.success ? 'success' : runResult.success === false ? 'danger' : 'info'" effect="dark">
              {{ runResult.success === undefined ? '未生效' : runResult.success ? '执行成功' : '被拒绝' }}
            </el-tag>
            <span v-if="runResult.error_code" class="run-code">{{ runResult.error_code }}</span>
            <span class="run-lat">工具层 {{ runResult.latency_ms ?? '—' }}ms · BFF {{ runResult.bff_latency_ms ?? '—' }}ms</span>
          </div>
          <p v-if="runResult.error_message" class="run-msg">{{ runResult.error_message }}</p>
          <p v-else-if="runResult.reason" class="run-msg">{{ runResult.reason }}</p>
          <pre v-if="runResult.output" class="run-output">{{ pretty(runResult.output) }}</pre>
          <span v-if="runResult.audit_id" class="run-audit">审计号 {{ runResult.audit_id }}</span>
        </div>
        <el-button size="small" @click="runVisible = false">关闭</el-button>
      </template>
      <template v-else #footer>
        <el-button size="small" @click="runVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="runRunning" @click="doRun">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { dataProvider } from '../api/provider'
import { useAppStore } from '../stores/app'
import type { ExecutionOverview, ExecutionTool, ToolExecutionResult, ToolImpactReport } from '../types'

const store = useAppStore()
const router = useRouter()
const loading = ref(false)
const overview = ref<ExecutionOverview | null>(null)

// 注意语义：liveData=false 只表示"当前展示的是演示回落数据"，与 available=false
// （四肢层不可用、连演示都必须说清不可用）是两件事，不要合并成一个判断。
const liveData = computed(() => overview.value?.data_source !== 'mock')
const available = computed(() => overview.value?.available === true)
const tools = computed(() => overview.value?.tools || [])
const metrics = computed(() => overview.value?.metrics?.metrics || null)
const sandbox = computed(() => overview.value?.sandbox || null)
const auditItems = computed(() => overview.value?.audit?.items || [])
const auditStats = computed(() => overview.value?.audit_stats?.audit || null)
const changeLog = computed(() => overview.value?.registry?.change_log || [])
const registry = computed(() => overview.value?.registry || null)
const breakers = computed(() => overview.value?.metrics?.circuit_breakers || [])
const partial = computed(() => overview.value?.partial === true)
const partialReasons = computed(() => overview.value?.partial_reasons || [])

/**
 * 分片缺失原因码 → 人话。
 *
 * 关键是**后缀语义不同**：`_timeout` 是"慢"，`_missing` 是"端点不存在"，`_unavailable` 是真连不上。
 * 早期把三者合成都写成"不可用"，导致沙箱冷探测 5.7s 超过 BFF 预算时被叙述成
 * "沙箱不可用"——与服务侧真相恰好相反。这里逐码区分，不让界面再犯同一个错。
 */
const PARTIAL_REASON_LABELS: Record<string, string> = {
  registry_timeout: '注册表读取超时（慢，非缺失）',
  registry_missing: '注册表端点不存在（服务版本可能较旧）',
  registry_unavailable: '注册表不可读',
  metrics_timeout: '指标读取超时（慢，非缺失）',
  metrics_missing: '指标端点不存在（服务版本可能较旧）',
  metrics_unavailable: '指标不可读',
  sandbox_timeout: '沙箱状态读取超时（慢，非缺失）',
  sandbox_missing: '沙箱端点不存在（服务版本可能较旧）',
  sandbox_unavailable: '沙箱状态不可读',
  audit_stats_timeout: '审计统计读取超时（慢，非缺失）',
  audit_stats_missing: '审计统计端点不存在（服务版本可能较旧）',
  audit_stats_unavailable: '审计统计不可读',
  audit_timeout: '审计明细读取超时（慢，非缺失）',
  audit_missing: '审计明细端点不存在（服务版本可能较旧）',
  audit_unavailable: '审计明细不可读',
}

function labelOfReason(code: string) {
  return PARTIAL_REASON_LABELS[code] || code
}

const partialReasonText = computed(() => partialReasons.value.map(labelOfReason).join('、') || '未知')

const partialDescription = computed(() => {
  const note = overview.value?.partial_note
  const budget = overview.value?.probe_budget_ms
  const budgetText = budget ? `子请求预算：默认 ${budget.default}ms / 沙箱 ${budget.sandbox}ms。` : ''
  return `${note ? `${note} ` : ''}${budgetText}缺失分片按「不可用」展示，不以 0 或空数组冒充真实指标。`
})

// success_rate 为 null 表示"窗口内无样本"，此时显示 — 而不是 0（0 会被读成"全失败"）
const successRateText = computed(() => {
  const rate = metrics.value?.success_rate
  return typeof rate === 'number' ? `${Math.round(rate * 1000) / 10}%` : '—'
})
const p99Text = computed(() => (metrics.value ? `${metrics.value.p99_ms}ms` : '—'))

const sandboxTag = computed(() => {
  if (!sandbox.value) return { type: 'info' as const, text: '状态未知' }
  if (!sandbox.value.enabled) return { type: 'info' as const, text: '已禁用' }
  if (sandbox.value.degraded || !sandbox.value.isolated) return { type: 'warning' as const, text: '已降级 · 非隔离边界' }
  return { type: 'success' as const, text: '真隔离运行中' }
})

function breakerOf(name: string) {
  return breakers.value.find(item => item.tool_name === name)
}
function breakerType(name: string) {
  return breakerOf(name)?.state === 'open' ? 'danger' as const : 'success' as const
}
function breakerLabel(name: string) {
  const breaker = breakerOf(name)
  if (!breaker) return '熔断未知'
  if (breaker.state === 'open') return `熔断打开 · 连续失败 ${breaker.consecutive_failures}`
  return breaker.consecutive_failures ? `正常 · 连续失败 ${breaker.consecutive_failures}` : '正常'
}

function formatTime(epoch?: number) {
  if (!epoch) return '—'
  return new Date(epoch).toLocaleTimeString('zh-CN', { hour12: false })
}
function pretty(value: unknown) {
  try { return typeof value === 'string' ? value : JSON.stringify(value, null, 2) } catch { return String(value) }
}
// 影响面在真实返回里是数组（可为空）；空数组不能渲染成 "[]"，要说清「无受影响面」
function impactText(impact: string[] | string | undefined) {
  if (Array.isArray(impact)) return impact.length ? impact.join('、') : '无受影响面'
  return impact || '无受影响面'
}
// 时间口径宽松：真实为 epoch 毫秒，也可能回 ISO 串
function timeText(value?: number | string) {
  if (value === undefined || value === null || value === '') return '—'
  if (typeof value === 'number') return new Date(value).toLocaleTimeString('zh-CN', { hour12: false })
  return value
}

// ─────────── IN-06 影响面 ───────────
const impactVisible = ref(false)
const impactLoading = ref(false)
const impactTool = ref<ExecutionTool | null>(null)
const impactReport = ref<ToolImpactReport | null>(null)

async function openImpact(tool: ExecutionTool) {
  impactTool.value = tool
  impactReport.value = null
  impactVisible.value = true
  impactLoading.value = true
  try {
    impactReport.value = await dataProvider.getToolImpact(tool.name)
  } catch (error) {
    ElMessage.error(`读取影响面失败：${(error as Error)?.message || '未知错误'}`)
  } finally {
    impactLoading.value = false
  }
}

// ─────────── 试运行 ───────────
const runVisible = ref(false)
const runTool = ref<ExecutionTool | null>(null)
const runArgs = ref('{}')
const runRunning = ref(false)
const runResult = ref<ToolExecutionResult | null>(null)

// 演示参数键与真实 schema 一致：calculator=expr / code=code / http=url
// （写成 expression / source 会在真实服务上被 schema 校验直接拒绝）
function defaultArgs(tool: ExecutionTool) {
  if (tool.name === 'calculator') return '{"expr":"(128 * 1.07 + 36) / 2"}'
  if (tool.name === 'http') return '{"url":"https://api.open-meteo.com/v1/forecast?latitude=31.2&longitude=121.5"}'
  return '{"code":"print(sum(range(100)))"}'
}

function openRun(tool: ExecutionTool) {
  runTool.value = tool
  runArgs.value = defaultArgs(tool)
  runResult.value = null
  runVisible.value = true
}

async function doRun() {
  if (!runTool.value) return
  let args: Record<string, unknown>
  try {
    args = JSON.parse(runArgs.value || '{}')
  } catch {
    ElMessage.error('参数不是合法 JSON')
    return
  }
  runRunning.value = true
  try {
    runResult.value = await dataProvider.executeTool(runTool.value.name, args)
  } catch (error) {
    ElMessage.error(`执行请求失败：${(error as Error)?.message || '未知错误'}`)
  } finally {
    runRunning.value = false
  }
  // 执行结果已落审计，回读一次让列表与指标同步（失败也不影响结果面板展示）
  await refresh()
}

async function refresh() {
  loading.value = true
  try {
    overview.value = await dataProvider.getExecution()
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
.execution-view { display: flex; flex-direction: column; gap: 16px; }
.view-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
.view-head h2 { margin: 2px 0 4px; font-size: 20px; letter-spacing: .04em; }
.view-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .22em; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.pad-alert { border-radius: 10px; }
.pad-alert :deep(.el-alert__description) { font-size: 12px; }

.down-panel { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.down-panel p { margin: 0; color: var(--wp-sub); font-size: 12px; }
.down-panel .hint { font-size: 11px; }
.down-panel code { padding: 8px 14px; border-radius: 8px; background: rgba(8,15,28,.4); color: var(--wp-gold-soft); font-size: 12px; }
.down-actions { display: flex; gap: 10px; margin-top: 4px; }

.summary-row { display: flex; gap: 12px; flex-wrap: wrap; }
.summary-chip { display: flex; align-items: baseline; gap: 8px; padding: 10px 16px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.05); }
.summary-chip strong { font-size: 20px; }
.summary-chip span { color: var(--wp-sub); font-size: 11px; }
.summary-chip.ok strong { color: var(--wp-success); }
.summary-chip.bad strong { color: var(--wp-danger); }

.sandbox-card { padding: 14px 16px; border: 1px solid var(--wp-border); border-radius: 14px; background: linear-gradient(135deg, rgba(94,234,212,.05), rgba(148,163,184,.04)); }
.sandbox-card.degraded { border-color: rgba(212,175,55,.45); }
.sb-head { display: flex; justify-content: space-between; align-items: center; }
.sb-head strong { font-size: 14px; }
.sb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; margin-top: 12px; }
.sb-item { display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(8,15,28,.22); }
.sb-item span { color: var(--wp-sub); font-size: 9px; }
.sb-item strong { font-size: 13px; }
.sb-note { margin: 10px 0 0; color: var(--wp-sub); font-size: 11px; line-height: 1.6; }

.section-title { display: flex; align-items: baseline; gap: 10px; margin: 4px 0 10px; font-size: 14px; }
.section-sub { color: var(--wp-sub); font-size: 11px; font-weight: 400; }
.tool-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.tool-card { padding: 14px 16px; border: 1px solid var(--wp-border); border-radius: 14px; background: linear-gradient(135deg, rgba(148,163,184,.06), rgba(94,234,212,.025)); }
.tool-card.deprecated { opacity: .72; border-style: dashed; }
.tool-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.tool-title { display: flex; align-items: center; gap: 8px; }
.tool-title strong { font-size: 14px; }
.ver-tag { font-family: ui-monospace, monospace; }
.tool-desc { margin: 8px 0 0; color: var(--wp-sub); font-size: 11px; line-height: 1.6; min-height: 30px; }
.tool-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.tool-domains { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
.tool-domains span { color: var(--wp-sub); font-size: 10px; }
.tool-domains code { padding: 2px 7px; border-radius: 6px; background: rgba(8,15,28,.35); color: var(--wp-gold-soft); font-size: 10px; }
.tool-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.schema-hash { color: var(--wp-sub); font-family: ui-monospace, monospace; font-size: 10px; }

.audit-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.audit-row { display: flex; justify-content: space-between; gap: 14px; padding: 10px 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148,163,184,.04); }
.audit-row.blocked { border-color: rgba(248,113,113,.35); background: rgba(248,113,113,.05); }
.audit-main { min-width: 0; flex: 1; }
.audit-line1 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.audit-line1 strong { font-size: 13px; }
.state-dot { width: 8px; height: 8px; border-radius: 50%; }
.state-dot.ok { background: var(--wp-success); box-shadow: 0 0 10px rgba(52,211,153,.55); }
.state-dot.bad { background: var(--wp-danger); box-shadow: 0 0 10px rgba(248,113,113,.55); }
.audit-time { color: var(--wp-sub); font-size: 10px; }
.audit-args { display: block; margin-top: 6px; color: var(--wp-sub); font-size: 11px; word-break: break-all; }
.audit-error { margin: 4px 0 0; color: var(--wp-danger); font-size: 11px; }
.audit-output { margin: 4px 0 0; color: var(--wp-sub); font-size: 11px; word-break: break-all; }
.audit-meta { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; color: var(--wp-sub); font-size: 10px; flex-shrink: 0; }
.audit-id { font-family: ui-monospace, monospace; }
.empty-line { margin: 0; color: var(--wp-sub); font-size: 12px; }

.changelog-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.changelog-list li { display: flex; align-items: baseline; gap: 10px; padding: 7px 12px; border: 1px solid var(--wp-border); border-radius: 10px; background: rgba(148,163,184,.04); font-size: 11px; }
.cl-action { padding: 1px 7px; border-radius: 6px; background: rgba(212,175,55,.14); color: var(--wp-gold-soft); font-size: 10px; }
.cl-ver { font-family: ui-monospace, monospace; color: var(--wp-sub); }
.cl-impact { color: var(--wp-sub); flex: 1; min-width: 0; }
.cl-time { color: var(--wp-sub); font-size: 10px; }

.dialog-hint { margin: 0 0 10px; color: var(--wp-sub); font-size: 11px; line-height: 1.6; }
.dialog-hint code { padding: 1px 5px; border-radius: 5px; background: rgba(8,15,28,.35); color: var(--wp-gold-soft); }
.run-result { flex: 1; margin-right: 12px; text-align: left; }
.run-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.run-code { color: var(--wp-danger); font-family: ui-monospace, monospace; font-size: 11px; }
.run-lat { color: var(--wp-sub); font-size: 10px; }
.run-msg { margin: 6px 0 0; color: var(--wp-sub); font-size: 11px; }
.run-output { margin: 8px 0 0; padding: 8px 10px; max-height: 140px; overflow: auto; border-radius: 8px; background: rgba(8,15,28,.35); color: var(--wp-gold-soft); font-size: 11px; }
.run-audit { display: block; margin-top: 6px; color: var(--wp-sub); font-size: 10px; }

/* IN-06 影响面抽屉 */
.foot-actions { display: flex; gap: 6px; }
.impact-loading { color: var(--wp-sub); font-size: 12px; }
.impact-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 10px 0 14px; }
.impact-item { display: flex; flex-direction: column; gap: 3px; padding: 8px 10px; border: 1px solid var(--wp-border); border-radius: 10px; }
.impact-item span { color: var(--wp-sub); font-size: 10px; }
.impact-item strong { font-size: 12px; }
.impact-title { margin: 12px 0 6px; font-size: 12px; }
.impact-list { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 6px; }
.impact-empty { margin: 0; color: var(--wp-sub); font-size: 11px; }
.impact-note { margin: 14px 0 0; color: var(--wp-sub); font-size: 11px; line-height: 1.6; }
.impact-sub { margin: 6px 0 0; color: var(--wp-sub); font-family: ui-monospace, monospace; font-size: 10px; }
</style>
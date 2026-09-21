<template>
  <div class="models-view">
    <header class="view-head">
      <div>
        <span class="view-kicker">BRAIN · LLM ACCESS</span>
        <h2>模型接入配置</h2>
        <p class="view-sub">
          按<strong>功能角色</strong>为大模型接口配置接入参数 · 真相源为 nlp-service <code>llm_model_config</code> 表
          <span v-if="list?.tenant_id" class="tenant-tag">租户 {{ list.tenant_id }}</span>
        </p>
      </div>
      <div class="head-actions">
        <el-tag v-if="live" type="success" effect="dark">真实配置</el-tag>
        <el-tag v-else-if="list && unavailable" type="danger" effect="dark">配置域不可达</el-tag>
        <el-tag v-else type="info" effect="plain">加载中</el-tag>
        <el-tag v-if="list?.storage" :type="list.storage.degraded ? 'warning' : 'success'" effect="plain">
          存储 {{ list.storage.backend || '—' }}
        </el-tag>
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="plain">{{ store.dataSource.toUpperCase() }}</el-tag>
        <div class="model-toolbar-actions">
          <el-button
            class="model-toolbar-button"
            :icon="Plus"
            data-testid="model-create"
            @click="openCreate"
          >
            新增模型配置
          </el-button>
          <el-button class="model-toolbar-button" :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
          <el-button class="model-toolbar-button" :icon="RefreshRight" :loading="reloading" @click="doReload">重载引擎</el-button>
        </div>
      </div>
    </header>

    <!-- 读不到 ≠ 没配过：这两种情况必须分开表达，否则用户会去重复新建 -->
    <el-alert
      v-if="unavailable"
      :closable="false"
      class="state-alert"
      type="error"
      show-icon
      title="大脑层不可用：模型配置读不到"
      :description="`${list?.reason || 'nlp-service /api/nlp/models 不可达'}。此处不代表“尚未配置模型”，启动后本页自动恢复：cd services/python/nlp-service && uvicorn app.main:app --port 8000`"
    />

    <el-alert
      v-else-if="list?.storage?.degraded"
      :closable="false"
      class="state-alert"
      type="warning"
      show-icon
      title="配置存储已降级为进程内存储，重启即丢失"
      :description="`${list.storage.reason || 'PostgreSQL 不可用'} · DSN ${list.storage.dsn || '-'}。PG 恢复后重新保存任一配置即可自动改回持久化。`"
    />

    <el-alert
      v-else-if="list?.storage?.key_source_warning"
      :closable="false"
      class="state-alert"
      type="warning"
      show-icon
      title="主密钥来自本地文件，仅限开发环境"
      :description="`${list.storage.key_source_warning}。生产环境必须通过 MODEL_CONFIG_MASTER_KEY 注入，且换密钥后历史密文将无法解密。`"
    />

    <!-- 概览 -->
    <section class="summary-row">
      <div class="summary-chip">
        <strong>{{ list?.total ?? '—' }}</strong>
        <span>配置总数</span>
      </div>
      <div class="summary-chip">
        <strong>{{ list?.enabled ?? '—' }}</strong>
        <span>已启用</span>
      </div>
      <div class="summary-chip">
        <strong>{{ (list?.llm?.role_configured || []).length }}</strong>
        <span>已装配角色</span>
      </div>
      <div class="summary-chip">
        <strong>{{ list?.storage?.backend || '—' }}</strong>
        <span>配置存储后端</span>
      </div>
      <div class="summary-chip">
        <strong>{{ list?.storage?.applied_ddl ?? '—' }}</strong>
        <span>已执行 DDL 语句</span>
      </div>
    </section>

    <p class="role-note">
      <strong>角色说明</strong>：模型按角色被调用。当前链路里
      <code>generate</code> 已接入问答生成；其余角色的引擎会照常装配并按 front 配置生效，
      但调用点仍在后续阶段接入 —— 也就是说"配了不报错"不等于"这条链路已经在用它"。
    </p>

    <!-- 角色分组 -->
    <el-card
      v-for="group in roleGroups"
      :key="group.key"
      class="section-card role-card"
      shadow="never"
    >
      <template #header>
        <div class="role-head">
          <div>
            <strong>{{ group.label }}</strong>
            <code class="role-key">{{ group.key }}</code>
            <span class="role-desc">{{ group.description }}</span>
          </div>
          <div class="role-head-right">
            <el-tag size="small" :type="group.assembled > 0 ? 'success' : 'info'" effect="plain">
              已装配 {{ group.assembled }} 个引擎
            </el-tag>
            <el-tag size="small" type="info" effect="plain">默认 {{ group.default_tier }} · {{ group.default_timeout_ms }}ms</el-tag>
          </div>
        </div>
      </template>

      <div v-if="group.items.length" class="model-grid">
        <article v-for="item in group.items" :key="item.id" class="model-card" :class="{ off: !item.enabled }">
          <div class="model-card-head">
            <div class="model-title">
              <strong>{{ item.name }}</strong>
              <small>{{ item.provider }} · {{ item.model || '（未填模型名）' }}</small>
            </div>
            <el-tag :type="item.enabled ? 'success' : 'info'" size="small">{{ item.enabled ? '已启用' : '未启用' }}</el-tag>
          </div>

          <dl class="model-meta">
            <div><dt>层级</dt><dd>{{ item.tier }}</dd></div>
            <div><dt>路由权重</dt><dd>{{ item.routing_weight }}</dd></div>
            <div><dt>密钥</dt><dd class="mono">{{ item.api_key_hint || '未配置' }}</dd></div>
            <div><dt>生成参数</dt><dd>{{ paramBrief(item) }}</dd></div>
          </dl>

          <p class="model-endpoint mono">{{ item.base_url || '（未填 base_url，启用会被拒绝）' }}</p>

          <p v-if="probes[item.id]" class="probe-line" :class="probeState(item.id)">
            {{ probeText(item.id) }}
          </p>
          <p v-else-if="item.last_probe_at" class="probe-line" :class="item.last_probe_ok ? 'ok' : 'bad'">
            上次探测 {{ item.last_probe_ok ? '通过' : '未通过' }}{{ item.last_probe_latency_ms ? ` · ${item.last_probe_latency_ms}ms` : '' }}
            <span v-if="item.last_probe_error"> · {{ item.last_probe_error }}</span>
          </p>

          <div class="model-actions">
            <label class="model-enable-toggle">
              <el-switch
                :model-value="item.enabled"
                size="small"
                :loading="busyId === item.id"
                @change="(value: boolean) => toggleEnabled(item, value)"
              />
              <span>{{ item.enabled ? '已启用' : '已停用' }}</span>
            </label>
            <div class="model-action-buttons">
              <el-button size="small" text type="primary" :icon="Edit" @click="openEdit(item)">编辑</el-button>
              <el-button size="small" text :icon="Connection" :loading="probingId === item.id" @click="doTest(item)">测试连通</el-button>
              <el-button size="small" text type="danger" :icon="Delete" @click="confirmDelete(item)">删除</el-button>
            </div>
          </div>
        </article>
      </div>

      <el-empty v-else :image-size="60" :description="`「${group.label}」尚未配置模型`" />
    </el-card>

    <!-- Token 用量看板（WB-10 后半句，**真实数据**，来源 nlp-service llm_token_usage 聚合表） -->
    <el-card class="section-card usage-card" shadow="never">
      <template #header>
        <div class="role-head">
          <div>
            <strong>Token 用量看板</strong>
            <span class="role-desc">调用次数 / Token 消耗 / 失败率 · 按功能角色与模型拆分</span>
          </div>
          <div class="usage-head-actions">
            <el-radio-group v-model="usageDays" size="small" @change="refreshUsage">
              <el-radio-button :value="7">7 天</el-radio-button>
              <el-radio-button :value="14">14 天</el-radio-button>
              <el-radio-button :value="30">30 天</el-radio-button>
            </el-radio-group>
            <el-tag v-if="usageLive" size="small" type="success" effect="plain">
              实测数据 · {{ usage?.window?.start }} ~ {{ usage?.window?.end }}
            </el-tag>
            <el-tag v-else size="small" type="info" effect="plain">不可读</el-tag>
          </div>
        </div>
      </template>

      <el-alert
        v-if="usageUnavailable"
        :closable="false"
        class="form-alert"
        type="warning"
        show-icon
        title="用量暂不可读，以下不显示任何数字"
        :description="`${usage?.reason || '原因未知'}。此处刻意不显示 0 —— 「读不到」与「这段时间没有人用」是两件不同的事。`"
      />

      <template v-else-if="usageLive">
        <el-alert
          v-if="(usage?.estimated_share || 0) > 0"
          :closable="false"
          class="form-alert"
          type="warning"
          show-icon
          title="部分 Token 为字符估算口径"
          :description="`${Math.round((usage?.estimated_share || 0) * 100)}% 的调用没有拿到供应商自报 usage（该供应商/本机推理服务未返回），已按字符估算（DEBT-016）。这部分数字只能当趋势看，不能当账单看。`"
        />
        <el-alert
          v-if="usage?.storage?.degraded"
          :closable="false"
          class="form-alert"
          type="warning"
          show-icon
          title="计量存储降级"
          :description="`${usage?.storage?.note || usage?.storage?.reason || 'PG 不可用期间的用量未计入本表。'}`"
        />

        <div class="usage-stats">
          <div class="usage-stat"><span>尝试次数</span><strong>{{ usage?.totals?.attempts ?? 0 }}</strong></div>
          <div class="usage-stat"><span>成功</span><strong>{{ usage?.totals?.calls ?? 0 }}</strong></div>
          <div class="usage-stat" :class="{ bad: (usage?.totals?.failures || 0) > 0 }">
            <span>失败</span><strong>{{ usage?.totals?.failures ?? 0 }}</strong>
          </div>
          <div class="usage-stat">
            <span>成功率</span>
            <strong>{{ usage?.totals?.success_rate === null || usage?.totals?.success_rate === undefined ? '—' : `${Math.round((usage.totals.success_rate || 0) * 100)}%` }}</strong>
          </div>
          <div class="usage-stat"><span>Prompt tokens</span><strong>{{ formatTokens(usage?.totals?.prompt_tokens || 0) }}</strong></div>
          <div class="usage-stat"><span>Completion tokens</span><strong>{{ formatTokens(usage?.totals?.completion_tokens || 0) }}</strong></div>
        </div>

        <div class="usage-charts">
          <div>
            <p class="runtime-caption">按天调用量（失败以红色段表示）</p>
            <div v-if="(usage?.daily || []).length" class="token-trend">
              <div v-for="item in usage?.daily || []" :key="item.date" class="token-column">
                <span>{{ item.calls }}</span>
                <div class="token-bar">
                  <i :style="{ height: `${dailyHeight(item.calls)}%` }" />
                  <b v-if="item.failures" :style="{ height: `${dailyHeight(item.failures)}%` }" />
                </div>
                <strong>{{ item.date?.slice(5) }}</strong>
                <small>{{ formatTokens(item.prompt_tokens + item.completion_tokens) }}</small>
              </div>
            </div>
            <el-empty v-else :image-size="50" description="窗口内暂无调用记录" />
          </div>
          <div>
            <p class="runtime-caption">按功能角色</p>
            <el-table :data="usageByRoleRows" size="small" empty-text="窗口内暂无调用记录">
              <el-table-column prop="label" label="角色" min-width="110" />
              <el-table-column prop="calls" label="成功" width="70" />
              <el-table-column prop="failures" label="失败" width="70" />
              <el-table-column label="Tokens" width="100">
                <template #default="{ row }">{{ formatTokens(row.tokens) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <p class="runtime-caption">按模型</p>
        <el-table :data="usageByModelRows" size="small" empty-text="窗口内暂无调用记录">
          <el-table-column prop="model" label="模型" min-width="180" />
          <el-table-column prop="provider" label="供应商" width="110" />
          <el-table-column prop="calls" label="成功" width="80" />
          <el-table-column prop="failures" label="失败" width="80" />
          <el-table-column label="Tokens" width="110">
            <template #default="{ row }">{{ formatTokens(row.tokens) }}</template>
          </el-table-column>
          <el-table-column label="平均延迟" width="110">
            <template #default="{ row }">{{ row.calls ? `${Math.round(row.latency_ms_sum / row.calls)}ms` : '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 运行态观测：配置 / 探测 / 用量 / Prometheus 指标均为真实来源。
         成本、质量、配额、队列缺数据源时显式标记“未接入”。 -->
    <el-card v-loading="runtimeLoading" class="section-card runtime-card" shadow="never">
      <template #header>
        <div class="role-head">
          <div>
            <strong>模型接入运行态</strong>
            <span class="role-desc">真实配置 · 连通性探测 · 调用用量 · Prometheus LLM 指标</span>
          </div>
          <el-tag v-if="runtimeUnavailable" size="small" type="danger" effect="dark">运行态不可达</el-tag>
          <el-tag v-else-if="runtime?.available" size="small" type="success" effect="dark">真实数据</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">加载中</el-tag>
        </div>
      </template>

      <el-alert
        v-if="runtimeUnavailable"
        :closable="false"
        type="error"
        show-icon
        title="模型运行态不可读"
        :description="runtime?.reason || 'BFF /models/runtime 不可达；这里不显示 0 或 Mock 运行指标。'"
      />

      <template v-else-if="runtime?.available">
        <div class="runtime-source-line">
          <el-tag size="small" :type="runtime.sources?.config?.degraded ? 'warning' : 'success'" effect="plain">
            配置 {{ runtime.sources?.config?.backend || 'unknown' }}
          </el-tag>
          <el-tag size="small" :type="runtime.sources?.usage?.available ? (runtime.sources.usage.degraded ? 'warning' : 'success') : 'danger'" effect="plain">
            用量 {{ runtime.sources?.usage?.available ? (runtime.sources.usage.degraded ? '降级' : '真实') : '不可读' }}
          </el-tag>
          <el-tag size="small" :type="runtime.sources?.prometheus?.complete ? 'success' : runtime.sources?.prometheus?.available ? 'warning' : 'danger'" effect="plain">
            Prometheus {{ runtime.sources?.prometheus?.complete ? '完整' : runtime.sources?.prometheus?.available ? '部分' : '不可达' }}
          </el-tag>
          <span class="runtime-window">窗口 {{ runtime.window?.days || usageDays }} 天</span>
        </div>

        <el-empty v-if="!runtime.items.length" :image-size="60" description="尚无模型配置，新增配置后这里展示真实运行状态" />
        <div v-else class="model-grid">
          <article v-for="item in runtime.items" :key="item.config_id" class="runtime-model" :class="item.runtime_state">
            <div class="model-card-head">
              <div class="model-title">
                <strong>{{ item.name }}</strong>
                <small>{{ item.provider }} · {{ item.model }}</small>
              </div>
              <el-tag size="small" :type="runtimeStateMeta(item.runtime_state).type">
                {{ runtimeStateMeta(item.runtime_state).label }}
              </el-tag>
            </div>
            <div class="runtime-role-line">
              <el-tag size="small" effect="plain">{{ item.role_label || item.config_key }}</el-tag>
              <span>权重 {{ item.routing_weight }}</span>
              <span v-if="item.prometheus?.qps !== undefined">QPS {{ Number(item.prometheus.qps).toFixed(2) }}</span>
            </div>
            <div class="runtime-stats">
              <span>调用<strong>{{ item.usage ? item.usage.attempts : '—' }}</strong></span>
              <span>失败<strong>{{ item.usage ? item.usage.failures : '—' }}</strong></span>
              <span>成功率<strong>{{ runtimePercent(item.usage?.success_rate) }}</strong></span>
              <span>流量<strong>{{ runtimePercent(item.usage?.share) }}</strong></span>
            </div>
            <div class="runtime-evidence">
              <span>{{ runtimeProbeText(item) }}</span>
              <span>推理平均 {{ runtimeUsageText(item.usage?.avg_latency_ms, 'ms') }}</span>
              <span>P95 {{ runtimeUsageText(item.prometheus?.p95_latency_ms, 'ms') }}</span>
              <span>Tokens {{ item.usage ? formatTokens(item.usage.tokens) : '—' }}</span>
            </div>
            <div class="tag-line">
              <el-tag v-if="item.tier" size="small" type="info">{{ item.tier }}</el-tag>
              <el-tag size="small" :type="item.enabled ? 'success' : 'info'" effect="plain">{{ item.enabled ? '已启用' : '已停用' }}</el-tag>
              <el-tag v-if="item.probe.at" size="small" type="info" effect="plain">探测 {{ item.probe.at }}</el-tag>
            </div>
          </article>
        </div>

        <div class="runtime-tables">
          <div class="runtime-routing-panel">
            <p class="runtime-caption">真实路由配置：角色优先 · 权重排序 · 故障后层级降级</p>
            <el-table :data="runtime.routing" size="small" empty-text="暂无已启用角色路由">
              <el-table-column prop="role_label" label="功能角色" min-width="110" />
              <el-table-column label="主模型" min-width="180">
                <template #default="{ row }">{{ row.candidates[0]?.name || '—' }}</template>
              </el-table-column>
              <el-table-column label="候选模型" width="100">
                <template #default="{ row }">{{ row.candidates.length }}</template>
              </el-table-column>
              <el-table-column label="实际调用" width="100">
                <template #default="{ row }">{{ row.usage?.attempts ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="失败" width="80">
                <template #default="{ row }">{{ row.usage?.failures ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="平均延迟" width="110">
                <template #default="{ row }">{{ runtimeUsageText(row.usage?.avg_latency_ms, 'ms') }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="unsupported-grid">
          <el-tag v-for="(value, key) in runtime.unsupported_fields || {}" :key="key" size="small" type="info" effect="plain">
            {{ runtimeUnsupportedText(String(key)) }}
          </el-tag>
        </div>
      </template>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="formVisible" :title="form.id ? `编辑配置 · ${form.name}` : '新增模型配置'" width="640px">
      <el-alert
        v-if="formError"
        :closable="false"
        class="form-alert"
        type="error"
        show-icon
        :title="formError"
      />
      <el-form label-width="112px" label-position="right">
        <el-form-item label="功能角色">
          <el-select v-model="form.config_key" :disabled="!!form.id" style="width: 100%">
            <el-option v-for="role in roles" :key="role.key" :label="`${role.label}（${role.key}）`" :value="role.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.name" placeholder="同一角色内唯一，例如：主力生成模型" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange">
            <el-option v-for="item in providers" :key="item.key" :label="item.key" :value="item.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="base_url">
          <el-input v-model="form.base_url" placeholder="OpenAI 兼容端点根地址，如 https://api.deepseek.com/v1" />
        </el-form-item>
        <el-form-item label="模型名">
          <el-input v-model="form.model" placeholder="如 deepseek-chat" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.api_key"
            type="password"
            show-password
            :placeholder="form.id
              ? `留空 = 保持原密钥（${form.api_key_hint || '未配置'}）；输入新值 = 轮换；输入单个空格后再清空 = 清除`
              : '粘贴供应商密钥，落库前会 AES-256-GCM 加密'"
          />
          <p v-if="form.id" class="field-hint">
            当前密钥：<code>{{ form.api_key_hint || '未配置' }}</code>。这里**不会**回填明文 —— 不回填才说明库里存的是密文。
          </p>
        </el-form-item>
        <el-divider content-position="left">代理与网络</el-divider>
        <div class="form-row">
          <el-form-item label="代理 URL">
            <el-input v-model="form.proxy_url" placeholder="留空 = 使用系统/环境代理" />
          </el-form-item>
          <el-form-item label="NO_PROXY">
            <el-input v-model="form.no_proxy" placeholder="如 openrouter.ai,localhost" />
          </el-form-item>
        </div>
        <el-form-item label="信任环境代理">
          <el-switch v-model="form.trust_env" />
          <span class="switch-hint">关闭后该模型完全直连，不读取系统 HTTP(S)_PROXY</span>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="层级">
            <el-select v-model="form.tier" style="width: 100%">
              <el-option v-for="tier in tiers" :key="tier" :label="tier" :value="tier" />
            </el-select>
          </el-form-item>
          <el-form-item label="路由权重">
            <el-input-number v-model="form.routing_weight" :min="0" :max="1000" :step="10" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="max_tokens">
            <el-input-number v-model="form.max_tokens" :min="1" :max="32000" :step="128" style="width: 100%" />
          </el-form-item>
          <el-form-item label="temperature">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" style="width: 100%" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="超时(ms)">
            <el-input-number v-model="form.timeout_ms" :min="500" :max="60000" :step="500" style="width: 100%" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
            <span class="switch-hint">未启用不会进入角色引擎</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">{{ form.id ? '保存修改' : '创建配置' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Delete, Edit, Plus, Refresh, RefreshRight } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { useAppStore } from '../stores/app'
import type {
  ModelConfig, ModelConfigList, ModelConfigUpsert, ModelProvider, ModelProbeResult,
  ModelRole, ModelRuntimeItem, ModelRuntimeOverview, ModelRuntimeState, ModelUsage,
} from '../types'

const store = useAppStore()

const loading = ref(false)
const reloading = ref(false)
const saving = ref(false)
const busyId = ref<number | null>(null)
const probingId = ref<number | null>(null)
const list = ref<ModelConfigList | null>(null)
const runtime = ref<ModelRuntimeOverview | null>(null)
const runtimeLoading = ref(false)
const probes = reactive<Record<number, ModelProbeResult>>({})
const formVisible = ref(false)
const formError = ref('')

const live = computed(() => list.value?.available === true)
const unavailable = computed(() => list.value !== null && list.value.available === false)
const runtimeUnavailable = computed(() => runtime.value !== null && runtime.value.available === false)

/** 角色字典优先用后端返回值（避免前端硬编码第二份字典）；后端不可用时回落本地最小集 */
const FALLBACK_ROLES: ModelRole[] = [
  { key: 'intent', label: '意图识别', default_tier: 'L1', default_timeout_ms: 3000, description: '问题意图分类' },
  { key: 'embed', label: '向量嵌入', default_tier: 'L1', default_timeout_ms: 4000, description: '知识向量化' },
  { key: 'rerank', label: '结果重排', default_tier: 'L1', default_timeout_ms: 4000, description: '候选精排' },
  { key: 'generate', label: '内容生成', default_tier: 'L2', default_timeout_ms: 20000, description: '最终答案生成' },
  { key: 'plan', label: '任务规划', default_tier: 'L2', default_timeout_ms: 12000, description: '多步规划' },
  { key: 'code', label: '代码生成', default_tier: 'L3', default_timeout_ms: 20000, description: '代码类任务' },
]
const roles = computed<ModelRole[]>(() => list.value?.roles?.length ? list.value.roles : FALLBACK_ROLES)
const providers = computed<ModelProvider[]>(() => list.value?.providers || [])
const tiers = computed(() => (list.value?.storage ? ['L1', 'L2', 'L3'] : ['L1', 'L2', 'L3']))

const roleGroups = computed(() => {
  const assembled = list.value?.llm?.engines || {}
  return roles.value.map(role => {
    const items = (list.value?.by_role?.[role.key] || []).slice()
    items.sort((a, b) => (b.routing_weight || 0) - (a.routing_weight || 0) || a.id - b.id)
    return {
      key: role.key,
      label: role.label,
      description: role.description || '',
      default_tier: role.default_tier || 'L2',
      default_timeout_ms: role.default_timeout_ms || 8000,
      assembled: Array.isArray(assembled[role.key]) ? assembled[role.key].length : 0,
      items,
    }
  })
})

const form = reactive<ModelConfigUpsert & {
  id?: number
  api_key_hint?: string
  proxy_url?: string
  no_proxy?: string
  trust_env?: boolean
}>({
  config_key: 'generate',
  name: '',
  provider: 'custom',
  base_url: '',
  model: '',
  api_key: '',
  tier: 'L2',
  max_tokens: 512,
  temperature: 0.2,
  timeout_ms: 8000,
  routing_weight: 100,
  enabled: true,
  proxy_url: '',
  no_proxy: '',
  trust_env: true,
})

function paramBrief(item: ModelConfig): string {
  const parts: string[] = []
  if (item.max_tokens) parts.push(`max ${item.max_tokens}`)
  if (item.temperature !== null && item.temperature !== undefined) parts.push(`T ${item.temperature}`)
  if (item.timeout_ms) parts.push(`${item.timeout_ms}ms`)
  return parts.length ? parts.join(' · ') : '默认'
}

function probeState(id: number): string {
  const probe = probes[id]
  if (!probe) return ''
  if (!probe.supported) return 'warn'
  return probe.ok ? 'ok' : 'bad'
}

function probeText(id: number): string {
  const probe = probes[id]
  if (!probe) return ''
  if (!probe.supported) return `未执行探测：${probe.detail || probe.reason || '该供应商不提供 GET /models'}`
  return probe.ok
    ? `探测通过 · ${probe.latency_ms ?? '—'}ms`
    : `探测未通过 · ${probe.detail || probe.reason || '连接失败'}`
}

const runtimeStateMeta = (state: ModelRuntimeState) => ({
  active: { label: '已启用 · 探测通过', type: 'success' },
  degraded: { label: '已启用 · 失败率偏高', type: 'warning' },
  offline: { label: '已启用 · 探测失败', type: 'danger' },
  unverified: { label: '已启用 · 未探测', type: 'info' },
  disabled: { label: '已停用', type: 'info' },
}[state] || { label: state, type: 'info' })

const runtimeProbeText = (item: ModelRuntimeItem) => {
  if (item.probe.state === 'passed') return `探测通过 · ${item.probe.latency_ms ?? '—'}ms`
  if (item.probe.state === 'failed') return `探测失败 · ${item.probe.error || '连接异常'}`
  return '尚未执行连通性探测'
}

const runtimeUsageText = (value: number | null | undefined, suffix = '') => {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return `${Number(value)}${suffix}`
}

const runtimePercent = (value: number | null | undefined) => {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return `${(Number(value) * 100).toFixed(1)}%`
}

const runtimeUnsupportedText = (key: string) => ({
  cost: '成本：未接入价格元数据',
  quality: '质量：未接入评测结果',
  quota: '配额：未接入供应商接口',
  queue: '队列：未接入运行态 Gauge',
}[key] || `${key}：未接入`)

async function refresh() {
  loading.value = true
  try {
    list.value = await dataProvider.getModels()
    for (const key of Object.keys(probes)) delete probes[Number(key)]
  } finally {
    loading.value = false
  }
}

function openCreate() {
  formError.value = ''
  const first = roles.value.find(role => !(list.value?.by_role?.[role.key] || []).length) || roles.value[0]
  Object.assign(form, {
    id: undefined,
    api_key_hint: '',
    config_key: first?.key || 'generate',
    name: '',
    provider: 'custom',
    base_url: '',
    model: '',
    api_key: '',
    tier: first?.default_tier || 'L2',
    max_tokens: 512,
    temperature: 0.2,
    timeout_ms: first?.default_timeout_ms || 8000,
    routing_weight: 100,
    enabled: true,
    proxy_url: '',
    no_proxy: '',
    trust_env: true,
  })
  formVisible.value = true
}

function openEdit(item: ModelConfig) {
  formError.value = ''
  Object.assign(form, {
    id: item.id,
    api_key_hint: item.api_key_hint,
    config_key: item.config_key,
    name: item.name,
    provider: item.provider,
    base_url: item.base_url,
    model: item.model,
    // 绝不回填密钥：hint 是脱敏值，回填会被当成真实密钥再存一次
    api_key: '',
    tier: item.tier,
    max_tokens: item.max_tokens,
    temperature: item.temperature,
    timeout_ms: item.timeout_ms,
    routing_weight: item.routing_weight,
    enabled: item.enabled,
    proxy_url: String(item.extra?.proxy_url || ''),
    no_proxy: String(item.extra?.no_proxy || ''),
    trust_env: item.extra?.trust_env === undefined ? true : Boolean(item.extra.trust_env),
  })
  formVisible.value = true
}

function onProviderChange(key: string) {
  const preset = providers.value.find(item => item.key === key)
  if (preset?.default_base_url && !form.base_url) form.base_url = preset.default_base_url
}

/** 只提交**显式出现**的字段；未填的可选值不塞默认值（否则会把既有配置清空） */
function buildPayload(): ModelConfigUpsert {
  const payload: ModelConfigUpsert = {
    config_key: form.config_key,
    name: String(form.name || '').trim(),
    provider: form.provider,
    base_url: String(form.base_url || '').trim(),
    model: String(form.model || '').trim(),
    tier: form.tier,
    enabled: !!form.enabled,
    extra: {
      ...(String(form.proxy_url || '').trim() ? { proxy_url: String(form.proxy_url).trim() } : {}),
      ...(String(form.no_proxy || '').trim() ? { no_proxy: String(form.no_proxy).trim() } : {}),
      trust_env: form.trust_env !== false,
    },
  }
  if (form.max_tokens !== null && form.max_tokens !== undefined) payload.max_tokens = form.max_tokens
  if (form.temperature !== null && form.temperature !== undefined) payload.temperature = form.temperature
  if (form.timeout_ms !== null && form.timeout_ms !== undefined) payload.timeout_ms = form.timeout_ms
  if (form.routing_weight !== null && form.routing_weight !== undefined) payload.routing_weight = form.routing_weight
  const secret = String(form.api_key ?? '')
  if (!form.id) {
    if (secret) payload.api_key = secret
  } else if (secret) {
    payload.api_key = secret
  }
  // 编辑态留空 = 不上送 api_key 字段 → 上游保留原凭据
  return payload
}

async function submitForm() {
  formError.value = ''
  const payload = buildPayload()
  if (!payload.name) { formError.value = '显示名不能为空'; return }
  if (payload.enabled && (!payload.base_url || !payload.model)) {
    formError.value = '启用状态下必须同时填写 base_url 与模型名（否则会创建出一条永远不可用的配置）'
    return
  }
  saving.value = true
  try {
    const outcome = await dataProvider.saveModel(payload, form.id)
    if (!outcome.ok) {
      formError.value = outcome.message || '保存失败'
      return
    }
    const reload = outcome.data?.reload as { ok?: boolean; roles?: string[]; error?: string } | undefined
    if (reload && reload.ok === false) {
      ElMessage.warning(`配置已保存，但引擎重载失败：${reload.error || '原因未知'}。配置在库中，重启服务或点「重载引擎」后生效`)
    } else {
      ElMessage.success(form.id ? '配置已更新并生效' : '配置已创建并生效')
    }
    formVisible.value = false
    await refreshAll()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(item: ModelConfig, value: boolean) {
  if (value && (!item.base_url || !item.model)) {
    ElMessage.warning('缺少 base_url 或模型名，无法启用')
    return
  }
  busyId.value = item.id
  try {
    const outcome = await dataProvider.saveModel({ enabled: value }, item.id)
    if (!outcome.ok) {
      ElMessage.error(outcome.message || '更新失败')
      return
    }
    ElMessage.success(value ? '已启用' : '已停用')
    await refreshAll()
  } finally {
    busyId.value = null
  }
}

async function doTest(item: ModelConfig) {
  probingId.value = item.id
  try {
    const result = await dataProvider.testModel(item.id)
    probes[item.id] = result
  } finally {
    probingId.value = null
  }
}

async function confirmDelete(item: ModelConfig) {
  try {
    await ElMessageBox.confirm(
      `确认删除「${item.name}」（角色 ${item.config_key}）？删除后该角色若没有其他可用配置，将回落到层级降级链。`,
      '删除模型配置',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const outcome = await dataProvider.deleteModel(item.id)
  if (!outcome.ok) {
    ElMessage.error(outcome.message || '删除失败')
    return
  }
  ElMessage.success('已删除')
  await refreshAll()
}

async function doReload() {
  reloading.value = true
  try {
    const outcome = await dataProvider.reloadModels()
    if (!outcome.ok) {
      ElMessage.error(outcome.message || '重载失败')
      return
    }
    const reload = outcome.data?.reload as { ok?: boolean; count?: number; roles?: string[]; error?: string } | undefined
    if (reload?.ok === false) {
      ElMessage.warning(`重载未成功：${reload.error || '原因未知'}`)
    } else {
      ElMessage.success(`引擎已重载 · ${reload?.count ?? 0} 个引擎 · 角色 ${(reload?.roles || []).join(' / ') || '无'}`)
    }
    await refreshAll()
  } finally {
    reloading.value = false
  }
}

// ─── Token 用量看板（WB-10 后半句）：**真实计量数据**，来源 nlp-service `llm_token_usage` ───
//
// 与「配置」分开取数：配置读的是 `llm_model_config`，用量读的是计量聚合表。
// 数据库不可用时两者都会降级，但降级语义必须显式 —— 见 usageUnavailable 的说明。
const usageDays = ref(7)
const usage = ref<ModelUsage | null>(null)

/** 「读到了真实数字」与「读不到」是两件事：读不到时不显示 0，否则会把
 *  「这段时间没人用」和「看不见用量」渲染成同一个页面。 */
const usageLive = computed(() => usage.value?.available === true)
const usageUnavailable = computed(() => usage.value !== null && usage.value.available === false)

/** 柱高基准取 `calls + failures` 的窗口最大值 —— 成功段与失败段堆叠后不会溢出容器 */
const usageDailyMax = computed(() => {
  const rows = usage.value?.daily || []
  return rows.reduce((max, item) => Math.max(max, (item.calls || 0) + (item.failures || 0)), 0) || 1
})

function dailyHeight(value: number): number {
  const raw = Number(value) || 0
  return Math.max(0, Math.min(100, Math.round((raw / usageDailyMax.value) * 100)))
}

/** token 数按量级缩写（10.4k / 8.42M）；不做四舍五入到 0 —— 最小显示 1 */
function formatTokens(value: number): string {
  const raw = Math.max(0, Number(value) || 0)
  if (raw >= 1_000_000) return `${(raw / 1_000_000).toFixed(2)}M`
  if (raw >= 1_000) return `${(raw / 1_000).toFixed(1)}k`
  return String(raw)
}

/** 角色中文名优先用后端返回的角色字典，与上方配置区分组口径保持一致 */
const usageByRoleRows = computed(() => {
  const labels: Record<string, string> = {}
  for (const role of roles.value) labels[role.key] = role.label
  return Object.entries(usage.value?.by_role || {})
    .map(([role, counters]) => ({
      role,
      label: labels[role] || role,
      calls: counters.calls || 0,
      failures: counters.failures || 0,
      tokens: (counters.prompt_tokens || 0) + (counters.completion_tokens || 0),
    }))
    .sort((a, b) => b.tokens - a.tokens)
})

/** 供应商不在用量聚合的维度里（聚合键是 model），故由配置清单反查；
 *  查不到就显式写「—」，不猜。 */
const usageByModelRows = computed(() => {
  const providerByModel = new Map<string, string>()
  for (const item of list.value?.items || []) {
    if (item.model && !providerByModel.has(item.model)) providerByModel.set(item.model, item.provider)
  }
  return Object.entries(usage.value?.by_model || {})
    .map(([model, counters]) => ({
      model,
      provider: providerByModel.get(model) || '—',
      calls: counters.calls || 0,
      failures: counters.failures || 0,
      tokens: (counters.prompt_tokens || 0) + (counters.completion_tokens || 0),
      latency_ms_sum: counters.latency_ms_sum || 0,
    }))
    .sort((a, b) => b.tokens - a.tokens)
})

async function refreshUsage() {
  // dataProvider 内部已做降级与上报（不可达 → available:false + reason），此处不吞语义
  usage.value = await dataProvider.getModelUsage(Number(usageDays.value) || 7)
}

async function refreshRuntime() {
  runtimeLoading.value = true
  try {
    runtime.value = await dataProvider.getModelRuntime(Number(usageDays.value) || 7)
  } finally {
    runtimeLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([refresh(), refreshUsage(), refreshRuntime()])
}

onMounted(() => {
  void refreshAll()
})
</script>

<style scoped>
.models-view { display: flex; flex-direction: column; gap: 16px; }
.view-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.view-head h2 { margin: 2px 0 4px; font-size: 20px; letter-spacing: .04em; }
.view-kicker { color: var(--wp-primary); font-size: 10px; letter-spacing: .22em; }
.view-sub { margin: 0; color: var(--wp-sub); font-size: 12px; }
.view-sub code { color: var(--wp-gold-soft); font-family: ui-monospace, monospace; }
.tenant-tag { margin-left: 8px; color: var(--wp-gold-soft); }
.head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.model-toolbar-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border: 1px solid rgba(94, 234, 212, .26);
  border-radius: 12px;
  background: rgba(8, 15, 28, .42);
  box-shadow: 0 8px 24px rgba(0, 0, 0, .16), inset 0 0 18px rgba(94, 234, 212, .035);
}
.model-toolbar-button.el-button {
  height: 30px;
  margin-left: 0;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: rgba(148, 163, 184, .055);
  color: var(--wp-text);
  font-weight: 600;
  letter-spacing: .02em;
  transition: background .18s ease, border-color .18s ease, color .18s ease, transform .18s ease;
}
.model-toolbar-button.el-button:hover,
.model-toolbar-button.el-button:focus-visible {
  border-color: rgba(94, 234, 212, .55);
  background: rgba(20, 184, 166, .14);
  color: var(--wp-primary);
  transform: translateY(-1px);
}
.model-toolbar-button.el-button :deep(.el-icon) { font-size: 15px; }
.state-alert :deep(.el-alert__description) { font-family: ui-monospace, monospace; font-size: 11px; line-height: 1.8; }
.summary-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
.summary-chip { padding: 14px 16px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .05); display: flex; flex-direction: column; gap: 6px; }
.summary-chip strong { font-size: 20px; color: var(--wp-text); }
.summary-chip span { color: var(--wp-sub); font-size: 11px; }
.role-note { margin: 0; padding: 10px 14px; border-left: 2px solid var(--wp-gold-soft); border-radius: 0 8px 8px 0; background: rgba(148, 163, 184, .06); color: var(--wp-sub); font-size: 11px; line-height: 1.9; }
.role-note strong { color: var(--wp-gold-soft); }
.role-note code { color: var(--wp-primary); font-family: ui-monospace, monospace; }
.role-card :deep(.el-card__header) { padding: 12px 16px; }
.role-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.role-head strong { font-size: 13px; }
.role-key { margin-left: 8px; padding: 1px 6px; border: 1px solid var(--wp-border); border-radius: 6px; font-family: ui-monospace, monospace; font-size: 10px; color: var(--wp-primary); }
.role-desc { margin-left: 10px; color: var(--wp-sub); font-size: 11px; }
.role-head-right { display: flex; gap: 8px; align-items: center; }
.model-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; }
.model-card { padding: 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .04); display: flex; flex-direction: column; gap: 10px; }
.model-card.off { opacity: .62; border-style: dashed; }
.model-card-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
.model-title { display: flex; flex-direction: column; gap: 3px; }
.model-title strong { font-size: 13px; color: var(--wp-text); }
.model-title small { color: var(--wp-sub); font-size: 11px; }
.model-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin: 0; }
.model-meta > div { display: flex; justify-content: space-between; gap: 8px; }
.model-meta dt { color: var(--wp-sub); font-size: 11px; }
.model-meta dd { margin: 0; font-size: 11px; color: var(--wp-text); }
.mono { font-family: ui-monospace, monospace; word-break: break-all; }
.model-endpoint { margin: 0; padding: 6px 8px; border-radius: 8px; background: rgba(148, 163, 184, .08); color: var(--wp-sub); font-size: 10px; }
.probe-line { margin: 0; font-size: 11px; }
.probe-line.ok { color: var(--wp-success); }
.probe-line.bad { color: var(--wp-danger); }
.probe-line.warn { color: var(--wp-gold-soft); }
.model-actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: auto; padding-top: 9px; border-top: 1px solid rgba(148,163,184,.14); }
.model-enable-toggle { display: inline-flex; align-items: center; gap: 6px; color: var(--wp-sub); font-size: 10px; cursor: pointer; }
.model-action-buttons { display: inline-flex; align-items: center; gap: 4px; }
.model-action-buttons .el-button { height: 28px; margin-left: 0; padding: 0 7px; border-radius: 8px; }
.model-action-buttons .el-button :deep(.el-icon) { font-size: 13px; }
.form-alert { margin-bottom: 12px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.field-hint { margin: 4px 0 0; color: var(--wp-sub); font-size: 11px; line-height: 1.7; }
.field-hint code { color: var(--wp-gold-soft); font-family: ui-monospace, monospace; }
.switch-hint { margin-left: 10px; color: var(--wp-sub); font-size: 11px; }
/* 运行态观测区：仅展示配置 / 探测 / 用量 / Prometheus 可证实数据 */
.runtime-card { margin-top: 2px; }
.runtime-model { padding: 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .04); display: flex; flex-direction: column; gap: 10px; }
.runtime-model.active { border-left: 2px solid var(--wp-success); }
.runtime-model.degraded { border-left: 2px solid var(--wp-gold-soft); }
.runtime-model.offline { border-left: 2px solid var(--wp-danger); }
.runtime-model.unverified { border-left: 2px solid var(--wp-primary); }
.runtime-model.disabled { border-left: 2px solid #64748b; opacity: .72; }
.runtime-source-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.runtime-window { color: var(--wp-sub); font-size: 11px; }
.runtime-role-line, .runtime-evidence { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: var(--wp-sub); font-size: 10px; }
.runtime-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.runtime-stats span { display: flex; flex-direction: column; gap: 2px; color: var(--wp-sub); font-size: 10px; }
.runtime-stats strong { color: var(--wp-text); font-size: 12px; }
.tag-line { display: flex; flex-wrap: wrap; gap: 6px; }
.runtime-tables { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; margin-top: 16px; }
.runtime-routing-panel { min-width: 0; }
.runtime-caption { margin: 0 0 8px; color: var(--wp-sub); font-size: 11px; }
.unsupported-grid { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
.token-trend { display: flex; align-items: flex-end; gap: 10px; height: 120px; }
.token-column { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; height: 100%; justify-content: flex-end; }
.token-column span { color: var(--wp-sub); font-size: 10px; }
.token-column strong { font-size: 11px; color: var(--wp-text); }
.token-column small { color: var(--wp-sub); font-size: 9px; }
.token-bar { width: 100%; max-width: 26px; flex: 1; display: flex; align-items: flex-end; }
.token-bar i { display: block; width: 100%; border-radius: 4px 4px 0 0; background: linear-gradient(180deg, var(--wp-primary), var(--wp-primary-strong)); }
/* Token 用量看板（真实计量数据）：成功段 + 失败红色段自下而上堆叠 */
.usage-card :deep(.el-card__header) { padding: 12px 16px; }
.usage-head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.usage-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; margin-bottom: 16px; }
.usage-stat { padding: 12px 14px; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(148, 163, 184, .05); display: flex; flex-direction: column; gap: 6px; }
.usage-stat span { color: var(--wp-sub); font-size: 11px; }
.usage-stat strong { font-size: 18px; color: var(--wp-text); }
.usage-stat.bad strong { color: var(--wp-danger); }
.usage-charts { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; margin-bottom: 16px; }
.usage-charts .token-bar { flex-direction: column; justify-content: flex-end; overflow: hidden; }
.usage-charts .token-bar b { display: block; width: 100%; background: var(--wp-danger); opacity: .85; }
@media (max-width: 900px) { .form-row { grid-template-columns: 1fr; } .runtime-tables { grid-template-columns: 1fr; } .usage-charts { grid-template-columns: 1fr; } }
</style>

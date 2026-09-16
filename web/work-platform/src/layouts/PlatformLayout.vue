<template>
  <el-container class="platform-shell">
    <el-aside :width="store.sidebarCollapsed ? '78px' : '248px'" class="platform-aside">
      <div class="brand" :class="{ compact: store.sidebarCollapsed }">
        <div class="brand-orbit"><span>AI</span></div>
        <div v-if="!store.sidebarCollapsed" class="brand-text">
          <strong>AGENT-LIFEFORM</strong>
          <span>生命体总控台 · AURELION</span>
        </div>
      </div>
      <div v-if="!store.sidebarCollapsed" class="system-pulse">
        <span class="pulse-dot" /> 系统在线 · {{ store.dataSource.toUpperCase() }}
      </div>
      <el-scrollbar class="nav-scroll">
        <el-menu :default-active="route.path" router :collapse="store.sidebarCollapsed" class="platform-menu">
          <template v-for="group in groups" :key="group">
            <el-menu-item-group :title="group">
              <el-menu-item v-for="item in modulesByGroup(group)" :key="item.id" :index="item.path">
                <el-icon><component :is="iconMap[item.icon]" /></el-icon>
                <template #title>{{ item.title }}</template>
              </el-menu-item>
            </el-menu-item-group>
          </template>
        </el-menu>
      </el-scrollbar>
      <div v-if="!store.sidebarCollapsed" class="agent-online-panel">
        <div class="agent-online-head"><span>Agent 在线</span><strong>{{ activeAgentCount }}/{{ onlineAgents.length }}</strong></div>
        <button v-for="agent in onlineAgents" :key="agent.agent_id" type="button" class="online-agent-row" @click="router.push('/collab')">
          <i class="online-agent-dot" :class="agent.state" />
          <span class="online-agent-copy"><strong>{{ agent.name }}</strong><small>{{ agent.role }} · {{ agent.task }}</small></span>
          <em>{{ agent.latency_ms }}ms</em>
        </button>
      </div>
      <div v-if="!store.sidebarCollapsed" class="aside-footer">
        <span>NODE 01</span><span>v0.2</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="platform-header">
        <el-button text circle class="header-icon" @click="store.toggleSidebar">
          <el-icon><Fold v-if="!store.sidebarCollapsed" /><Expand v-else /></el-icon>
        </el-button>
        <div class="header-brand">
          <span class="header-kicker">LIFEFORM COMMAND</span>
          <strong>统一工作平台</strong>
        </div>
        <button class="global-search-trigger" type="button" @click="openSearch">
          <el-icon><Search /></el-icon>
          <span>搜索任务 / 知识 / 技能 / 案例</span>
          <kbd>Ctrl K</kbd>
        </button>
        <div class="header-vitals">
          <div v-for="item in headerVitals" :key="item.key" class="header-vital" :class="item.status">
            <span>{{ item.label.split(' / ')[0] }}</span>
            <strong>{{ item.value }}{{ item.unit }}</strong>
          </div>
        </div>
        <div class="header-status" :class="{ offline: !online }">
          <span class="status-dot" />
          <span>{{ online ? 'SYNC' : 'OFFLINE' }}</span>
        </div>
        <el-tooltip :disabled="!degradedCount" :content="degradeTooltip" placement="bottom">
          <el-tag :type="degradedCount ? 'danger' : store.dataSource === 'api' ? 'success' : 'info'" effect="dark" class="source-tag" @click="store.toggleDataSource">
            {{ store.dataSource.toUpperCase() }}{{ degradedCount ? ` · 降级 ${degradedCount}` : '' }}
          </el-tag>
        </el-tooltip>
        <el-tag type="warning" effect="plain" class="tenant-tag">{{ store.tenant }}</el-tag>
        <el-tooltip content="通知中心">
          <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="notification-badge">
            <el-button text circle class="header-icon" @click="notificationVisible = true"><el-icon><Bell /></el-icon></el-button>
          </el-badge>
        </el-tooltip>
        <el-tooltip content="浅色 / 深色"><el-button text circle class="header-icon" @click="store.toggleTheme"><el-icon><Moon v-if="!store.dark" /><Sunny v-else /></el-icon></el-button></el-tooltip>
        <el-dropdown @command="handleUserCommand">
          <el-avatar :size="34" class="user-avatar">AI</el-avatar>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="preferences">个人偏好</el-dropdown-item>
              <el-dropdown-item command="model">模型：{{ store.model }}</el-dropdown-item>
              <el-dropdown-item divided command="mock">数据源：{{ store.dataSource.toUpperCase() }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="platform-main">
        <div class="ambient-line top" />
        <div v-if="degradedCount" class="degrade-banner" role="status">
          <span class="degrade-dot" />
          <strong>数据源降级</strong>
          <span>API 模式下 {{ degradedCount }} 个数据域回落到 Mock：{{ degradedScopes }}</span>
          <el-button text type="primary" size="small" @click="degradeDetailVisible = true">查看明细</el-button>
        </div>
        <router-view />
        <div class="ambient-line bottom" />
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="searchVisible" width="720px" class="command-dialog" title="全局搜索" @opened="focusSearch">
    <el-input ref="searchInputRef" v-model="searchKeyword" size="large" clearable placeholder="输入任务、知识文档、技能、案例或审批 ID" @input="runSearch">
      <template #prefix><el-icon><Search /></el-icon></template>
      <template #suffix><span class="dialog-hint">ESC 关闭</span></template>
    </el-input>
    <div class="search-scope">
      <el-tag v-for="scope in searchScopes" :key="scope" size="small" :effect="activeSearchScope === scope ? 'dark' : 'plain'" @click="activeSearchScope = scope">{{ scope }}</el-tag>
    </div>
    <div v-if="searching" class="search-loading"><el-skeleton :rows="3" animated /></div>
    <div v-else-if="filteredSearchResults.length" class="search-results">
      <button v-for="item in filteredSearchResults" :key="`${item.type}-${item.title}`" class="search-result" type="button" @click="openSearchResult(item)">
        <el-tag size="small" effect="plain">{{ item.type }}</el-tag>
        <div><strong>{{ item.title }}</strong><p>{{ item.text }}</p></div>
        <el-icon><ArrowRight /></el-icon>
      </button>
    </div>
    <el-empty v-else description="没有匹配结果，试试任务 ID 或技能名称" :image-size="80" />
  </el-dialog>

  <el-drawer v-model="notificationVisible" title="通知中心" size="420px">
    <div class="notification-toolbar">
      <span>未读 {{ unreadCount }} 条</span>
      <el-button text type="primary" :disabled="!unreadCount" @click="markAllRead">全部已读</el-button>
    </div>
    <button v-for="item in notificationList" :key="item.id" class="notification-item" :class="{ unread: item.unread }" type="button" @click="openNotification(item)">
      <div class="notification-title"><strong>{{ item.title }}</strong><span>{{ item.time }}</span></div>
      <p>{{ item.text }}</p>
      <el-tag size="small" effect="plain">{{ notificationLabel(item.type) }}</el-tag>
    </button>
    <el-empty v-if="!notificationList.length" description="暂无通知" :image-size="72" />
  </el-drawer>

  <el-dialog v-model="preferenceVisible" title="偏好设置" width="560px">
    <el-form label-width="120px" class="preference-form">
      <el-form-item label="界面主题">
        <el-radio-group v-model="preferenceDraft.theme">
          <el-radio-button value="dark">深色</el-radio-button>
          <el-radio-button value="light">浅色</el-radio-button>
          <el-radio-button value="system">跟随系统</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="默认模型">
        <el-select v-model="preferenceDraft.default_model" style="width: 100%">
          <el-option label="L0 规则 / 低成本" value="L0" />
          <el-option label="L1 轻量 / 快速" value="L1" />
          <el-option label="L2 通用 / 推荐" value="L2" />
          <el-option label="L3 强化 / 高精度" value="L3" />
        </el-select>
      </el-form-item>
      <el-form-item label="界面语言">
        <el-select v-model="preferenceDraft.language" style="width: 100%">
          <el-option label="简体中文" value="zh-CN" />
          <el-option label="English" value="en-US" />
        </el-select>
      </el-form-item>
      <el-form-item label="通知偏好">
        <div class="preference-switches">
          <el-switch v-model="preferenceDraft.notify_approval" active-text="审批" />
          <el-switch v-model="preferenceDraft.notify_task" active-text="任务" />
          <el-switch v-model="preferenceDraft.notify_healing" active-text="自愈" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="preferenceVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingPreferences" @click="savePreferences">保存偏好</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="degradeDetailVisible" width="640px" title="数据源降级明细">
    <p class="degrade-dialog-hint">
      当前数据源为 <strong>{{ store.dataSource.toUpperCase() }}</strong>。以下数据域在 API 模式下请求失败或 BFF 尚未实现，
      界面正在展示 Mock 数据；补齐端点后会自动恢复真实数据。
    </p>
    <el-table :data="dataSourceStatus.degraded" size="small">
      <el-table-column prop="scope" label="数据域" width="140" />
      <el-table-column prop="reason" label="降级原因" />
      <el-table-column prop="at" label="时间" width="110" />
    </el-table>
    <el-empty v-if="!degradedCount" description="当前没有降级数据域" :image-size="70" />
    <template #footer>
      <el-button @click="degradeDetailVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight, Bell, ChatDotRound, Checked, Collection, Connection, Cpu, DataBoard, Expand, Fold,
  Grid, Guide, List, Monitor, Moon, Odometer, Refresh, Search, Share, Sunny, Timer, UserFilled, View,
} from '@element-plus/icons-vue'
import { modules } from '../config/modules'
import { onlineAgents } from '../api/mock'
import { useAppStore } from '../stores/app'
import { notifications as notificationSeed, searchIndex, vitalSigns } from '../api/mock'
import { dataProvider } from '../api/provider'
import { dataSourceStatus } from '../api/status'
import type { NotificationItem, SearchItem, UserPreferences } from '../types'

const store = useAppStore()
const route = useRoute()
const router = useRouter()
const groups = ['生命体区', '工作台区', '治理区', '观测区'] as const
const iconMap: Record<string, unknown> = {
  DataBoard, Odometer, Cpu, View, Refresh, Share, List, ChatDotRound,
  UserFilled, Grid, Connection, Timer, Collection, Checked, Monitor, Guide,
}
const modulesByGroup = (group: string) => modules.filter(item => item.group === group)

const searchVisible = ref(false)
const notificationVisible = ref(false)
const preferenceVisible = ref(false)
const searchKeyword = ref('')
const activeSearchScope = ref('全部')
const searchScopes = ['全部', '任务', '知识', '技能', '案例', '审批']
const searchResults = ref<SearchItem[]>(searchIndex)
const searching = ref(false)
const searchInputRef = ref<{ focus: () => void } | null>(null)
const notificationList = ref<NotificationItem[]>(notificationSeed.map(item => ({ ...item })))
const online = ref(navigator.onLine)
const headerVitals = computed(() => vitalSigns.slice(0, 3))
const activeAgentCount = computed(() => onlineAgents.filter(agent => agent.state === 'run').length)
const unreadCount = computed(() => notificationList.value.filter(item => item.unread).length)
const filteredSearchResults = computed(() => searchResults.value.filter(item => activeSearchScope.value === '全部' || item.type === activeSearchScope.value))
const preferenceDraft = reactive<UserPreferences>({
  theme: store.themeMode,
  default_model: store.model,
  language: store.language,
  notify_approval: store.notifyApproval,
  notify_task: store.notifyTask,
  notify_healing: store.notifyHealing,
})
const savingPreferences = ref(false)
const degradeDetailVisible = ref(false)
const degradedCount = computed(() => dataSourceStatus.degraded.length)
const degradedScopes = computed(() => dataSourceStatus.degraded.map(item => item.scope).join(' / '))
const degradeTooltip = computed(() => `API 模式下降级的数据域：${degradedScopes.value || '-'}`)

function openSearch() {
  searchVisible.value = true
  nextTick(() => focusSearch())
}

async function focusSearch() {
  searchInputRef.value?.focus()
  await runSearch()
}

async function runSearch() {
  searching.value = true
  try {
    searchResults.value = await dataProvider.getSearch(searchKeyword.value) as SearchItem[]
  } finally {
    searching.value = false
  }
}

function openSearchResult(item: SearchItem) {
  searchVisible.value = false
  router.push(item.route)
}

function openNotification(item: NotificationItem) {
  item.unread = false
  notificationVisible.value = false
  if (item.route) router.push(item.route)
}

function markAllRead() {
  notificationList.value.forEach(item => { item.unread = false })
  ElMessage.success('通知已全部标记为已读')
}

function notificationLabel(type: NotificationItem['type']) {
  return { approval: '审批', task: '任务', healing: '自愈', system: '系统' }[type]
}

function openPreferences() {
  preferenceDraft.theme = store.themeMode
  preferenceDraft.default_model = store.model
  preferenceDraft.language = store.language
  preferenceDraft.notify_approval = store.notifyApproval
  preferenceDraft.notify_task = store.notifyTask
  preferenceDraft.notify_healing = store.notifyHealing
  preferenceVisible.value = true
}

async function savePreferences() {
  savingPreferences.value = true
  try {
    await dataProvider.updatePreferences({ ...preferenceDraft })
    store.updatePreferences({ ...preferenceDraft })
    preferenceVisible.value = false
    ElMessage.success('偏好设置已保存')
  } finally {
    savingPreferences.value = false
  }
}

function handleUserCommand(command: string) {
  if (command === 'preferences') openPreferences()
  if (command === 'model') openPreferences()
  if (command === 'mock') store.toggleDataSource()
}

function handleGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    openSearch()
  }
}

function updateOnlineStatus() {
  online.value = navigator.onLine
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  window.addEventListener('online', updateOnlineStatus)
  window.addEventListener('offline', updateOnlineStatus)
})
onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('online', updateOnlineStatus)
  window.removeEventListener('offline', updateOnlineStatus)
})
</script>

<style scoped>
.platform-shell { min-height: 100vh; background: transparent; }
.platform-aside {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  position: relative;
  background: var(--wp-shell-sidebar);
  border-right: 1px solid var(--wp-border);
  box-shadow: 20px 0 60px rgba(0, 0, 0, .24);
  transition: width .22s ease;
}
.platform-aside::after { content: ""; position: absolute; right: -1px; top: 0; width: 1px; height: 100%; background: linear-gradient(180deg, transparent, rgba(212,175,55,.4), transparent); }
.brand { display: flex; align-items: center; gap: 12px; height: 78px; padding: 0 18px; border-bottom: 1px solid rgba(148,163,184,.12); }
.brand.compact { justify-content: center; padding: 0; }
.brand-orbit { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; border: 1px solid rgba(94,234,212,.55); color: var(--wp-gold-soft); box-shadow: 0 0 24px rgba(94,234,212,.22), inset 0 0 18px rgba(212,175,55,.12); }
.brand-text { display: flex; flex-direction: column; min-width: 0; }
.brand-text strong { color: var(--wp-brand-text); letter-spacing: .12em; font-size: 13px; }
.brand-text span { color: var(--wp-sub); font-size: 10px; letter-spacing: .08em; }
.system-pulse { display: flex; align-items: center; gap: 8px; margin: 14px 18px; color: var(--wp-sub); font-size: 11px; letter-spacing: .12em; }
.pulse-dot, .status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--wp-success); box-shadow: 0 0 12px var(--wp-success); animation: breathe 2s ease-in-out infinite; }
.nav-scroll { flex: 1 1 auto; min-height: 0; }
.nav-scroll :deep(.el-scrollbar__wrap) { overflow-y: auto; }
.platform-menu { width: 100%; }
.platform-menu { padding: 6px 0 18px; }
.aside-footer { flex: 0 0 auto; display: flex; justify-content: space-between; padding: 0 20px; color: rgba(148,163,184,.55); font-size: 10px; letter-spacing: .16em; }
.platform-header {
  display: flex; align-items: center; gap: 10px; height: 72px;
  background: var(--wp-shell-header); border-bottom: 1px solid var(--wp-border);
  backdrop-filter: blur(20px); box-shadow: 0 12px 50px rgba(0,0,0,.22);
}
.header-icon { color: var(--wp-sub) !important; }
.header-icon:hover { color: var(--wp-gold-soft) !important; background: rgba(212,175,55,.08) !important; }
.header-brand { display: flex; flex-direction: column; line-height: 1.2; min-width: 128px; }
.header-brand strong { font-size: 15px; letter-spacing: .08em; }
.header-kicker { color: var(--wp-primary); font-size: 9px; letter-spacing: .22em; }
.global-search-trigger { display: flex; align-items: center; gap: 10px; width: min(360px, 24vw); padding: 9px 12px; border: 1px solid var(--wp-border); border-radius: 10px; background: var(--wp-input-bg); color: var(--wp-sub); cursor: pointer; text-align: left; }
.global-search-trigger span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.global-search-trigger kbd { padding: 2px 6px; border: 1px solid var(--wp-border); border-radius: 5px; color: var(--wp-sub); font-size: 10px; }
.header-vitals { display: flex; align-items: stretch; gap: 2px; margin-left: auto; border: 1px solid var(--wp-border); border-radius: 10px; overflow: hidden; }
.header-vital { display: flex; flex-direction: column; min-width: 76px; padding: 7px 10px; background: rgba(148,163,184,.04); }
.header-vital span { color: var(--wp-sub); font-size: 9px; }
.header-vital strong { margin-top: 2px; font-size: 12px; }
.header-vital.warning strong { color: #f59e0b; }
.header-vital.critical strong { color: var(--wp-danger); }
.header-status { display: flex; align-items: center; gap: 7px; color: var(--wp-sub); font-size: 10px; letter-spacing: .18em; }
.header-status.offline { color: var(--wp-danger); }
.header-status.offline .status-dot { background: var(--wp-danger); box-shadow: 0 0 12px var(--wp-danger); }
.source-tag, .tenant-tag { cursor: pointer; }
.user-avatar { background: linear-gradient(135deg, var(--wp-primary), var(--wp-gold)); color: #07111f; font-weight: 800; }
.notification-badge :deep(.el-badge__content) { border: none; background: var(--wp-danger); }
.platform-main { position: relative; padding: 24px; background: transparent; }
.ambient-line { position: absolute; left: 24px; right: 24px; height: 1px; background: linear-gradient(90deg, transparent, rgba(94,234,212,.35), rgba(212,175,55,.35), transparent); opacity: .45; }
.ambient-line.top { top: 8px; }
.ambient-line.bottom { bottom: 8px; }
.search-scope { display: flex; gap: 8px; margin: 16px 0; flex-wrap: wrap; }
.search-scope .el-tag { cursor: pointer; }
.search-results { display: flex; flex-direction: column; gap: 8px; max-height: 420px; overflow: auto; }
.search-result { display: flex; align-items: center; gap: 12px; width: 100%; padding: 12px; border: 1px solid var(--wp-border); border-radius: 12px; background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.search-result:hover { border-color: var(--wp-primary); background: rgba(94,234,212,.06); }
.search-result > div { flex: 1; }
.search-result p, .notification-item p { margin: 4px 0 0; color: var(--wp-sub); font-size: 12px; }
.dialog-hint { color: var(--wp-sub); font-size: 10px; }
.search-loading { min-height: 150px; padding: 8px; }
.notification-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; color: var(--wp-sub); font-size: 12px; }
.notification-item { display: block; width: 100%; padding: 14px 0; border: 0; border-bottom: 1px solid var(--wp-border); background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.notification-item.unread { padding-left: 12px; border-left: 2px solid var(--wp-gold); }
.notification-title { display: flex; justify-content: space-between; gap: 8px; }
.notification-title span { color: var(--wp-sub); font-size: 11px; }
.notification-item .el-tag { margin-top: 8px; }
.preference-switches { display: flex; gap: 18px; flex-wrap: wrap; }
@media (max-width: 1500px) { .header-vitals { display: none; } }
@media (max-width: 1100px) { .tenant-tag, .header-status { display: none; } .global-search-trigger { max-width: 280px; margin-left: auto; } }
@media (max-width: 900px) { .global-search-trigger, .header-brand { display: none; } .platform-main { padding: 14px; } }
.agent-online-panel { flex: 0 0 auto; max-height: 240px; margin: 8px 12px 0; padding: 10px 8px; overflow-y: auto; border: 1px solid var(--wp-border); border-radius: 12px; background: rgba(8, 15, 28, .34); }
.agent-online-head { display: flex; justify-content: space-between; padding: 0 6px 7px; color: var(--wp-sub); font-size: 10px; letter-spacing: .1em; }
.agent-online-head strong { color: var(--wp-success); }
.online-agent-row { display: grid; grid-template-columns: 8px 1fr auto; align-items: center; gap: 7px; width: 100%; padding: 5px 6px; border: 0; border-radius: 8px; background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.online-agent-row:hover { background: rgba(148,163,184,.08); }
.online-agent-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--wp-success); box-shadow: 0 0 7px rgba(52,211,153,.55); }
.online-agent-dot.wait { background: var(--wp-gold-soft); box-shadow: 0 0 7px rgba(212,175,55,.45); }
.online-agent-dot.idle { background: #64748b; box-shadow: none; }
.online-agent-copy { display: flex; flex-direction: column; min-width: 0; gap: 1px; }
.online-agent-copy strong { font-size: 10px; }
.online-agent-copy small { overflow: hidden; color: var(--wp-sub); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.online-agent-row em { color: var(--wp-sub); font-size: 8px; font-style: normal; }

/* 数据源降级提示：API 模式下回落到 Mock 时必须可见 */
.degrade-banner {
  display: flex; align-items: center; gap: 10px; margin: 0 0 14px;
  padding: 9px 14px; border: 1px solid rgba(212,175,55,.45); border-radius: 12px;
  background: linear-gradient(90deg, rgba(212,175,55,.14), rgba(212,175,55,.03));
  color: var(--wp-text); font-size: 12px; letter-spacing: .01em;
}
.degrade-banner strong { color: var(--wp-gold-soft); letter-spacing: .08em; }
.degrade-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--wp-gold-soft); box-shadow: 0 0 9px rgba(212,175,55,.65); animation: degrade-pulse 2.4s ease-in-out infinite; }
@keyframes degrade-pulse { 0%, 100% { opacity: 1 } 50% { opacity: .35 } }
.degrade-dialog-hint { margin: 0 0 14px; color: var(--wp-sub); font-size: 12px; line-height: 1.7; }
</style>

<template>
  <div class="page">
    <div class="hero-command compact-hero">
      <div class="hero-kicker">MISSION CONTROL / WB-01</div>
      <h2 class="hero-title">任务中心</h2>
      <p class="hero-desc">创建 → 规划 → 执行 → 交付 → 归档 · 点击任务进入对话，失败任务可直接重试</p>
    </div>

    <el-alert v-if="error" title="任务列表加载失败，请检查 BFF 或重试" type="error" :closable="false" show-icon>
      <template #default><el-button size="small" text type="primary" @click="loadTasks">重试</el-button></template>
    </el-alert>

    <el-card class="section-card" shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="stateFilter">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="running">进行中</el-radio-button>
          <el-radio-button value="pending">待处理</el-radio-button>
          <el-radio-button value="done">已完成</el-radio-button>
          <el-radio-button value="failed">失败</el-radio-button>
          <el-radio-button value="archived">已归档</el-radio-button>
        </el-radio-group>
        <div class="toolbar-actions">
          <span class="drag-hint">拖动 ⋮⋮ 调整优先级</span>
          <el-button type="primary" :icon="Plus" @click="createVisible = true">创建任务</el-button>
        </div>
      </div>

      <el-skeleton v-if="loading" :rows="6" animated />
      <el-empty v-else-if="!filteredTasks.length" description="当前筛选下没有任务">
        <el-button type="primary" @click="createVisible = true">创建第一个任务</el-button>
      </el-empty>
      <el-table v-else :data="filteredTasks" stripe row-key="task_id" @row-click="openTask">
        <el-table-column width="42">
          <template #default="{ row }">
            <span class="drag-handle" draggable="true" title="拖动排序" @click.stop @dragstart="dragStart(row)" @dragover.prevent @drop.stop="dropTask(row)">⋮⋮</span>
          </template>
        </el-table-column>
        <el-table-column prop="task_id" label="任务 ID" width="110" />
        <el-table-column prop="title" label="任务" min-width="220" />
        <el-table-column prop="type" label="类型" width="130" />
        <el-table-column prop="agent" label="执行方" width="150" />
        <el-table-column label="优先级" width="90">
          <template #default="{ row }"><el-tag :type="priorityType(row.priority)">{{ row.priority }}</el-tag></template>
        </el-table-column>
        <el-table-column label="进度" width="190">
          <template #default="{ row }"><el-progress :percentage="row.progress" :status="row.state === 'failed' ? 'exception' : undefined" /></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="stateType(row.state)">{{ stateLabel(row.state) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="updated_at" label="更新时间" width="100" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.state === 'failed'" size="small" type="danger" plain @click.stop="retryTask(row)">重试</el-button>
            <el-button v-else-if="row.state === 'done'" size="small" text type="primary" @click.stop="archiveTask(row)">归档</el-button>
            <el-button v-else size="small" text type="primary" @click.stop="openTask(row)">继续</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="创建任务" width="560px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="任务名称" required><el-input v-model="createForm.title" placeholder="例如：新能源行业竞品调研" /></el-form-item>
        <el-form-item label="任务类型">
          <el-select v-model="createForm.type" style="width: 100%">
            <el-option label="单 Agent" value="single_agent" />
            <el-option label="专家团协作" value="expert_team" />
            <el-option label="自动化触发" value="automation" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-radio-group v-model="createForm.priority">
            <el-radio-button value="P0">P0</el-radio-button>
            <el-radio-button value="P1">P1</el-radio-button>
            <el-radio-button value="P2">P2</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="执行要求"><el-input v-model="createForm.requirement" type="textarea" :rows="3" placeholder="描述目标、数据范围和交付物" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTask">创建并规划</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { tasks as mockTasks } from '../api/mock'
import type { TaskItem } from '../types'

const router = useRouter()
const tasks = ref<TaskItem[]>(mockTasks.map(item => ({ ...item })))
const stateFilter = ref('all')
const loading = ref(true)
const error = ref(false)
const createVisible = ref(false)
const creating = ref(false)
const dragTaskId = ref('')
const createForm = reactive({ title: '', type: 'single_agent', priority: 'P1' as TaskItem['priority'], requirement: '' })
const filteredTasks = computed(() => stateFilter.value === 'all' ? tasks.value : tasks.value.filter(item => item.state === stateFilter.value))

async function loadTasks() {
  loading.value = true
  error.value = false
  try {
    const data = await dataProvider.getTasks() as TaskItem[]
    if (Array.isArray(data) && data.length) tasks.value = data.map(item => ({ ...item }))
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

function stateType(state: string) {
  return ({ done: 'success', running: 'primary', failed: 'danger', pending: 'info', archived: 'info' } as Record<string, 'success' | 'primary' | 'danger' | 'info'>)[state] || 'info'
}

function stateLabel(state: string) {
  return ({ done: '已完成', running: '进行中', failed: '失败', pending: '待处理', archived: '已归档' } as Record<string, string>)[state] || state
}

function priorityType(priority: string) {
  return ({ P0: 'danger', P1: 'warning', P2: 'info' } as Record<string, 'danger' | 'warning' | 'info'>)[priority] || 'info'
}

function openTask(row: TaskItem) {
  router.push({ path: '/chat', query: { task_id: row.task_id } })
}

async function retryTask(row: TaskItem) {
  await dataProvider.retryTask(row.task_id)
  row.state = 'running'
  row.progress = 1
  row.updated_at = '刚刚'
  ElMessage.success(`${row.task_id} 已重新进入执行队列`)
}

function archiveTask(row: TaskItem) {
  row.state = 'archived'
  row.updated_at = '刚刚'
  ElMessage.success(`${row.task_id} 已归档`)
}

async function createTask() {
  if (!createForm.title.trim()) {
    ElMessage.warning('请输入任务名称')
    return
  }
  creating.value = true
  try {
    await dataProvider.createTask({ ...createForm })
    const task: TaskItem = {
      task_id: `T-${1043 + tasks.value.length}`,
      title: createForm.title.trim(),
      type: createForm.type,
      state: 'pending',
      progress: 0,
      priority: createForm.priority,
      agent: createForm.type === 'expert_team' ? '待分配专家团' : '待规划 Agent',
      updated_at: '刚刚',
    }
    tasks.value.unshift(task)
    createVisible.value = false
    createForm.title = ''
    createForm.requirement = ''
    stateFilter.value = 'all'
    ElMessage.success('任务已创建，正在进入规划阶段')
  } finally {
    creating.value = false
  }
}

function dragStart(row: TaskItem) {
  dragTaskId.value = row.task_id
}

function dropTask(target: TaskItem) {
  const fromIndex = tasks.value.findIndex(item => item.task_id === dragTaskId.value)
  const toIndex = tasks.value.findIndex(item => item.task_id === target.task_id)
  if (fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return
  const [moved] = tasks.value.splice(fromIndex, 1)
  tasks.value.splice(toIndex, 0, moved)
  dragTaskId.value = ''
  ElMessage.success('任务顺序已更新')
}

onMounted(loadTasks)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; }
.toolbar-actions { display: flex; align-items: center; gap: 12px; }
.drag-hint { color: var(--wp-sub); font-size: 11px; }
.drag-handle { color: var(--wp-sub); cursor: grab; user-select: none; }
:deep(.el-table__row) { cursor: pointer; }
@media (max-width: 800px) { .toolbar { align-items: flex-start; flex-direction: column; } .drag-hint { display: none; } }
</style>

<template>
  <div class="page">
    <div>
      <h2 class="page-title">任务中心</h2>
      <p class="page-sub">任务生命周期、优先级、状态与进度</p>
    </div>
    <el-card class="section-card" shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="stateFilter">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="running">进行中</el-radio-button>
          <el-radio-button label="pending">待处理</el-radio-button>
          <el-radio-button label="done">已完成</el-radio-button>
          <el-radio-button label="failed">失败</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :icon="Plus">创建任务</el-button>
      </div>
      <el-table :data="filteredTasks" stripe>
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
          <template #default="{ row }"><el-tag :type="stateType(row.state)">{{ row.state }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="updated_at" label="更新时间" width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { tasks as mockTasks } from '../api/mock'
import type { TaskItem } from '../types'

const tasks = ref<TaskItem[]>(mockTasks)
const stateFilter = ref('all')
const filteredTasks = computed(() => stateFilter.value === 'all' ? tasks.value : tasks.value.filter(item => item.state === stateFilter.value))
const stateType = (state: string) => ({ done: 'success', running: 'primary', failed: 'danger', pending: 'info', archived: 'info' }[state] || 'info')
const priorityType = (priority: string) => ({ P0: 'danger', P1: 'warning', P2: 'info' }[priority] || 'info') as 'danger' | 'warning' | 'info'

onMounted(async () => {
  const data = await dataProvider.getTasks() as TaskItem[]
  if (Array.isArray(data)) tasks.value = data
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; }
</style>

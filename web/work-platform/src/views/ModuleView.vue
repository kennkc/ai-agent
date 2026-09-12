<template>
  <div class="page">
    <div>
      <h2 class="page-title">{{ module.title }}</h2>
      <p class="page-sub">{{ module.description }} · {{ module.phase }} · {{ module.status }}</p>
    </div>
    <el-alert
      v-if="module.status === 'prototype'"
      title="当前为 Vue 3 + Element Plus 结构迁移版，业务数据仍使用 MockDataSource。"
      type="info"
      :closable="false"
      show-icon
    />
    <div class="metric-grid">
      <el-card v-for="item in summary" :key="item.label" class="section-card" shadow="never">
        <div class="metric-label">{{ item.label }}</div>
        <div class="metric-value">{{ item.value }}<span class="metric-unit">{{ item.unit }}</span></div>
        <div class="metric-trend">{{ item.trend }}</div>
      </el-card>
    </div>
    <el-card class="section-card" shadow="never">
      <template #header><strong>{{ module.title }} 工作区</strong></template>
      <el-table :data="rows" stripe>
        <el-table-column v-for="column in columns" :key="column.prop" :prop="column.prop" :label="column.label" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { moduleMap } from '../config/modules'
import type { ModuleId } from '../types'

const route = useRoute()
const module = computed(() => moduleMap[(route.meta.module as ModuleId) || 'overview'])
const summary = computed(() => [
  { label: '模块状态', value: module.value.status, unit: '', trend: module.value.phase },
  { label: '数据源', value: 'MOCK', unit: '', trend: '可切换 API' },
  { label: '路由', value: module.value.path, unit: '', trend: 'Vue Router' },
  { label: '框架', value: 'Vue 3', unit: '', trend: 'Element Plus' },
])
const columns = [
  { prop: 'name', label: '对象' },
  { prop: 'state', label: '状态' },
  { prop: 'owner', label: '执行方' },
  { prop: 'updated_at', label: '更新时间' },
]
const rows = [
  { name: `${module.value.title}示例对象`, state: 'ready', owner: 'Agent', updated_at: '刚刚' },
]
</script>

<template>
  <el-container class="platform-shell">
    <el-aside :width="store.sidebarCollapsed ? '64px' : '224px'" class="platform-aside">
      <div class="brand" :class="{ compact: store.sidebarCollapsed }">
        <div class="brand-logo">🧠</div>
        <div v-if="!store.sidebarCollapsed" class="brand-text">
          <strong>Agent-Lifeform</strong>
          <span>工作平台</span>
        </div>
      </div>
      <el-scrollbar>
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
    </el-aside>

    <el-container>
      <el-header class="platform-header">
        <el-button text circle @click="store.toggleSidebar">
          <el-icon><Fold v-if="!store.sidebarCollapsed" /><Expand v-else /></el-icon>
        </el-button>
        <div class="header-title">统一工作平台</div>
        <el-input v-model="search" class="global-search" placeholder="全局搜索：任务 / 知识 / 技能 / 案例" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="dark" @click="store.toggleDataSource">
          {{ store.dataSource.toUpperCase() }}
        </el-tag>
        <el-tag type="warning" effect="plain">{{ store.tenant }}</el-tag>
        <el-tooltip content="通知中心"><el-button text circle><el-icon><Bell /></el-icon></el-button></el-tooltip>
        <el-tooltip content="浅色 / 深色"><el-button text circle @click="store.toggleTheme"><el-icon><Moon v-if="!store.dark" /><Sunny v-else /></el-icon></el-button></el-tooltip>
        <el-dropdown>
          <el-avatar :size="32">AI</el-avatar>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item>个人偏好</el-dropdown-item>
              <el-dropdown-item>模型：{{ store.model }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="platform-main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Bell, ChatDotRound, Checked, Collection, Connection, Cpu, DataBoard, Expand, Fold,
  Grid, List, Moon, Odometer, Refresh, Search, Share, Sunny, Timer, UserFilled, View,
} from '@element-plus/icons-vue'
import { modules } from '../config/modules'
import { useAppStore } from '../stores/app'

const store = useAppStore()
const route = useRoute()
const search = ref('')
const groups = ['生命体区', '工作台区', '治理区'] as const
const iconMap: Record<string, unknown> = {
  DataBoard, Odometer, Cpu, View, Refresh, Share, List, ChatDotRound,
  UserFilled, Grid, Connection, Timer, Collection, Checked,
}
const modulesByGroup = (group: string) => modules.filter(item => item.group === group)
</script>

<style scoped>
.platform-shell { min-height: 100vh; }
.platform-aside { background: var(--wp-card); border-right: 1px solid var(--wp-border); transition: width .2s ease; }
.brand { display: flex; align-items: center; gap: 10px; height: 64px; padding: 0 16px; border-bottom: 1px solid var(--wp-border); }
.brand.compact { justify-content: center; padding: 0; }
.brand-logo { font-size: 26px; }
.brand-text { display: flex; flex-direction: column; line-height: 1.25; }
.brand-text span { color: var(--wp-sub); font-size: 12px; }
.platform-menu { min-height: calc(100vh - 64px); }
.platform-header { display: flex; align-items: center; gap: 12px; border-bottom: 1px solid var(--wp-border); background: var(--wp-card); }
.header-title { font-weight: 700; white-space: nowrap; }
.global-search { max-width: 360px; margin-left: auto; }
.platform-main { padding: 20px; background: var(--wp-bg); }
@media (max-width: 900px) {
  .global-search { display: none; }
  .header-title { display: none; }
}
</style>

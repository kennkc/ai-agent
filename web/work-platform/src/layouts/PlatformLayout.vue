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
        <el-input v-model="search" class="global-search" placeholder="搜索任务 / 知识 / 技能 / 案例" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div class="header-status">
          <span class="status-dot" />
          <span>SYNC</span>
        </div>
        <el-tag :type="store.dataSource === 'api' ? 'success' : 'info'" effect="dark" @click="store.toggleDataSource">
          {{ store.dataSource.toUpperCase() }}
        </el-tag>
        <el-tag type="warning" effect="plain">{{ store.tenant }}</el-tag>
        <el-tooltip content="通知中心"><el-button text circle class="header-icon"><el-icon><Bell /></el-icon></el-button></el-tooltip>
        <el-tooltip content="浅色 / 深色"><el-button text circle class="header-icon" @click="store.toggleTheme"><el-icon><Moon v-if="!store.dark" /><Sunny v-else /></el-icon></el-button></el-tooltip>
        <el-dropdown>
          <el-avatar :size="34" class="user-avatar">AI</el-avatar>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item>个人偏好</el-dropdown-item>
              <el-dropdown-item>模型：{{ store.model }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="platform-main">
        <div class="ambient-line top" />
        <router-view />
        <div class="ambient-line bottom" />
      </el-main>
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
.platform-shell { min-height: 100vh; background: transparent; }
.platform-aside {
  position: relative;
  background: linear-gradient(180deg, rgba(6, 10, 20, .96), rgba(10, 17, 32, .88));
  border-right: 1px solid rgba(148, 163, 184, .16);
  box-shadow: 20px 0 60px rgba(0, 0, 0, .24);
  transition: width .22s ease;
}
.platform-aside::after { content: ""; position: absolute; right: -1px; top: 0; width: 1px; height: 100%; background: linear-gradient(180deg, transparent, rgba(212,175,55,.4), transparent); }
.brand { display: flex; align-items: center; gap: 12px; height: 78px; padding: 0 18px; border-bottom: 1px solid rgba(148,163,184,.12); }
.brand.compact { justify-content: center; padding: 0; }
.brand-orbit { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; border: 1px solid rgba(94,234,212,.55); color: var(--wp-gold-soft); box-shadow: 0 0 24px rgba(94,234,212,.22), inset 0 0 18px rgba(212,175,55,.12); }
.brand-text { display: flex; flex-direction: column; min-width: 0; }
.brand-text strong { color: #f8fafc; letter-spacing: .12em; font-size: 13px; }
.brand-text span { color: var(--wp-sub); font-size: 10px; letter-spacing: .08em; }
.system-pulse { display: flex; align-items: center; gap: 8px; margin: 14px 18px; color: var(--wp-sub); font-size: 11px; letter-spacing: .12em; }
.pulse-dot, .status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--wp-success); box-shadow: 0 0 12px var(--wp-success); animation: breathe 2s ease-in-out infinite; }
.nav-scroll { height: calc(100vh - 140px); }
.platform-menu { padding: 6px 0 18px; }
.aside-footer { position: absolute; bottom: 14px; left: 0; right: 0; display: flex; justify-content: space-between; padding: 0 20px; color: rgba(148,163,184,.55); font-size: 10px; letter-spacing: .16em; }
.platform-header {
  display: flex; align-items: center; gap: 12px; height: 72px;
  background: rgba(8, 13, 25, .72); border-bottom: 1px solid rgba(148,163,184,.14);
  backdrop-filter: blur(20px); box-shadow: 0 12px 50px rgba(0,0,0,.22);
}
.header-icon { color: var(--wp-sub) !important; }
.header-icon:hover { color: var(--wp-gold-soft) !important; background: rgba(212,175,55,.08) !important; }
.header-brand { display: flex; flex-direction: column; line-height: 1.2; }
.header-brand strong { font-size: 15px; letter-spacing: .08em; }
.header-kicker { color: var(--wp-primary); font-size: 9px; letter-spacing: .22em; }
.global-search { max-width: 360px; margin-left: auto; }
.header-status { display: flex; align-items: center; gap: 7px; color: var(--wp-sub); font-size: 10px; letter-spacing: .18em; }
.user-avatar { background: linear-gradient(135deg, var(--wp-primary), var(--wp-gold)); color: #07111f; font-weight: 800; }
.platform-main { position: relative; padding: 24px; background: transparent; }
.ambient-line { position: absolute; left: 24px; right: 24px; height: 1px; background: linear-gradient(90deg, transparent, rgba(94,234,212,.35), rgba(212,175,55,.35), transparent); opacity: .45; }
.ambient-line.top { top: 8px; }
.ambient-line.bottom { bottom: 8px; }
@media (max-width: 900px) {
  .global-search, .header-status, .header-brand { display: none; }
  .platform-main { padding: 14px; }
}
</style>

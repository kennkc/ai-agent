import { createRouter, createWebHistory } from 'vue-router'
import type { ModuleId } from '../types'

// 全部路由使用动态 import：首屏只加载平台骨架，模块视图按需拉取，
// 避免把 13 个模块面板 + 观测区一次性打进初始 chunk。
const PlatformLayout = () => import('../layouts/PlatformLayout.vue')
const OverviewView = () => import('../views/OverviewView.vue')
const TasksView = () => import('../views/TasksView.vue')
const ChatView = () => import('../views/ChatView.vue')
const ModuleView = () => import('../views/ModuleView.vue')
const MiddlewareView = () => import('../views/MiddlewareView.vue')
const TracingView = () => import('../views/TracingView.vue')
const KnowledgeView = () => import('../views/KnowledgeView.vue')

const moduleRoutes = [
  'vitals', 'brain', 'senses', 'evolution', 'collab', 'experts', 'skills',
  'connectors', 'automation', 'models', 'remote', 'cases', 'approvals',
] as ModuleId[]

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: PlatformLayout,
      redirect: '/overview',
      children: [
        { path: 'overview', name: 'overview', component: OverviewView },
        { path: 'tasks', name: 'tasks', component: TasksView },
        { path: 'chat', name: 'chat', component: ChatView },
        { path: 'middleware', name: 'middleware', component: MiddlewareView },
        { path: 'knowledge', name: 'knowledge', component: KnowledgeView },
        { path: 'tracing', name: 'tracing', component: TracingView },
        ...moduleRoutes.map(id => ({
          path: id,
          name: id,
          component: ModuleView,
          meta: { module: id },
        })),
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})

export default router
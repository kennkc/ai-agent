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
const ServicesView = () => import('../views/ServicesView.vue')
const TracingView = () => import('../views/TracingView.vue')
const MetricsView = () => import('../views/MetricsView.vue')
const KnowledgeView = () => import('../views/KnowledgeView.vue')
const ExecutionView = () => import('../views/ExecutionView.vue')
// WB-10 模型接入配置：有真实写路径（CRUD），与只读模块面板拆开，避免写逻辑混进大而全的 ModuleView
const ModelsView = () => import('../views/ModelsView.vue')

// 'models' 不在其中：它有独立视图与真实写路径（见上方 ModelsView）
const moduleRoutes = [
  'vitals', 'brain', 'senses', 'evolution', 'collab', 'experts', 'skills',
  'connectors', 'automation', 'remote', 'cases', 'approvals',
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
        { path: 'services', name: 'services', component: ServicesView },
        { path: 'knowledge', name: 'knowledge', component: KnowledgeView },
        { path: 'tracing', name: 'tracing', component: TracingView },
        { path: 'metrics', name: 'metrics', component: MetricsView },
        // R-C05(预) 执行视图：工具调用流 / 拦截记录 / 沙箱状态（wp-bff /tools 聚合）
        { path: 'execution', name: 'execution', component: ExecutionView },
        // WB-10 多模型管理面板：按功能角色配置大模型接入（wp-bff /models 真实 CRUD）
        { path: 'models', name: 'models', component: ModelsView },
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
import { createRouter, createWebHistory } from 'vue-router'
import PlatformLayout from '../layouts/PlatformLayout.vue'
import OverviewView from '../views/OverviewView.vue'
import TasksView from '../views/TasksView.vue'
import ChatView from '../views/ChatView.vue'
import ModuleView from '../views/ModuleView.vue'
import MiddlewareView from '../views/MiddlewareView.vue'
import TracingView from '../views/TracingView.vue'
import type { ModuleId } from '../types'

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

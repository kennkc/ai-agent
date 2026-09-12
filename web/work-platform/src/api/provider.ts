import axios from 'axios'
import { chatMessages, metrics, serviceHealth, tasks } from './mock'

const source = (import.meta.env.VITE_DATA_SOURCE || 'mock') as 'mock' | 'api'
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/wp',
  timeout: 10000,
  headers: {
    'X-Tenant-Id': import.meta.env.VITE_TENANT_ID || 'default',
  },
})

export const dataProvider = {
  mode: source,
  async getOverview() {
    if (source === 'api') return (await api.get('/overview')).data
    return { metrics, services: serviceHealth }
  },
  async getTasks() {
    if (source === 'api') return (await api.get('/tasks')).data
    return tasks
  },
  async getChat() {
    if (source === 'api') return (await api.get('/chat/T-1042')).data
    return chatMessages
  },
  async ask(question: string) {
    if (source === 'api') return (await api.post('/chat/T-1042', { question })).data
    return {
      answer: '当前为 Mock 模式。Vue 工作平台已接入 ConsoleDataProvider，切换 VITE_DATA_SOURCE=api 后将请求真实 BFF。',
      citations: [{ title: 'Vue 工作平台说明', source: 'mock' }],
    }
  },
}

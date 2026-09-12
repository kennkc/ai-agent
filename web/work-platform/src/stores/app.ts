import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: false,
    dark: false,
    dataSource: (import.meta.env.VITE_DATA_SOURCE || 'mock') as 'mock' | 'api',
    tenant: import.meta.env.VITE_TENANT_ID || 'default',
    model: 'L2',
  }),
  actions: {
    toggleSidebar() { this.sidebarCollapsed = !this.sidebarCollapsed },
    toggleTheme() {
      this.dark = !this.dark
      document.documentElement.classList.toggle('dark', this.dark)
    },
    toggleDataSource() {
      this.dataSource = this.dataSource === 'mock' ? 'api' : 'mock'
    },
  },
})

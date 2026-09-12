import { defineStore } from 'pinia'

const savedTheme = localStorage.getItem('wp-theme') || 'dark'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: false,
    dark: savedTheme === 'dark',
    dataSource: (import.meta.env.VITE_DATA_SOURCE || 'mock') as 'mock' | 'api',
    tenant: import.meta.env.VITE_TENANT_ID || 'default',
    model: 'L2',
  }),
  actions: {
    toggleSidebar() { this.sidebarCollapsed = !this.sidebarCollapsed },
    toggleTheme() {
      this.dark = !this.dark
      document.documentElement.classList.toggle('dark', this.dark)
      localStorage.setItem('wp-theme', this.dark ? 'dark' : 'light')
    },
    toggleDataSource() {
      this.dataSource = this.dataSource === 'mock' ? 'api' : 'mock'
    },
  },
})

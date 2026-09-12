import { defineStore } from 'pinia'
import type { ThemeMode, UserPreferences } from '../types'

const savedTheme = (localStorage.getItem('wp-theme') || 'dark') as 'dark' | 'light'
const defaultPreferences: UserPreferences = {
  theme: savedTheme,
  default_model: localStorage.getItem('wp-model') || 'L2',
  language: localStorage.getItem('wp-language') || 'zh-CN',
  notify_approval: true,
  notify_task: true,
  notify_healing: true,
}

const readPreferences = (): UserPreferences => {
  try {
    return { ...defaultPreferences, ...JSON.parse(localStorage.getItem('wp-preferences') || '{}') }
  } catch {
    return defaultPreferences
  }
}

const systemPrefersDark = () => window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? true

export const useAppStore = defineStore('app', {
  state: () => {
    const preferences = readPreferences()
    const resolvedTheme = preferences.theme === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : preferences.theme
    return {
      sidebarCollapsed: false,
      dark: resolvedTheme === 'dark',
      themeMode: preferences.theme as ThemeMode,
      dataSource: (import.meta.env.VITE_DATA_SOURCE || 'mock') as 'mock' | 'api',
      tenant: import.meta.env.VITE_TENANT_ID || 'default',
      model: preferences.default_model,
      language: preferences.language,
      notifyApproval: preferences.notify_approval,
      notifyTask: preferences.notify_task,
      notifyHealing: preferences.notify_healing,
    }
  },
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
    applyTheme(mode?: ThemeMode) {
      const targetMode = mode || this.themeMode
      this.themeMode = targetMode
      this.dark = targetMode === 'system' ? systemPrefersDark() : targetMode === 'dark'
      document.documentElement.classList.toggle('dark', this.dark)
      localStorage.setItem('wp-theme', this.dark ? 'dark' : 'light')
      this.persistPreferences()
    },
    toggleTheme() {
      this.applyTheme(this.dark ? 'light' : 'dark')
    },
    updatePreferences(payload: Partial<UserPreferences>) {
      if (payload.theme) this.themeMode = payload.theme
      if (payload.default_model) this.model = payload.default_model
      if (payload.language) this.language = payload.language
      if (typeof payload.notify_approval === 'boolean') this.notifyApproval = payload.notify_approval
      if (typeof payload.notify_task === 'boolean') this.notifyTask = payload.notify_task
      if (typeof payload.notify_healing === 'boolean') this.notifyHealing = payload.notify_healing
      this.applyTheme(this.themeMode)
    },
    persistPreferences() {
      const preferences: UserPreferences = {
        theme: this.themeMode,
        default_model: this.model,
        language: this.language,
        notify_approval: this.notifyApproval,
        notify_task: this.notifyTask,
        notify_healing: this.notifyHealing,
      }
      localStorage.setItem('wp-preferences', JSON.stringify(preferences))
      localStorage.setItem('wp-model', this.model)
      localStorage.setItem('wp-language', this.language)
    },
    toggleDataSource() {
      this.dataSource = this.dataSource === 'mock' ? 'api' : 'mock'
    },
  },
})

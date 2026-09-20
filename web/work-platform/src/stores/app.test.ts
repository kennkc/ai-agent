import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAppStore } from './app'

describe('app store', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
    setActivePinia(createPinia())
  })

  it('defaults to dark theme and toggles to light', () => {
    const store = useAppStore()
    expect(store.dark).toBe(true)

    store.toggleTheme()

    expect(store.dark).toBe(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(localStorage.getItem('wp-theme')).toBe('light')
  })

  it('persists theme and preference changes', () => {
    const store = useAppStore()
    store.applyTheme('light')
    store.updatePreferences({ default_model: 'L1', notify_task: false })

    const persisted = JSON.parse(localStorage.getItem('wp-preferences') || '{}')
    expect(persisted.theme).toBe('light')
    expect(persisted.default_model).toBe('L1')
    expect(persisted.notify_task).toBe(false)
    expect(localStorage.getItem('wp-model')).toBe('L1')
  })

  it('toggles mock/api source without affecting theme', () => {
    const store = useAppStore()
    const theme = store.dark

    store.toggleDataSource()

    expect(store.dataSource).not.toBe((import.meta.env.VITE_DATA_SOURCE || 'mock'))
    expect(store.dark).toBe(theme)
  })
})
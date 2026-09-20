import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.clear())
})

test('overview loads and theme switches between dark and light', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '生命体总览' })).toBeVisible()
  await expect(page.locator('html')).toHaveClass(/dark/)

  await page.getByTestId('theme-toggle').click()
  await expect(page.locator('html')).not.toHaveClass(/dark/)

  await page.getByTestId('theme-toggle').click()
  await expect(page.locator('html')).toHaveClass(/dark/)
})

test('core observability and model pages are reachable from navigation', async ({ page }) => {
  await page.goto('/')

  await page.getByRole('menuitem', { name: '后台服务' }).click()
  await expect(page.getByRole('heading', { name: '后台服务控制台' })).toBeVisible()

  await page.getByRole('menuitem', { name: '多模型管理' }).click()
  await expect(page.getByRole('heading', { name: '模型接入配置' })).toBeVisible()
})
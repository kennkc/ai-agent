import { expect, test } from '@playwright/test'

test('live wp-bff overview and service catalog stay reachable', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '生命体总览' })).toBeVisible()
  await expect(page.getByText('API', { exact: false }).first()).toBeVisible()

  await page.getByRole('menuitem', { name: '后台服务' }).click()
  await expect(page.getByRole('heading', { name: '后台服务控制台' })).toBeVisible()
  await expect(page.getByText('WP BFF', { exact: false }).first()).toBeVisible()
})
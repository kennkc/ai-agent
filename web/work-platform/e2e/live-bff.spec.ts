import { expect, test } from '@playwright/test'

test('live wp-bff overview and service catalog stay reachable', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '生命体总览' })).toBeVisible()
  await expect(page.getByText('API', { exact: false }).first()).toBeVisible()

  await page.getByRole('menuitem', { name: '后台服务' }).click()
  await expect(page.getByRole('heading', { name: '后台服务控制台' })).toBeVisible()
  await expect(page.getByText('WP BFF', { exact: false }).first()).toBeVisible()

  await page.getByRole('menuitem', { name: /工作台区/ }).click()
  await page.getByRole('menuitem', { name: '多模型管理' }).click()
  await expect(page.getByRole('heading', { name: '模型接入配置' })).toBeVisible()
  await expect(page.getByText('模型接入运行态')).toBeVisible()
  await expect(page.getByText('真实数据', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('演示数据（BFF 未提供该数据域）')).toHaveCount(0)

  await page.getByRole('menuitem', { name: /观测区/ }).click()
  await page.getByRole('menuitem', { name: '指标监控' }).click()
  await expect(page.getByRole('heading', { name: '指标监控' })).toBeVisible()
  await expect(page.getByText('Prometheus 在线')).toBeVisible()
  await expect(page.getByText('8/8').first()).toBeVisible()
})
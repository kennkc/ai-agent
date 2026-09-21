import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: /live-.*\.spec\.ts/,
  timeout: 45_000,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:3002',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium-live', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: [
    {
      command: 'cross-env WP_BFF_PORT=8090 WP_BFF_APP_CONTROL=false node ../../services/node/wp-bff/server.js',
      url: 'http://127.0.0.1:8090/api/wp/healthz',
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: 'cross-env VITE_DATA_SOURCE=api vite --mode development --port 3002',
      url: 'http://127.0.0.1:3002',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
})
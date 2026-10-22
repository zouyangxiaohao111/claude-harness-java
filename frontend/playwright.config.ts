import { defineConfig } from '@playwright/test'

/**
 * e2e 配置 · 独立环境（⛔ 不碰日常 3000/3458 与用户真库）
 *
 *   后端：node e2e/start-backend.mjs → 端口 3460 + 独立 SQLite（D:/tmp/e2e-nexusai/data）
 *   前端：vite dev → 端口 5174，代理指向 3460（NEXUSAI_DEV_PORT / NEXUSAI_API_TARGET）
 *   浏览器：channel=chrome 复用本机 Chrome（不下载 Playwright 自带浏览器）
 *
 * 运行：cd front && npx playwright test
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 60_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5174',
    channel: 'chrome',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'node e2e/start-backend.mjs',
      url: 'http://localhost:3460/api/v1/sessions',
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'ignore',
      stderr: 'ignore',
    },
    {
      command: 'npm run dev',
      url: 'http://localhost:5174/',
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        NEXUSAI_DEV_PORT: '5174',
        NEXUSAI_API_TARGET: 'http://localhost:3460',
      },
      stdout: 'ignore',
      stderr: 'ignore',
    },
  ],
})

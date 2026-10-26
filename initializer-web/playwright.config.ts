import { defineConfig } from '@playwright/test'

const port = Number(process.env.E2E_WEB_PORT ?? 18173)
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45000,
  expect: { timeout: 10000 },
  outputDir: 'test-results/e2e',
  reporter: [['list'], ['html', { outputFolder: 'reports/e2e', open: 'never' }], ['json', { outputFile: 'reports/e2e/results.json' }]],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    browserName: 'chromium',
    launchOptions: process.env.E2E_CHROMIUM_PATH ? { executablePath: process.env.E2E_CHROMIUM_PATH } : {},
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    acceptDownloads: true,
  },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 1000 } } },
    { name: 'compact', use: { viewport: { width: 1024, height: 768 } } },
  ],
  webServer: {
    command: 'node e2e/serve.mjs',
    url: `http://127.0.0.1:${port}/api/metadata/java-versions`,
    timeout: 150000,
    reuseExistingServer: false,
  },
})

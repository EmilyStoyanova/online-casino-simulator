import { defineConfig } from '@playwright/test'
import { BACKEND_IPV4, FRONTEND_HOST, FRONTEND_PORT, FRONTEND_URL } from './e2e/support/stack'

/**
 * Browser acceptance tests (W2-16) against the real application: Chrome → Vite dev server →
 * Spring Boot jar → a throwaway PostgreSQL container (`e2e/support/stack.ts`). Needs Docker, a
 * `JAVA_HOME` pointing to JDK 21, a built backend jar and an installed Google Chrome.
 *
 * Serial on purpose: the tests share one backend, and some stop or restart it.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './e2e/support/global-setup.ts',
  globalTeardown: './e2e/support/global-teardown.ts',
  use: {
    baseURL: FRONTEND_URL,
    channel: 'chrome',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npx vite --host ${FRONTEND_HOST} --port ${FRONTEND_PORT} --strictPort`,
    url: FRONTEND_URL,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_DEV_PROXY_TARGET: BACKEND_IPV4, VITE_API_BASE_URL: '' },
  },
})

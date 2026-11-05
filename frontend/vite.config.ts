import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        // Same-origin API calls in development: no CORS, and cookies behave as in production.
        '/api': {
          target: env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      // Browser acceptance tests run with Playwright (`npm run test:e2e`), not Vitest.
      exclude: [...configDefaults.exclude, 'e2e/**'],
      restoreMocks: true,
      unstubGlobals: true,
      unstubEnvs: true,
    },
  }
})

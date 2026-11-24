import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { loadEnv, searchForWorkspaceRoot } from 'vite'
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
      // Tests only: the contract drift test imports the API contract as raw text (`openapi.yaml?raw`).
      // Only its folder (which holds nothing else) is added; Vite compares the id with its `?raw`
      // query, so a single-file entry would not match. The dev server's file access is unchanged.
      ...(mode === 'test' && {
        fs: { allow: [searchForWorkspaceRoot(process.cwd()), '../backend/src/main/resources/static'] },
      }),
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

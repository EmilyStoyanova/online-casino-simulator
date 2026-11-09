/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the backend API; empty means same origin (Vite proxy, Vercel rewrite). */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

import { removeState, startBackend, startDatabase, stopDatabase } from './stack'

/** Starts the isolated database and backend before any test (the frontend is Playwright's `webServer`). */
export default async function globalSetup(): Promise<void> {
  await startDatabase()
  try {
    await startBackend()
  } catch (error) {
    stopDatabase()
    removeState()
    throw error
  }
}

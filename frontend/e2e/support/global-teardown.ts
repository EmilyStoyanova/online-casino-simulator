import { removeState, stopBackend, stopDatabase } from './stack'

/** Removes everything the tests created: the backend process, the container (with its data) and the state file. */
export default async function globalTeardown(): Promise<void> {
  try {
    await stopBackend()
  } finally {
    stopDatabase()
    removeState()
  }
}

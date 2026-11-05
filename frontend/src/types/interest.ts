/** Mirrors the `Interest` schema of `openapi.yaml`. */
export interface Interest {
  /** Stable key that the profile stores and sends (e.g. `PROBABILITY`). */
  code: string
  name: string
  description: string
}

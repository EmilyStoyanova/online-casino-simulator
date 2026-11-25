import { useSyncExternalStore } from 'react'

/**
 * Whether this backend offers roulette, as far as the page knows (§7.7, Q10). In memory for the page
 * visit (until a reload); nothing is ever sent to find out.
 *
 * - `unknown`: nothing says otherwise; the page offers the game, and only a spin response decides.
 * - `unavailable`: a spin was answered `404`, or the platform status reported a database schema
 *   version below 9 (a backend without the roulette migrations). A version of 9 or more proves
 *   nothing (the Week 3 build starts on a V9 database), so it never makes the game "available".
 */
export type RouletteAvailability =
  | { readonly status: 'unknown' }
  | { readonly status: 'unavailable'; readonly reason: 'not-found' | 'old-schema' }

/** The first schema version that contains the roulette tables (Flyway V9). */
export const ROULETTE_SCHEMA_VERSION = 9

const UNKNOWN: RouletteAvailability = Object.freeze({ status: 'unknown' })

let current: RouletteAvailability = UNKNOWN
const listeners = new Set<() => void>()

function set(next: RouletteAvailability): void {
  if (current.status === 'unavailable' && current.reason === 'not-found') return // the strongest proof stays
  current = next
  listeners.forEach((listener) => listener())
}

/** A spin was answered `404`: no roulette on this server for the rest of the page visit. */
export function markRouletteNotFound(): void {
  set(Object.freeze({ status: 'unavailable', reason: 'not-found' }))
}

/**
 * Called with `database.schemaVersion` of the platform status the app already loads. Only a version
 * that is a whole number below 9 changes anything.
 */
export function noteSchemaVersion(schemaVersion: string | null): void {
  if (schemaVersion === null || !/^\d+$/.test(schemaVersion.trim())) return
  if (Number(schemaVersion.trim()) < ROULETTE_SCHEMA_VERSION && current.status === 'unknown') {
    set(Object.freeze({ status: 'unavailable', reason: 'old-schema' }))
  }
}

export function rouletteAvailability(): RouletteAvailability {
  return current
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function useRouletteAvailability(): RouletteAvailability {
  return useSyncExternalStore(subscribe, rouletteAvailability, rouletteAvailability)
}

/** Tests only: a fresh page visit. */
export function resetRouletteAvailability(): void {
  current = UNKNOWN
  listeners.forEach((listener) => listener())
}

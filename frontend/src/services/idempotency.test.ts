import { describe, expect, it, vi } from 'vitest'
import { newIdempotencyKey } from './idempotency'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('newIdempotencyKey', () => {
  it('returns a different canonical lower-case UUID v4 on every call', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()))

    expect(keys.size).toBe(50)
    for (const key of keys) expect(key).toMatch(UUID_V4)
  })

  it('uses crypto.randomUUID where it exists', () => {
    const randomUUID = vi.fn(() => '11111111-2222-4333-8444-555555555555')
    vi.stubGlobal('crypto', { randomUUID, getRandomValues: vi.fn() })

    expect(newIdempotencyKey()).toBe('11111111-2222-4333-8444-555555555555')
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it('builds a UUID v4 from getRandomValues outside secure contexts (no randomUUID)', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => {
        bytes.fill(0xff)
        return bytes
      },
    })

    // Version and variant bits are forced; everything else comes from the random bytes.
    expect(newIdempotencyKey()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
  })

  it('fails instead of falling back to a weak random source', () => {
    vi.stubGlobal('crypto', undefined)
    const weak = vi.spyOn(Math, 'random')

    expect(() => newIdempotencyKey()).toThrow(/secure random/)
    expect(weak).not.toHaveBeenCalled()
  })
})

/**
 * A new idempotency key: a random version-4 UUID in the canonical lower-case form.
 *
 * Only the spin orchestration (W5-07) calls this, once per genuinely new spin; retries reuse the key
 * they were created with. The API client never creates keys.
 *
 * `crypto.randomUUID` exists only in secure contexts (HTTPS, `localhost`); elsewhere the UUID is built
 * from `crypto.getRandomValues`. `Math.random` is never used.
 */
export function newIdempotencyKey(): string {
  const cryptoApi: Crypto | undefined = globalThis.crypto
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  if (typeof cryptoApi?.getRandomValues !== 'function') {
    throw new Error('This browser offers no secure random number generator.')
  }
  const bytes = cryptoApi.getRandomValues(new Uint8Array(16))
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40 // version 4
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80 // RFC 9562 variant
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

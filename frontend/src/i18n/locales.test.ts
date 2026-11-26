import { describe, expect, it, vi } from 'vitest'
import { DEFAULT_LOCALE, LOCALE_STORAGE_KEY, readStoredLocale, storeLocale } from './locales'

describe('the saved interface language (localStorage, one key)', () => {
  it('is English when nothing is saved', () => {
    expect(DEFAULT_LOCALE).toBe('en')
    expect(readStoredLocale()).toBe('en')
  })

  it('reads and writes only the locale code under its own key', () => {
    storeLocale('bg')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('bg')
    expect(readStoredLocale()).toBe('bg')
    expect(Object.keys(localStorage)).toEqual([LOCALE_STORAGE_KEY])
  })

  it.each(['fr', 'BG', 'bg-BG', '', ' en', 'null', '{"locale":"bg"}'])('falls back to English for the invalid value %j', (value) => {
    localStorage.setItem(LOCALE_STORAGE_KEY, value)
    expect(readStoredLocale()).toBe('en')
  })

  it('falls back to English when storage throws on access, and ignores a failed write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError')
    })
    expect(readStoredLocale()).toBe('en')
    expect(() => storeLocale('bg')).not.toThrow()
  })

  it('falls back to English when the localStorage property itself throws (blocked site data)', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get: () => {
        throw new DOMException('blocked', 'SecurityError')
      },
    })
    try {
      expect(readStoredLocale()).toBe('en')
      expect(() => storeLocale('bg')).not.toThrow()
    } finally {
      if (original) Object.defineProperty(globalThis, 'localStorage', original)
    }
  })
})

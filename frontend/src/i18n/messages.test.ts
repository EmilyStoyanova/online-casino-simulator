import { describe, expect, it, vi } from 'vitest'
import { bg } from './messages/bg'
import { en } from './messages/en'
import type { MessageKey } from './messages/types'
import { createI18n, msg, type Message } from './translate'

type Entry = string | { one: string; other: string }

const placeholders = (entry: Entry): string[] => {
  const texts = typeof entry === 'string' ? [entry] : [entry.one, entry.other]
  return [...new Set(texts.flatMap((text) => [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1] ?? '')))].sort()
}

describe('dictionaries: no missing or extra keys', () => {
  const enKeys = Object.keys(en).sort()
  const bgKeys = Object.keys(bg).sort()

  it('has exactly the English keys in Bulgarian (the compiler checks this too)', () => {
    expect(bgKeys.filter((key) => !(key in en))).toEqual([])
    expect(enKeys.filter((key) => !(key in bg))).toEqual([])
  })

  it('uses plural forms in Bulgarian exactly where English does', () => {
    for (const key of enKeys as MessageKey[]) {
      expect(typeof bg[key], key).toBe(typeof en[key])
    }
  })

  it('keeps every placeholder of each English text, and adds none', () => {
    for (const key of enKeys as MessageKey[]) {
      expect(placeholders(bg[key] as Entry), key).toEqual(placeholders(en[key] as Entry))
    }
  })

  it('has no empty text', () => {
    for (const [key, entry] of [...Object.entries(en), ...Object.entries(bg)] as [string, Entry][]) {
      const texts = typeof entry === 'string' ? [entry] : [entry.one, entry.other]
      for (const text of texts) expect(text.trim(), key).not.toBe('')
    }
  })

  it('translates the interface: Bulgarian texts are written in Cyrillic', () => {
    // Patterns made only of placeholders and punctuation, and the product name, are the same in both languages.
    const neutral = new Set<string>(['app.name', 'common.join', 'cell.numberSpoken', 'cell.withRange', 'slip.added'])
    const untranslated = (Object.keys(bg) as MessageKey[]).filter((key) => {
      const entry = bg[key] as Entry
      const text = (typeof entry === 'string' ? entry : entry.other).replace(/\{\w+\}/g, '')
      return !neutral.has(key) && !/[Ѐ-ӿ]/.test(text)
    })
    expect(untranslated).toEqual([])
  })
})

describe('the product name', () => {
  it('is "Online Casino Simulator" in every language; only the description around it is translated', () => {
    expect(en['app.name']).toBe('Online Casino Simulator')
    expect(bg['app.name']).toBe('Online Casino Simulator')
    expect(bg['app.metaDescription']).toMatch(/^Online Casino Simulator — [Ѐ-ӿ]/)
  })
})

describe('createI18n: interpolation, plurals and nested messages', () => {
  const english = createI18n('en')
  const bulgarian = createI18n('bg')

  it('fills placeholders and formats numbers for the language', () => {
    expect(english.t('home.lobby.title', { name: 'Alice' })).toBe('Welcome back, Alice')
    expect(english.t('slip.betsCount', { count: 3, max: 10 })).toBe('3 of 10')
    expect(bulgarian.t('slip.betsCount', { count: 3, max: 10 })).toBe('3 от 10')
    expect(english.coins(1330)).toBe('1,330 coins')
    expect(bulgarian.coins(13_300)).toBe('13 300 монети')
  })

  it('chooses the plural form by count', () => {
    expect([0, 1, 2].map((count) => english.t('common.bets', { count }))).toEqual(['0 bets', '1 bet', '2 bets'])
    expect([0, 1, 2].map((count) => bulgarian.t('common.bets', { count }))).toEqual(['0 залога', '1 залог', '2 залога'])
    expect([1, 5].map((count) => bulgarian.coins(count))).toEqual(['1 монета', '5 монети'])
    expect(bulgarian.signedCoins(-30)).toBe('−30 монети')
  })

  it('translates stored messages, nested messages included, in the language of the moment', () => {
    const stored: Message = msg('slip.added', {
      name: msg('cell.numberName', { number: 17 }),
      amount: msg('common.coins', { count: 10 }),
      summary: msg('slip.summary', { bets: msg('common.bets', { count: 1 }), total: msg('common.coins', { count: 10 }) }),
    })
    expect(english.t(stored)).toBe('Number 17: 10 coins. 1 bet, total 10 coins.')
    expect(bulgarian.t(stored)).toBe('Число 17: 10 монети. 1 залог, общо 10 монети.')
  })

  it('shows untranslatable text as it is and turns a fragment into a sentence', () => {
    expect(bulgarian.t({ text: 'Exactly as the server sent it' })).toBe('Exactly as the server sent it')
    expect(english.t({ sentence: msg('server.notBlank') })).toBe('Must not be blank.')
    expect(bulgarian.t({ sentence: msg('server.notBlank') })).toBe('Не трябва да е празно.')
  })

  it('formats dates for the language', () => {
    expect(english.date('2026-11-02T09:15:00Z')).toBe('2 November 2026')
    expect(bulgarian.date('2026-11-02T09:15:00Z')).toBe('2 ноември 2026 г.')
    expect(bulgarian.date('not a date')).toBe('—')
  })
})

describe('createI18n: missing translation keys', () => {
  it('falls back to English for a key the Bulgarian dictionary lacks', () => {
    const partial = createI18n('bg', { en, bg: { ...bg, 'slip.review': undefined } as Partial<typeof bg> })
    expect(partial.t('slip.review')).toBe('Review bets')
    expect(partial.t('slip.clear')).toBe('Изчисти всички залози')
  })

  it('shows the key itself when no dictionary has it, so the gap is visible', () => {
    const { t } = createI18n('bg')
    expect(t('does.not.exist' as MessageKey)).toBe('does.not.exist')
  })

  it('keeps a placeholder that has no value visible instead of dropping it', () => {
    const { t } = createI18n('en')
    expect(t('home.lobby.title')).toBe('Welcome back, {name}')
  })

  it('never logs while translating', () => {
    const error = vi.spyOn(console, 'error')
    const warn = vi.spyOn(console, 'warn')
    createI18n('bg').t('does.not.exist' as MessageKey)
    expect(error).not.toHaveBeenCalled()
    expect(warn).not.toHaveBeenCalled()
  })
})

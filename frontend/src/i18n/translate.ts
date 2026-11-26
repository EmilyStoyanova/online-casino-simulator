import { createElement, Fragment, type ReactNode } from 'react'
import { DEFAULT_LOCALE, INTL_LOCALE, type Locale } from './locales'
import { bg } from './messages/bg'
import { en } from './messages/en'
import type { Dictionary, MessageKey, PluralForms } from './messages/types'

export type { MessageKey } from './messages/types'

/**
 * A text to show, kept as data until it is rendered, so it follows a change of language. State that
 * outlives a render (errors, announcements, the message of an unresolved spin) stores a `Message`, never
 * a translated string.
 *
 * - `{ key, values }`: an interface text; `values` fill its `{placeholders}`.
 * - `{ text }`: text that cannot be translated (e.g. an unknown message of the server), shown as it is.
 * - `{ sentence }`: the inner message with a capital first letter and a final full stop (server field
 *   messages are lower-case fragments).
 */
export type Message =
  | { readonly key: MessageKey; readonly values?: MessageValues }
  | { readonly text: string }
  | { readonly sentence: Message }

/** A placeholder value: text as it is, a number formatted for the language, or a nested message. */
export type MessageValue = string | number | Message
export type MessageValues = Readonly<Record<string, MessageValue>>

export interface Translate {
  (key: MessageKey, values?: MessageValues): string
  (message: Message): string
}

/** Everything a component needs to show text and values in one language. */
export interface I18n {
  readonly locale: Locale
  /** The text of a key or of a stored `Message`. */
  readonly t: Translate
  /**
   * The text of a key with React elements in its placeholders (a link, a `<Coins>`), as one node, so
   * the word order stays the translation's.
   */
  readonly rich: (key: MessageKey, values: Readonly<Record<string, ReactNode>>) => ReactNode
  /** A whole number with the language's grouping: "1,330" / "1 330". */
  readonly number: (value: number) => string
  /** A percentage figure with two decimals, without the sign: "2.70" / "2,70". */
  readonly percentFigure: (value: number) => string
  /** "1 coin", "1,330 coins" / "1 монета", "1 330 монети": never a currency. */
  readonly coins: (amount: number) => string
  /** "+360 coins", "−30 coins" (U+2212 minus sign): the sign is text. */
  readonly signedCoins: (amount: number) => string
  /** A calendar date in UTC, long style: "2 November 2026" / "2 ноември 2026 г."; "—" if unreadable. */
  readonly date: (isoDate: string) => string
  /** A moment in the local (or the given) time zone, medium date and short time; "—" if unreadable. */
  readonly dateTime: (isoDate: string, timeZone?: string) => string
}

export const msg = (key: MessageKey, values?: MessageValues): Message => (values === undefined ? { key } : { key, values })

/** Untranslatable text, shown as it is. */
export const textMessage = (text: string): Message => ({ text })

/** A number of coins as a message ("10 coins"), for use inside other messages. */
export const coinsMessage = (amount: number): Message => ({ key: 'common.coins', values: { count: amount } })

export const DICTIONARIES: Readonly<Record<Locale, Partial<Record<MessageKey, string | PluralForms>>>> = Object.freeze({
  en,
  bg: bg satisfies Dictionary,
})

const PLACEHOLDER = /\{(\w+)\}/g

function isMessage(value: unknown): value is Message {
  return typeof value === 'object' && value !== null
}

/**
 * Builds the texts and formatters of `locale`. A key the dictionary lacks falls back to English, and a
 * key no dictionary has is shown as the key itself, so a gap is visible instead of an empty text.
 * `dictionaries` exists for tests only.
 */
export function createI18n(
  locale: Locale,
  dictionaries: Readonly<Record<Locale, Partial<Record<MessageKey, string | PluralForms>>>> = DICTIONARIES,
): I18n {
  const tag = INTL_LOCALE[locale]
  const plurals = new Intl.PluralRules(tag)
  const integer = new Intl.NumberFormat(tag, { maximumFractionDigits: 0 })
  const percent = new Intl.NumberFormat(tag, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const longDate = new Intl.DateTimeFormat(tag, { dateStyle: 'long', timeZone: 'UTC' })
  const dictionary = dictionaries[locale]
  const fallback = dictionaries[DEFAULT_LOCALE]

  const formatValue = (value: number): string => (Number.isInteger(value) ? integer.format(value) : String(value))

  function template(key: MessageKey, values: MessageValues | undefined): string {
    const entry = dictionary[key] ?? fallback[key]
    if (entry === undefined) return key
    if (typeof entry === 'string') return entry
    const count = values?.count
    const form = plurals.select(typeof count === 'number' ? count : Number.NaN)
    return form === 'one' ? entry.one : entry.other
  }

  const valueText = (value: MessageValue): string =>
    typeof value === 'number' ? formatValue(value) : typeof value === 'string' ? value : translateMessage(value)

  function translateKey(key: MessageKey, values?: MessageValues): string {
    return template(key, values).replace(PLACEHOLDER, (placeholder, name: string) => {
      const value = values?.[name]
      return value === undefined ? placeholder : valueText(value)
    })
  }

  function translateMessage(message: Message): string {
    if ('key' in message) return translateKey(message.key, message.values)
    if ('text' in message) return message.text
    const inner = translateMessage(message.sentence).trim()
    if (inner === '') return inner
    const capitalised = `${inner.charAt(0).toLocaleUpperCase(tag)}${inner.slice(1)}`
    return /[.!?]$/.test(capitalised) ? capitalised : `${capitalised}.`
  }

  const t = ((keyOrMessage: MessageKey | Message, values?: MessageValues) =>
    isMessage(keyOrMessage) ? translateMessage(keyOrMessage) : translateKey(keyOrMessage, values)) as Translate

  const rich: I18n['rich'] = (key, values) => {
    const parts = template(key, undefined).split(PLACEHOLDER)
    // `split` with a capture group alternates text and placeholder names: text, name, text, …
    return createElement(
      Fragment,
      null,
      ...parts.map((part, index) => createElement(Fragment, { key: index }, index % 2 === 0 ? part : (values[part] ?? `{${part}}`))),
    )
  }

  const coins = (amount: number) => t('common.coins', { count: amount })

  return Object.freeze({
    locale,
    t,
    rich,
    number: (value: number) => integer.format(value),
    percentFigure: (value: number) => percent.format(value),
    coins,
    signedCoins: (amount: number) => {
      const sign = amount > 0 ? '+' : amount < 0 ? '−' : ''
      return `${sign}${coins(Math.abs(amount))}`
    },
    date: (isoDate: string) => {
      const date = new Date(isoDate)
      return Number.isNaN(date.getTime()) ? '—' : longDate.format(date)
    },
    dateTime: (isoDate: string, timeZone?: string) => {
      const date = new Date(isoDate)
      if (Number.isNaN(date.getTime())) return '—'
      // Created per call: the local time zone is read at formatting time (tests change it).
      return new Intl.DateTimeFormat(tag, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(date)
    },
  })
}

const cache = new Map<Locale, I18n>()

/** The shared `I18n` of a locale (created once). */
export function i18nFor(locale: Locale = DEFAULT_LOCALE): I18n {
  let i18n = cache.get(locale)
  if (!i18n) {
    i18n = createI18n(locale)
    cache.set(locale, i18n)
  }
  return i18n
}

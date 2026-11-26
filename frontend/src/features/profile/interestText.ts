import { DEFAULT_LOCALE, type Locale } from '../../i18n/locales'
import { i18nFor, type MessageKey } from '../../i18n/translate'
import type { Interest } from '../../types/interest'

/**
 * The interest catalogue comes from the server in English (`GET /api/v1/interests`, seeded by the V5
 * migration). In English the server's texts are shown unchanged; in another language the known codes
 * use the dictionary, and a code added later on the server keeps the server's text until it is
 * translated here. The API and the stored codes are unchanged.
 */
const KNOWN_INTERESTS = {
  PROBABILITY: ['interest.PROBABILITY.name', 'interest.PROBABILITY.description'],
  MATHEMATICS: ['interest.MATHEMATICS.name', 'interest.MATHEMATICS.description'],
  STATISTICS: ['interest.STATISTICS.name', 'interest.STATISTICS.description'],
  GAME_THEORY: ['interest.GAME_THEORY.name', 'interest.GAME_THEORY.description'],
  PSYCHOLOGY: ['interest.PSYCHOLOGY.name', 'interest.PSYCHOLOGY.description'],
  FINANCIAL_LITERACY: ['interest.FINANCIAL_LITERACY.name', 'interest.FINANCIAL_LITERACY.description'],
  HISTORY: ['interest.HISTORY.name', 'interest.HISTORY.description'],
  RESPONSIBLE_PLAY: ['interest.RESPONSIBLE_PLAY.name', 'interest.RESPONSIBLE_PLAY.description'],
} as const satisfies Record<string, readonly [MessageKey, MessageKey]>

function keysOf(code: string): readonly [MessageKey, MessageKey] | undefined {
  return Object.prototype.hasOwnProperty.call(KNOWN_INTERESTS, code)
    ? KNOWN_INTERESTS[code as keyof typeof KNOWN_INTERESTS]
    : undefined
}

export function interestName(interest: Interest, locale: Locale): string {
  const keys = keysOf(interest.code)
  return locale === DEFAULT_LOCALE || keys === undefined ? interest.name : i18nFor(locale).t(keys[0])
}

export function interestDescription(interest: Interest, locale: Locale): string {
  const keys = keysOf(interest.code)
  return locale === DEFAULT_LOCALE || keys === undefined ? interest.description : i18nFor(locale).t(keys[1])
}

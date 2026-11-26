import type { en } from './en'

/** An entry chosen by `count` through `Intl.PluralRules` (English and Bulgarian use `one` and `other`). */
export interface PluralForms {
  readonly one: string
  readonly other: string
}

/** Every key of the interface texts; the English dictionary is the source. */
export type MessageKey = keyof typeof en

/**
 * A complete dictionary: exactly the English keys, a plain text where English has one and plural forms
 * where English has them. A missing or extra key is a compile error.
 */
export type Dictionary = {
  readonly [K in MessageKey]: (typeof en)[K] extends string ? string : PluralForms
}

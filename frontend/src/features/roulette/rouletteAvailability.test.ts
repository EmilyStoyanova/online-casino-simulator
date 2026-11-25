import { describe, expect, it } from 'vitest'
import { markRouletteNotFound, noteSchemaVersion, rouletteAvailability } from './rouletteAvailability'

describe('rouletteAvailability (§7.7, Q10)', () => {
  it('starts unknown: the page offers the game until a spin response says otherwise', () => {
    expect(rouletteAvailability()).toEqual({ status: 'unknown' })
  })

  it('a schema version below 9 is an early hint that roulette is missing', () => {
    noteSchemaVersion('7')
    expect(rouletteAvailability()).toEqual({ status: 'unavailable', reason: 'old-schema' })
  })

  it('a version of 9 or more, a missing or an unreadable version proves nothing', () => {
    for (const version of ['9', '10', null, '', 'V7', '8.1']) {
      noteSchemaVersion(version)
      expect(rouletteAvailability(), String(version)).toEqual({ status: 'unknown' })
    }
  })

  it('a 404 makes it unavailable for the rest of the page visit; nothing turns it back', () => {
    markRouletteNotFound()
    noteSchemaVersion('9')
    noteSchemaVersion('7')
    expect(rouletteAvailability()).toEqual({ status: 'unavailable', reason: 'not-found' })
  })
})

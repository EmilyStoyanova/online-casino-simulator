import { describe, expect, it } from 'vitest'

/**
 * W5-10 regression (Lighthouse `color-contrast`, WCAG 1.4.3): `text-zinc-500` on the app's dark cards
 * (about 3.8:1) and page background (about 4.1:1) is below 4.5:1 for small text. Secondary text uses
 * `text-zinc-400` (about 6.8:1). jsdom does not apply Tailwind, so the classes are checked in the
 * source files, read as raw text through Vite. Placeholders are not content and keep their own style.
 */
const sources = import.meta.glob<string>(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!../test/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
})

// The mid greys are the trap on the dark surfaces. Darker greys (zinc-700 to 950) appear only as text on
// light surfaces, such as the 1-coin and 100-coin chips, where they have a high contrast.
const LOW_CONTRAST_TEXT = /(?<![\w:-])text-zinc-(?:500|600)\b/g

describe('text contrast classes', () => {
  it('reads the application sources', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(50)
  })

  it('uses no mid-grey (zinc-500/600) text colour (placeholders excepted)', () => {
    const findings = Object.entries(sources).flatMap(([file, text]) =>
      text.split('\n').flatMap((line, index) => [...line.matchAll(LOW_CONTRAST_TEXT)].map((m) => `${file}:${index + 1} ${m[0]}`)),
    )
    expect(findings).toEqual([])
  })
})

import { describe, expect, it } from 'vitest'
import { loginPath, registerPath, safeReturnPath } from './returnPath'

describe('safeReturnPath', () => {
  it('keeps internal paths with their query and fragment', () => {
    expect(safeReturnPath('/vault')).toBe('/vault')
    expect(safeReturnPath('/vault/coins?tab=history&page=2#top')).toBe('/vault/coins?tab=history&page=2#top')
    // Normalised like the browser would; still a path of this application.
    expect(safeReturnPath('/a/../vault')).toBe('/vault')
    expect(safeReturnPath('/%2F%2Fevil.example')).toBe('/%2F%2Fevil.example')
  })

  it('falls back to the home page for anything that could leave the application', () => {
    for (const value of [
      null,
      undefined,
      '',
      'vault',
      'https://evil.example/vault',
      'HTTPS://evil.example',
      'javascript:alert(1)',
      '//evil.example',
      '///evil.example',
      '/\\evil.example',
      '\\\\evil.example',
      '/\t/evil.example',
      '/\n/evil.example',
      ' /vault',
      '/vault\u0000',
    ]) {
      expect(safeReturnPath(value), JSON.stringify(value)).toBe('/')
    }
  })

  it('never returns to a sign-in page', () => {
    expect(safeReturnPath('/login')).toBe('/')
    expect(safeReturnPath('/register?returnTo=%2Fvault')).toBe('/')
  })
})

describe('loginPath and registerPath', () => {
  it('remember an internal page, but not the home page or an unsafe value', () => {
    expect(loginPath('/vault?tab=coins#top')).toBe('/login?returnTo=%2Fvault%3Ftab%3Dcoins%23top')
    expect(registerPath('/vault')).toBe('/register?returnTo=%2Fvault')
    expect(loginPath()).toBe('/login')
    expect(loginPath('/')).toBe('/login')
    expect(loginPath('//evil.example')).toBe('/login')
  })
})

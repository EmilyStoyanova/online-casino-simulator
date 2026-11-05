import { describe, expect, it } from 'vitest'
import { deleteCookie, setCookie } from '../test/http'
import { readCsrfToken } from './csrf'

describe('readCsrfToken', () => {
  it('returns null without an XSRF-TOKEN cookie', () => {
    setCookie('OTHER', 'x')

    expect(readCsrfToken()).toBeNull()
  })

  it('reads and decodes the token among other cookies', () => {
    setCookie('A', '1')
    setCookie('XSRF-TOKEN', 'a b/c=')
    setCookie('B', '2')

    expect(readCsrfToken()).toBe('a b/c=')
  })

  it('treats a deleted token as missing', () => {
    setCookie('XSRF-TOKEN', 'abc')
    deleteCookie('XSRF-TOKEN')

    expect(readCsrfToken()).toBeNull()
  })
})

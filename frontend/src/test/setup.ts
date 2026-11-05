import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'
import { clearCookies } from './http'

// `findBy…`/`waitFor` wait 1 s by default; whole-app tests (several typed fields and requests) can
// take longer on a loaded machine or CI runner. Waiting longer changes no expectation.
configure({ asyncUtilTimeout: 3000 })

afterEach(() => {
  cleanup()
  clearCookies()
  localStorage.clear()
  sessionStorage.clear()
})

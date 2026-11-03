import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { clearCookies } from './http'

afterEach(() => {
  cleanup()
  clearCookies()
  localStorage.clear()
  sessionStorage.clear()
})

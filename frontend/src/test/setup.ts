import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { resetRouletteAvailability } from '../features/roulette/rouletteAvailability'
import { resetSpinFlights } from '../features/roulette/useSpin'
import { fakeWheel } from './fakeWheelGame'
import { clearCookies } from './http'

// jsdom has no canvas/WebGL: every test gets the recording fake instead of the Phaser game, and a
// canvas check the test controls (`fakeWheel.canvasSupported`).
vi.mock('../games/roulette/createWheelGame', () => import('./fakeWheelGame'))
vi.mock('../games/roulette/canvasSupport', () => ({ canRenderWheel: () => fakeWheel.canvasSupported }))

// `findBy…`/`waitFor` wait 1 s by default; whole-app tests (several typed fields and requests) can
// take longer on a loaded machine or CI runner. Waiting longer changes no expectation.
configure({ asyncUtilTimeout: 3000 })

afterEach(() => {
  cleanup()
  fakeWheel.reset()
  clearCookies()
  localStorage.clear()
  sessionStorage.clear()
  resetRouletteAvailability()
  resetSpinFlights()
})

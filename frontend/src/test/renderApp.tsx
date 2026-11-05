import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { App } from '../App'

/** Renders the whole application at `path`, as after a full page load (fresh in-memory state). */
export function renderApp(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

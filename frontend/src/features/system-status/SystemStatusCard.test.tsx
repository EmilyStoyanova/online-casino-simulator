import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { jsonResponse, stubFetch } from '../../test/http'
import type { SystemStatus } from '../../types/system'
import { SystemStatusCard } from './SystemStatusCard'

const upStatus: SystemStatus = {
  application: 'online-casino-simulator',
  version: '0.1.0-SNAPSHOT',
  status: 'UP',
  database: { status: 'UP', schemaVersion: '1' },
  timestamp: '2026-01-15T10:00:00Z',
}

describe('SystemStatusCard', () => {
  it('shows backend and database status reported by the API', async () => {
    stubFetch().mockResolvedValue(jsonResponse(upStatus))

    render(<SystemStatusCard />)

    expect(screen.getByText('Checking backend…')).toBeInTheDocument()
    expect(await screen.findByText('0.1.0-SNAPSHOT')).toBeInTheDocument()
    expect(screen.getAllByText('UP')).toHaveLength(2)
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('shows a degraded status when the backend reports the database as down (503)', async () => {
    const downStatus: SystemStatus = {
      ...upStatus,
      status: 'DOWN',
      database: { status: 'DOWN', schemaVersion: null },
    }
    stubFetch().mockResolvedValue(jsonResponse(downStatus, 503))

    render(<SystemStatusCard />)

    expect(await screen.findAllByText('DOWN')).toHaveLength(2)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('shows an error and recovers after refreshing', async () => {
    const fetchMock = stubFetch()
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(jsonResponse(upStatus))

    render(<SystemStatusCard />)

    expect(await screen.findByText(/Backend unavailable/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))

    expect(await screen.findByText('0.1.0-SNAPSHOT')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

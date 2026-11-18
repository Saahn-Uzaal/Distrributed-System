// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }: { children: React.ReactNode }) => <div data-testid="map">{children}</div>,
  TileLayer: ({ attribution }: { attribution: string }) => <span>{attribution}</span>,
  Marker: ({ position, children }: { position: [number, number]; children: React.ReactNode }) =>
    <div data-testid="marker" data-position={position.join(',')}>{children}</div>,
  Popup: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
  GeoJSON: ({ data }: { data: unknown }) => <div data-testid="route">{JSON.stringify(data)}</div>,
  useMap: () => ({ fitBounds: vi.fn() })
}))

import RouteMap from './RouteMap'

afterEach(cleanup)

describe('RouteMap', () => {
  it('renders pickup destination route and OpenStreetMap attribution', () => {
    render(<RouteMap
      pickup={{ latitude: 21.0288, longitude: 105.8525, address: 'Hồ Gươm' }}
      destination={{ latitude: 21.0368, longitude: 105.8347, address: 'Lăng Bác' }}
      geometry={{ type: 'LineString', coordinates: [[105.8525, 21.0288], [105.8347, 21.0368]] }}
    />)

    expect(screen.getAllByTestId('marker').map((node) => node.dataset.position))
      .toEqual(['21.0288,105.8525', '21.0368,105.8347'])
    expect(screen.getByTestId('route').textContent).toContain('105.8525')
    expect(screen.getByText(/OpenStreetMap/)).toBeTruthy()
  })
})

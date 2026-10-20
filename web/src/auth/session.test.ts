import { describe, expect, it } from 'vitest'
import { routeForRole } from './session'

describe('routeForRole — route theo role (T08 acceptance)', () => {
  it('PASSENGER → /passenger', () => {
    expect(routeForRole('PASSENGER')).toBe('/passenger')
  })
  it('DRIVER → /driver', () => {
    expect(routeForRole('DRIVER')).toBe('/driver')
  })
})

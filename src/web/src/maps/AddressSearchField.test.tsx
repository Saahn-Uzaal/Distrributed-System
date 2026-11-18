// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import AddressSearchField from './AddressSearchField'

afterEach(cleanup)

it('does not search while typing and selects only an explicit result', async () => {
  const search = vi.fn().mockResolvedValue([
    { locationToken: 'loc-1', displayName: 'Hồ Gươm, Hà Nội', latitude: 21.0288, longitude: 105.8525 }
  ])
  const select = vi.fn()
  render(<AddressSearchField label="Điểm đón" search={search} onSelect={select} />)

  fireEvent.change(screen.getByLabelText('Điểm đón'), { target: { value: 'Hồ Gươm' } })
  expect(search).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Tìm điểm đón' }))
  await screen.findByText('Hồ Gươm, Hà Nội')
  expect(search).toHaveBeenCalledWith('Hồ Gươm')
  expect(select).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('Hồ Gươm, Hà Nội'))
  await waitFor(() => expect(select).toHaveBeenCalledWith(expect.objectContaining({ locationToken: 'loc-1' })))
})

it('invalidates the selected address as soon as its text is edited', () => {
  const onQueryChange = vi.fn()
  render(<AddressSearchField
    label="Điểm đón"
    search={vi.fn()}
    onSelect={vi.fn()}
    onQueryChange={onQueryChange}
    selected={{ locationToken: 'loc-1', displayName: 'Hồ Gươm, Hà Nội', latitude: 21.0288, longitude: 105.8525 }}
  />)

  fireEvent.change(screen.getByLabelText('Điểm đón'), { target: { value: 'Hồ Tây' } })

  expect(onQueryChange).toHaveBeenCalledWith('Hồ Tây')
})

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState } from '../testing'
import { FakeBackend } from '../fakeBackend'

afterEach(cleanup)

it('saves speed units and converts the live speed without reconnecting', async () => {
  const backend = new FakeBackend()
  backend.data.values['gps.enabled'] = 'true'
  backend.data.applies['gps.units'] = 'now'
  render(<Shell state={appState({ phase: 'active', gps: {
    position: [50, 30], heading: 90, speed: 161, rpm: 3000, gear: 'D3', throttle: 0.2, brake: 0,
    keyboard: true, gamepad: false, listening: true, sentences: [], sent: 1,
  } })} backend={backend} />)
  fireEvent.click(screen.getByRole('tab', { name: 'GPS' }))
  const units = await screen.findByRole('radiogroup', { name: 'Speed units' })
  expect(within(units).getByRole('radio', { name: 'km/h' }).getAttribute('aria-checked')).toBe('true')
  fireEvent.click(within(units).getByRole('radio', { name: 'mph' }))
  await waitFor(() => expect(backend.saved).toEqual([{ 'gps.units': 'imperial' }]))
  await screen.findByText(/100 mph/)
  fireEvent.click(within(units).getByRole('radio', { name: 'km/h' }))
  await waitFor(() => expect(backend.saved.at(-1)).toEqual({ 'gps.units': 'metric' }))
  await screen.findByText(/161 km\/h/)
})

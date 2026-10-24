import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { AppState, GpsState } from '../api'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

const live = { phase: 'active', canShowCarPlay: true } as const

const car: GpsState = {
  position: [50.4501, 30.5234],
  heading: 90,
  speed: 48,
  rpm: 2300,
  gear: 'D3',
  throttle: 0.4,
  brake: 0,
  keyboard: true,
  gamepad: false,
  listening: true,
  sentences: ['GGA', 'RMC', 'PASCD'],
  sent: 12,
}

/** A backend whose settings have the GPS [on], with the gps.* keys applying as the app says. */
function gpsBackend(on: boolean) {
  const backend = new FakeBackend()
  backend.data = {
    ...backend.data,
    values: { ...backend.data.values, 'gps.enabled': String(on), 'gps.start': '', 'gps.heading': '0', 'gps.car.power.hp': '150' },
    applies: {
      ...backend.data.applies,
      'gps.enabled': 'reconnect', 'gps.wheel.speed': 'reconnect', 'gps.satellites': 'reconnect',
      'gps.start': 'now', 'gps.heading': 'now', 'gps.resume': 'now', 'gps.car.power.hp': 'now', 'gps.car.mass.kg': 'now',
      'gps.gauges': 'now',
    },
  }
  return backend
}

async function gps(state: AppState = appState(live), backend = gpsBackend(true)) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'GPS' }))
  return backend
}

const rowText = (label: string) => screen.getByText(label, { selector: 'label' }).parentElement!.textContent

describe('GPS page', () => {
  // The iPhone hears of the GPS as it connects: the switch says so, as the AltScreen's does.
  it('turns the GPS on, from the next connection', async () => {
    const backend = await gps(appState(live), gpsBackend(false))

    expect(screen.getByText('Applies at the next connection')).toBeTruthy()
    fireEvent.click(screen.getByRole('checkbox', { name: "Send the car's position to the iPhone" }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.enabled': 'true' }]))
  })

  // Off, the GPS has nothing to set: the rest waits for it, as the AltScreen's page does.
  it('holds the rest while the GPS is off', async () => {
    await gps(appState(live), gpsBackend(false))

    const held = screen.getByRole('textbox', { name: 'Start point' }).closest('[inert]') as HTMLElement
    expect(held).not.toBeNull()
    expect(held.title).toBe('Turn on the GPS to set it')
    expect(held.contains(screen.getByRole('textbox', { name: 'Power' }))).toBe(true)
    expect(held.contains(screen.getByRole('checkbox', { name: "Send the car's position to the iPhone" }))).toBe(false)
  })

  it('saves where the car starts and what car it is', async () => {
    const backend = await gps()

    const start = screen.getByRole('textbox', { name: 'Start point' }) as HTMLInputElement
    fireEvent.change(start, { target: { value: 'https://maps.apple.com/?ll=50.4501,30.5234' } })
    fireEvent.blur(start)
    const power = screen.getByRole('textbox', { name: 'Power' }) as HTMLInputElement
    fireEvent.change(power, { target: { value: '300' } })
    fireEvent.blur(power)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show the speedometer and tachometer on the AltScreen' }))

    await waitFor(() =>
      expect(backend.saved).toEqual([
        { 'gps.start': 'https://maps.apple.com/?ll=50.4501,30.5234' },
        { 'gps.car.power.hp': '300' },
        { 'gps.gauges': 'false' },
      ]),
    )
  })

  // What the car does now, and what the iPhone takes from it.
  it('shows the car and what the iPhone asked for', async () => {
    await gps(appState({ ...live, gps: car }))

    expect(rowText('iPhone')).toContain('Listening: GGA, RMC, PASCD; 12 updates sent')
    expect(rowText('Driving')).toBe('Driving50.45010, 30.52340 · 90° · 48 km/h · 2300 rpm · D3')
    expect(screen.getByText('Drive mode on · No gamepad')).toBeTruthy()
  })

  it('says when the car has no start yet and the iPhone is not listening', async () => {
    await gps(appState({ ...live, gps: { ...car, position: null, listening: false, sentences: null, sent: 0, keyboard: false } }))

    expect(rowText('iPhone')).toContain('Not listening')
    expect(rowText('Driving')).toContain('No start point')
    expect(screen.getByText('Drive mode off (F10) · No gamepad')).toBeTruthy()
  })
})

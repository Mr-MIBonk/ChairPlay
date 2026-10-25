import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { AppState, GpsState } from '../api'
import { Shell } from '../shell/Shell'
import { appState } from '../testing'
import { FakeBackend } from '../fakeBackend'

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
    values: {
      ...backend.data.values,
      'gps.enabled': String(on),
      'gps.start': '',
      'gps.heading': '0',
      'gps.car.power.hp': '150',
    },
    applies: {
      ...backend.data.applies,
      'gps.enabled': 'reconnect',
      'gps.wheel.speed': 'reconnect',
      'gps.satellites': 'reconnect',
      'gps.start': 'now',
      'gps.heading': 'now',
      'gps.car.power.hp': 'now',
      'gps.car.mass.kg': 'now',
    },
  }
  return backend
}

describe('GPS page, Windows location', () => {
  // The button puts the car where Windows locates this PC, as typed coordinates do.
  it('puts the car where Windows locates this PC', async () => {
    const backend = gpsBackend(true)
    backend.location = { latitude: 48.85837, longitude: 2.294481 }
    await gps(appState(live), backend)

    fireEvent.click(screen.getByRole('button', { name: 'Use Windows location' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.start': '48.85837, 2.294481', 'gps.heading': '0' }]))
  })

  // Location off, or desktop apps not let use it: nothing moves, and the reason comes with a way to Windows' settings.
  it('moves nothing when Windows will not tell, and opens its location settings', async () => {
    const backend = gpsBackend(true)
    backend.location = { error: 'denied' }
    await gps(appState(live), backend)

    fireEvent.click(screen.getByRole('button', { name: 'Use Windows location' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Location settings' }))

    expect(backend.saved).toEqual([])
    expect(backend.opened).toEqual(['location'])
  })
})

async function gps(state: AppState = appState(live), backend = gpsBackend(true)) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'GPS' }))
  return backend
}

describe('GPS page', () => {
  it('turns the GPS on', async () => {
    const backend = await gps(appState(live), gpsBackend(false))

    fireEvent.click(screen.getByRole('checkbox', { name: 'Enable spoofing' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.enabled': 'true' }]))
  })

  // Off, the GPS has nothing to set: the rest waits for it, as the AltScreen's page does.
  it('holds the rest while the GPS is off', async () => {
    await gps(appState(live), gpsBackend(false))

    const held = screen.getByRole('textbox', { name: 'latitude' }).closest('[inert]') as HTMLElement
    expect(held).not.toBeNull()
    expect(held.contains(screen.getByRole('textbox', { name: 'Power' }))).toBe(true)
    expect(held.contains(screen.getByRole('checkbox', { name: 'Enable spoofing' }))).toBe(false)
  })

  it('saves what car it is', async () => {
    const backend = await gps()

    const power = screen.getByRole('textbox', { name: 'Power' }) as HTMLInputElement
    fireEvent.change(power, { target: { value: '600' } })
    fireEvent.blur(power)

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.car.power.hp': '600' }]))
  })

  // Drive mode, as F10 turns it: the switch shows it as the app has it, and turns it on or off.
  it('turns drive mode on and off', async () => {
    const backend = gpsBackend(true)
    const { rerender } = render(
      <Shell state={appState({ ...live, gps: { ...car, keyboard: false } })} backend={backend} />,
    )
    await screen.findByRole('textbox', { name: 'Network' })
    fireEvent.click(screen.getByRole('tab', { name: 'GPS' }))

    const driveMode = screen.getByRole('checkbox', { name: 'Drive mode' }) as HTMLInputElement
    expect(driveMode.checked).toBe(false)
    fireEvent.click(driveMode)
    await waitFor(() => expect(backend.driveModes).toEqual([true]))
    rerender(<Shell state={appState({ ...live, gps: { ...car, keyboard: true } })} backend={backend} />)
    expect(driveMode.checked).toBe(true)
    fireEvent.click(driveMode)

    await waitFor(() => expect(backend.driveModes).toEqual([true, false]))
    expect(backend.saved).toEqual([])
  })

  // Latitude first, then longitude, as map apps give them; both go into gps.start together.
  it('saves the start point from its latitude and longitude', async () => {
    const backend = gpsBackend(true)
    backend.data.values['gps.start'] = '48.8738, 2.2950'
    await gps(appState(live), backend)

    const latitude = screen.getByRole('textbox', { name: 'latitude' }) as HTMLInputElement
    const longitude = screen.getByRole('textbox', { name: 'longitude' }) as HTMLInputElement
    expect([latitude.value, longitude.value]).toEqual(['48.8738', '2.2950'])
    fireEvent.change(longitude, { target: { value: '2.3000' } })
    fireEvent.blur(longitude)

    // The car goes there facing as the heading field says.
    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.start': '48.8738, 2.3000', 'gps.heading': '0' }]))
  })

  // The fields are where the car is now: they follow it as it drives, and a value typed moves it there.
  it('shows where the car is as it drives', async () => {
    const backend = gpsBackend(true)
    backend.data.values['gps.start'] = '48.8738, 2.2950'
    const { rerender } = render(<Shell state={appState({ ...live, gps: car })} backend={backend} />)
    await screen.findByRole('textbox', { name: 'Network' })
    fireEvent.click(screen.getByRole('tab', { name: 'GPS' }))

    const latitude = screen.getByRole('textbox', { name: 'latitude' }) as HTMLInputElement
    const heading = screen.getByRole('textbox', { name: 'Heading' }) as HTMLInputElement
    expect([latitude.value, heading.value]).toEqual(['50.45010', '90'])
    rerender(
      <Shell
        state={appState({ ...live, gps: { ...car, position: [50.4502, 30.5234], heading: 95 } })}
        backend={backend}
      />,
    )
    expect([latitude.value, heading.value]).toEqual(['50.45020', '95'])

    // Typing holds the field still while the car moves on.
    fireEvent.change(heading, { target: { value: '180' } })
    rerender(<Shell state={appState({ ...live, gps: { ...car, heading: 100 } })} backend={backend} />)
    expect(heading.value).toBe('180')
    fireEvent.blur(heading)

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.heading': '180' }]))
  })

  // A pair or a map link pasted into either field fills both.
  it('takes a pair or a map link pasted into a field', async () => {
    const backend = await gps()

    const latitude = screen.getByRole('textbox', { name: 'latitude' }) as HTMLInputElement
    fireEvent.change(latitude, { target: { value: 'https://maps.apple.com/?ll=50.4501,30.5234&q=Kyiv' } })
    expect((screen.getByRole('textbox', { name: 'longitude' }) as HTMLInputElement).value).toBe('30.5234')
    fireEvent.blur(latitude)

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.start': '50.4501, 30.5234', 'gps.heading': '0' }]))
  })

  // One field alone is no place: the page marks the fields and saves nothing.
  it('refuses one half alone, or a number off the globe', async () => {
    const backend = await gps()

    const latitude = screen.getByRole('textbox', { name: 'latitude' }) as HTMLInputElement
    fireEvent.change(latitude, { target: { value: '48.87' } })
    fireEvent.blur(latitude)
    expect(latitude.getAttribute('aria-invalid')).toBe('true')
    const longitude = screen.getByRole('textbox', { name: 'longitude' }) as HTMLInputElement
    fireEvent.change(longitude, { target: { value: '200' } })
    fireEvent.blur(longitude)
    expect(longitude.getAttribute('aria-invalid')).toBe('true')

    expect(backend.saved).toEqual([])
  })

  // A city puts the car on one of its busy roads, facing along it; the city it stands in is the one chosen.
  it('puts the car in a city from its presets', async () => {
    const backend = gpsBackend(true)
    backend.data.values['gps.start'] = '48.87604, 2.28792'
    backend.data.values['gps.heading'] = '116'
    await gps(appState(live), backend)

    expect(screen.getByRole('radio', { name: 'PAR' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'NYC' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.start': '40.74389, -74.00841', 'gps.heading': '7' }]))
  })

  // How the fixes go, for finding what the iPhone takes: the rate, 4 a second as it asks, and the sentences.
  it('sets the GPS rate and sentences', async () => {
    const backend = await gps()

    const fixes = screen.getByRole('radiogroup', { name: 'Updates' })
    expect(within(fixes).getByRole('radio', { name: '4' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'Classic' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(within(fixes).getByRole('radio', { name: '10' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Modern' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.rate': '10' }, { 'gps.nmea': 'modern' }]))
  })

  // The car's handling and the gauges' size: normal and large until set.
  it('sets the handling and the gauges', async () => {
    const backend = await gps()

    const handling = screen.getByRole('radiogroup', { name: 'Handling' })
    const gauges = screen.getByRole('radiogroup', { name: 'Gauges' })
    expect(within(handling).getByRole('radio', { name: 'Normal' }).getAttribute('aria-checked')).toBe('true')
    expect(within(gauges).getByRole('radio', { name: 'Large' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(within(handling).getByRole('radio', { name: 'Sharp' }))
    fireEvent.click(within(gauges).getByRole('radio', { name: 'Small' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.car.handling': 'sharp' }, { 'gps.gauges': 'small' }]))
  })
})

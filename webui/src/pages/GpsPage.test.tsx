import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
    fireEvent.click(screen.getByRole('checkbox', { name: "Spoof the iPhone's GPS" }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.enabled': 'true' }]))
  })

  // Off, the GPS has nothing to set: the rest waits for it, as the AltScreen's page does.
  it('holds the rest while the GPS is off', async () => {
    await gps(appState(live), gpsBackend(false))

    const held = screen.getByRole('textbox', { name: 'Latitude' }).closest('[inert]') as HTMLElement
    expect(held).not.toBeNull()
    expect(held.title).toBe('Turn on the GPS to set it')
    expect(held.contains(screen.getByRole('textbox', { name: 'Power' }))).toBe(true)
    expect(held.contains(screen.getByRole('checkbox', { name: "Spoof the iPhone's GPS" }))).toBe(false)
  })

  it('saves what car it is', async () => {
    const backend = await gps()

    const power = screen.getByRole('textbox', { name: 'Power' }) as HTMLInputElement
    fireEvent.change(power, { target: { value: '300' } })
    fireEvent.blur(power)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show gauges on the AltScreen' }))
    // As the position's fields: each named before it, its unit after it.
    expect(power.previousElementSibling?.textContent).toBe('Power')
    expect(power.nextElementSibling?.textContent).toBe('hp')
    expect(screen.getByRole('textbox', { name: 'Mass' }).nextElementSibling?.textContent).toBe('kg')

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.car.power.hp': '300' }, { 'gps.gauges': 'false' }]))
  })

  // Latitude first, then longitude, as map apps give them; both go into gps.start together.
  it('saves the start point from its latitude and longitude', async () => {
    const backend = gpsBackend(true)
    backend.data.values['gps.start'] = '48.8738, 2.2950'
    await gps(appState(live), backend)

    const latitude = screen.getByRole('textbox', { name: 'Latitude' }) as HTMLInputElement
    const longitude = screen.getByRole('textbox', { name: 'Longitude' }) as HTMLInputElement
    expect([latitude.value, longitude.value]).toEqual(['48.8738', '2.2950'])
    // Each field says which it is, filled as well as blank.
    expect([latitude, longitude].map((field) => field.previousElementSibling?.textContent)).toEqual(['Latitude', 'Longitude'])
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

    const latitude = screen.getByRole('textbox', { name: 'Latitude' }) as HTMLInputElement
    const heading = screen.getByRole('textbox', { name: 'Heading' }) as HTMLInputElement
    expect([latitude.value, heading.value]).toEqual(['50.45010', '90'])
    rerender(<Shell state={appState({ ...live, gps: { ...car, position: [50.4502, 30.5234], heading: 95 } })} backend={backend} />)
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

    const latitude = screen.getByRole('textbox', { name: 'Latitude' }) as HTMLInputElement
    fireEvent.change(latitude, { target: { value: 'https://maps.apple.com/?ll=50.4501,30.5234&q=Kyiv' } })
    expect((screen.getByRole('textbox', { name: 'Longitude' }) as HTMLInputElement).value).toBe('30.5234')
    fireEvent.blur(latitude)

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.start': '50.4501, 30.5234', 'gps.heading': '0' }]))
  })

  // One field alone is no place: the page says what is missing and saves nothing.
  it('asks for both halves and for numbers on the globe', async () => {
    const backend = await gps()

    const latitude = screen.getByRole('textbox', { name: 'Latitude' }) as HTMLInputElement
    fireEvent.change(latitude, { target: { value: '48.87' } })
    fireEvent.blur(latitude)
    expect(screen.getByText('Enter the longitude too')).toBeTruthy()
    const longitude = screen.getByRole('textbox', { name: 'Longitude' }) as HTMLInputElement
    fireEvent.change(longitude, { target: { value: '200' } })
    fireEvent.blur(longitude)
    expect(screen.getByText('A longitude is from -180 to 180')).toBeTruthy()

    expect(backend.saved).toEqual([])
  })

  // The compass beside the heading points the way the car faces.
  it('draws the heading on a compass', async () => {
    const backend = gpsBackend(true)
    backend.data.values['gps.heading'] = '116'
    await gps(appState(live), backend)

    const compass = screen.getByRole('img', { name: 'Heading 116°' })
    expect(compass.querySelector('[data-arrow]')!.getAttribute('transform')).toBe('rotate(116)')
    // North is a quarter like the others; the arrow alone is in the accent.
    expect(compass.querySelectorAll('[data-quarter]')).toHaveLength(4)
    expect(compass.querySelectorAll('[stroke="var(--accent)"]')).toHaveLength(1)
    // The arrow, its line's width included, keeps at least a pixel clear of the quarters' marks, whichever way it turns.
    const scale = Number(compass.getAttribute('width')) / 32
    const arrow = compass.querySelector('[data-arrow]')!
    const points = arrow.getAttribute('d')!.match(/-?[\d.]+/g)!.map(Number)
    let reach = 0
    for (let i = 0; i < points.length; i += 2) reach = Math.max(reach, Math.hypot(points[i], points[i + 1]))
    reach += Number(arrow.getAttribute('stroke-width')) / 2
    const marks = Math.min(...[...compass.querySelectorAll('[data-quarter]')].map((mark) => -Number(mark.getAttribute('y2'))))
    expect((marks - reach) * scale).toBeGreaterThanOrEqual(1)
  })

  // A city puts the car on one of its busy roads, facing along it; the city it stands in is the one chosen.
  it('puts the car in a city from its presets', async () => {
    const backend = gpsBackend(true)
    backend.data.values['gps.start'] = '48.87604, 2.28792'
    backend.data.values['gps.heading'] = '116'
    await gps(appState(live), backend)

    expect(screen.getByRole('radio', { name: 'PAR' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'NYC' }).title).toBe('New York: West Side Highway, northbound')
    // Two rows, as long as the page's other groups (the class the styles size it by).
    expect(screen.getByRole('radiogroup', { name: 'City presets' }).className).toBe('seg rows cities')
    expect(rowText('City presets')).toContain('PAR')
    fireEvent.click(screen.getByRole('radio', { name: 'NYC' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.start': '40.74389, -74.00841', 'gps.heading': '7' }]))
  })

  // How the fixes go, for finding what the iPhone takes: the rate, 4 a second as it asks, and the sentences.
  it('sets the GPS rate and sentences', async () => {
    const backend = await gps()

    const fixes = screen.getByRole('radiogroup', { name: 'Fixes' })
    expect(within(fixes).getByRole('radio', { name: '4' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'Classic' }).getAttribute('aria-checked')).toBe('true')
    expect(rowText('Fixes')).toContain('a second')
    fireEvent.click(within(fixes).getByRole('radio', { name: '10' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Modern' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'gps.rate': '10' }, { 'gps.nmea': 'modern' }]))
  })

  // What the car does now, and what the iPhone takes from it.
  it('shows the car and what the iPhone asked for', async () => {
    await gps(appState({ ...live, gps: car }))

    // What the iPhone takes, in the GPS group's note beside when its switches apply.
    expect(screen.getByText('iPhone listening: GGA, RMC, PASCD; 12 updates sent · Applies at the next connection')).toBeTruthy()
    // Where it is and which way it faces are in the fields; how it goes and what drives it, in the Car group's note.
    expect(screen.getByText('48 km/h · 2300 rpm · D3 · Drive mode on · No gamepad')).toBeTruthy()
  })

  it('says when the car has no position yet and the iPhone is not listening', async () => {
    await gps(appState({ ...live, gps: { ...car, position: null, listening: false, sentences: null, sent: 0, keyboard: false } }))

    expect(screen.getByText('iPhone not listening · Applies at the next connection')).toBeTruthy()
    expect((screen.getByRole('textbox', { name: 'Latitude' }) as HTMLInputElement).value).toBe('')
    expect(screen.getByText('48 km/h · 2300 rpm · D3 · Drive mode off (F10) · No gamepad')).toBeTruthy()
  })
})

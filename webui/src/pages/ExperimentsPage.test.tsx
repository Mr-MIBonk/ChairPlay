import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { AppState } from '../api'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend, withKeys } from '../testing'

afterEach(cleanup)

const live = { phase: 'active', canShowCarPlay: true } as const

async function experiments(state: AppState = appState(live), backend = new FakeBackend()) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'Experiments' }))
  return backend
}

const button = (row: string) =>
  screen.getByText(row, { selector: 'label' }).parentElement!.querySelector('button') as HTMLButtonElement

describe('Experiments page', () => {
  // The HUD's demos play on the AltScreen, for watching its maneuvers and lanes without an iPhone.
  it('plays the HUD demos on the AltScreen', async () => {
    const backend = await experiments()

    expect(button('Maneuvers').textContent).toBe('Play')
    expect(button('Maneuvers').title).toBe("Play a made-up route on the AltScreen's HUD")
    fireEvent.click(button('Maneuvers'))
    fireEvent.click(button('Lane changes'))

    await waitFor(() => expect(backend.demos).toEqual(['maneuvers', 'lanes']))
  })

  it('stops the demo playing', async () => {
    const backend = await experiments(appState({ ...live, demo: { playing: 'lanes', available: true } }))

    expect(button('Lane changes').textContent).toBe('Stop')
    expect(button('Maneuvers').textContent).toBe('Play')
    fireEvent.click(button('Lane changes'))

    await waitFor(() => expect(backend.demos).toEqual([null]))
  })

  // The iPhone's route, song, call, and battery messages to a file in logs\, from the next start.
  it('turns the CarPlay data log on', async () => {
    const backend = await experiments()

    fireEvent.click(screen.getByRole('checkbox', { name: "Write the iPhone's messages to a file" }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'log.carplay.data': 'true' }]))
    expect(screen.getByText('Applies after restart')).toBeTruthy()
  })

  // How much the app's log in the logs folder tells, at once; Trace adds DiPlay's packet-by-packet lines.
  it('sets the log level', async () => {
    const backend = await experiments()
    const level = screen.getByRole('radiogroup', { name: 'Log level' })

    expect(within(level).getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['Errors', 'Warnings', 'Info', 'Debug', 'Trace'])
    expect(within(level).getByRole('radio', { name: 'Info' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(within(level).getByRole('radio', { name: 'Trace' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'log.level': 'trace' }]))
  })

  it('opens the logs and screenshots folders', async () => {
    const backend = await experiments()

    fireEvent.click(screen.getByRole('button', { name: 'Logs' }))
    fireEvent.click(screen.getByRole('button', { name: 'Screenshots' }))

    expect(backend.opened).toEqual(['logs', 'screenshots'])
  })

  // An experiment: the AltScreen declares its whole frame and parts of its eighths as view areas, room for a dial or two
  // or a side panel, and CarPlay moves between them; each choice draws its part of the frame.
  it('moves the AltScreen between its view areas', async () => {
    const backend = await experiments(appState({ ...live, viewAreas: { count: 6, current: 0 } }))
    const areas = screen.getByRole('radiogroup', { name: 'View area' })

    expect(within(areas).getAllByRole('radio').map((radio) => radio.textContent)).toEqual([
      'Left 5/8', 'Full screen', 'Right 5/8', 'Left 3/8', 'Center 3/8', 'Right 3/8',
    ])
    // Each picture cuts the frame at its own part's edges alone.
    const cuts = within(areas).getAllByRole('radio').map((radio) => radio.querySelectorAll('svg.area-icon line').length)
    expect(cuts).toEqual([1, 0, 1, 1, 2, 1])
    expect(within(areas).getByRole('radio', { name: 'Full screen' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(within(areas).getByRole('radio', { name: 'Center 3/8' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Declare view areas' }))

    await waitFor(() => expect(backend.viewAreas).toEqual([4]))
    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.experiment.areas': 'true' }]))
  })

  it('waits for a session with view areas to move between', async () => {
    await experiments()
    const areas = screen.getByRole('radiogroup', { name: 'View area' })

    expect(within(areas).getAllByRole('radio').every((radio) => (radio as HTMLButtonElement).disabled)).toBe(true)
    expect(areas.closest('.row')!.getAttribute('title')).toBe('Turn on the view areas, then connect again')
  })

  it('waits for the AltScreen, and says so', async () => {
    await experiments(appState({ ...live, demo: { playing: null, available: false } }))

    expect(button('Maneuvers').disabled).toBe(true)
    expect(button('Maneuvers').title).toBe('Turn on the AltScreen on its page first')
  })

  it('names the screenshot key as bound, and none while it is unbound', async () => {
    const screenshots = () => screen.getByRole('button', { name: 'Screenshots' }).title

    await experiments(withKeys(appState(live), { screenshot: 'PrintScreen' }))
    expect(screenshots()).toBe('Open the screenshots folder in File Explorer (Print Screen saves to it)')
    cleanup()

    await experiments(withKeys(appState(live), { screenshot: null }))
    expect(screenshots()).toBe('Open the screenshots folder in File Explorer')
  })
})

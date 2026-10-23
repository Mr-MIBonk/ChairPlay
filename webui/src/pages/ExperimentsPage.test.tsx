import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { AppState } from '../api'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

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

  it('waits for the AltScreen, and says so', async () => {
    await experiments(appState({ ...live, demo: { playing: null, available: false } }))

    expect(button('Maneuvers').disabled).toBe(true)
    expect(button('Maneuvers').title).toBe('Turn on the AltScreen on its page first')
  })
})

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { StartScreen } from './StartScreen'
import { appState, FakeBackend } from './testing'

afterEach(cleanup)

describe('StartScreen', () => {
  it('offers Connect when the app waits for it', () => {
    const backend = new FakeBackend()
    render(<StartScreen state={appState({ phase: 'idle' })} backend={backend} />)

    fireEvent.click(screen.getByText('Connect'))

    expect(backend.connects).toBe(1)
    expect(screen.queryByText('Reconnect now')).toBeNull()
  })

  it('offers Show CarPlay once a session is active', () => {
    const backend = new FakeBackend()
    render(<StartScreen state={appState({ phase: 'active', canShowCarPlay: true, autoshow: false })} backend={backend} />)

    fireEvent.click(screen.getByText('Show CarPlay'))

    expect(backend.shown).toEqual(['carplay'])
  })

  // In a browser CarPlay shows in ChairPlay's own window, which the page cannot bring up.
  it('offers no Show CarPlay in a browser', () => {
    render(<StartScreen state={appState({ phase: 'active', canShowCarPlay: true, embedded: false })} backend={new FakeBackend()} />)

    expect(screen.queryByText('Show CarPlay')).toBeNull()
  })

  it('shows the last error with a hint', () => {
    const state = appState({ phase: 'retrying', error: { message: 'Offline MFi directory is missing or invalid', at: '14:03:05' } })
    render(<StartScreen state={state} backend={new FakeBackend()} />)

    expect(screen.getByText(/Offline MFi directory is missing/)).toBeTruthy()
    expect(screen.getByText(/Put the MFi identity files/)).toBeTruthy()
  })

  it('gives the firewall commands while the rule is missing', () => {
    const state = appState({ setup: { ...appState().setup, firewall: 'missing', mfiPresent: false } })
    render(<StartScreen state={state} backend={new FakeBackend()} />)

    expect(screen.getByText(/netsh advfirewall/)).toBeTruthy()
    expect(screen.getByText('Not found yet.')).toBeTruthy()
  })

  it('opens the settings', () => {
    const backend = new FakeBackend()
    render(<StartScreen state={appState()} backend={backend} />)

    fireEvent.click(screen.getByText('Settings'))

    expect(backend.shown).toEqual(['settings'])
  })
})

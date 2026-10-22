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

  // A round button with CarPlay's own icon, never a text button, brings CarPlay back.
  it('offers the CarPlay button once a session is active', () => {
    const backend = new FakeBackend()
    render(<StartScreen state={appState({ phase: 'active', canShowCarPlay: true, autoshow: false })} backend={backend} />)

    fireEvent.click(screen.getByRole('button', { name: 'CarPlay' }))

    expect(backend.shown).toEqual(['carplay'])
    expect(screen.queryByText(/Show CarPlay/)).toBeNull()
  })

  it('goes back to CarPlay on Escape while a session can be shown', () => {
    const backend = new FakeBackend()
    const { unmount } = render(<StartScreen state={appState({ phase: 'active', canShowCarPlay: true })} backend={backend} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    unmount()

    render(<StartScreen state={appState({ phase: 'idle' })} backend={backend} />)
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(backend.shown).toEqual(['carplay'])
  })

  // In a browser CarPlay shows in ChairPlay's own window, which the page cannot bring up.
  it('offers no CarPlay button in a browser', () => {
    const backend = new FakeBackend()
    render(<StartScreen state={appState({ phase: 'active', canShowCarPlay: true, embedded: false })} backend={backend} />)

    expect(screen.queryByRole('button', { name: 'CarPlay' })).toBeNull()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(backend.shown).toEqual([])
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

  it('shows no app name and no intro, only status, forms and steps', () => {
    render(<StartScreen state={appState()} backend={new FakeBackend()} />)

    expect(screen.queryByText('ChairPlay')).toBeNull()
    expect(screen.queryByText(/joins for CarPlay/)).toBeNull()
    expect(screen.getByRole('heading', { name: 'Wi-Fi' })).toBeTruthy()
  })

  it('opens the settings', () => {
    const backend = new FakeBackend()
    render(<StartScreen state={appState()} backend={backend} />)

    fireEvent.click(screen.getByText('Settings'))

    expect(backend.shown).toEqual(['settings'])
  })
})

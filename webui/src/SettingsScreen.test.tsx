import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { SettingsScreen } from './SettingsScreen'
import { appState, FakeBackend } from './testing'

afterEach(cleanup)

describe('SettingsScreen', () => {
  it('saves a switch at once and says it applies now', async () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    fireEvent.click(await screen.findByLabelText('Show CarPlay automatically'))

    expect(await screen.findByText('Saved.')).toBeTruthy()
    expect(backend.saved).toEqual([{ 'ui.autoshow': 'false' }])
  })

  it('says a window size applies at the next start', async () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    fireEvent.change(await screen.findByLabelText('Main window width'), { target: { value: '1600' } })
    fireEvent.click(screen.getAllByText('Save')[1])

    expect(await screen.findByText('Saved. Applies after restart.')).toBeTruthy()
    expect(backend.saved).toEqual([{ 'window.width': '1600' }])
  })

  it('lists the changes waiting for a restart', () => {
    render(
      <SettingsScreen
        state={appState({ screen: 'settings', restartPending: ['window.height', 'window.width', 'altscreen.enabled'] })}
        backend={new FakeBackend()}
      />,
    )

    expect(screen.getByText('Applies after restart: Main window size, AltScreen.')).toBeTruthy()
  })

  it('goes back to CarPlay when there is a session, else to the start screen', () => {
    const backend = new FakeBackend()
    const { unmount } = render(<SettingsScreen state={appState({ screen: 'settings', canShowCarPlay: true })} backend={backend} />)
    fireEvent.click(screen.getByText('‹ CarPlay'))
    unmount()

    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(backend.shown).toEqual(['carplay', 'start'])
  })

  it('offers a reconnect for a Wi-Fi change', () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings', reconnectPending: ['wifi.ssid'] })} backend={backend} />)

    fireEvent.click(screen.getByText('Reconnect now'))

    expect(backend.reconnects).toBe(1)
  })
})

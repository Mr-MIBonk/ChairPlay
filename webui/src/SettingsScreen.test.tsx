import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { SettingsScreen } from './SettingsScreen'
import { appState, FakeBackend } from './testing'

afterEach(cleanup)

describe('SettingsScreen', () => {
  it('opens on Connection and shows one section at a time, picked in the sidebar', async () => {
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={new FakeBackend()} />)

    expect((await screen.findByRole('tab', { name: 'Connection' })).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('switch', { name: 'Connect automatically' })).toBeTruthy()
    expect(screen.getByRole('switch', { name: 'Show CarPlay automatically' })).toBeTruthy()
    expect(screen.queryByRole('switch', { name: 'AltScreen' })).toBeNull()
    expect(screen.queryByRole('textbox', { name: 'Main window width' })).toBeNull()

    fireEvent.click(screen.getByRole('tab', { name: 'AltScreen' }))
    expect(screen.getByRole('tab', { name: 'AltScreen' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tab', { name: 'Connection' }).getAttribute('aria-selected')).toBe('false')
    expect(screen.getByRole('switch', { name: 'AltScreen' })).toBeTruthy()
    expect(screen.getByRole('textbox', { name: 'AltScreen width' })).toBeTruthy()
    expect(screen.queryByRole('switch', { name: 'Connect automatically' })).toBeNull()

    fireEvent.click(screen.getByRole('tab', { name: 'Main window' }))
    expect(screen.getByRole('textbox', { name: 'Main window width' })).toBeTruthy()
    expect(screen.getByRole('textbox', { name: 'Main window height' })).toBeTruthy()
    expect(screen.queryByRole('switch', { name: 'AltScreen' })).toBeNull()
  })

  it('saves a switch at once and says it applies now', async () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    fireEvent.click(await screen.findByRole('switch', { name: 'Show CarPlay automatically' }))

    expect(await screen.findByText('Saved.')).toBeTruthy()
    expect(backend.saved).toEqual([{ 'ui.autoshow': 'false' }])
  })

  it('saves connecting automatically', async () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    fireEvent.click(await screen.findByRole('switch', { name: 'Connect automatically' }))

    expect(await screen.findByText('Saved. Applies after restart.')).toBeTruthy()
    expect(backend.saved).toEqual([{ 'ui.autoconnect': 'false' }])
  })

  it('saves the AltScreen switch and size from its section', async () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    fireEvent.click(await screen.findByRole('tab', { name: 'AltScreen' }))
    fireEvent.click(screen.getByRole('switch', { name: 'AltScreen' }))
    expect(await screen.findByText('Saved. Applies after restart.')).toBeTruthy()

    fireEvent.change(screen.getByRole('textbox', { name: 'AltScreen width' }), { target: { value: '1600' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'AltScreen height' }), { target: { value: '600' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(backend.saved).toEqual([{ 'altscreen.enabled': 'true' }, { 'altscreen.width': '1600', 'altscreen.height': '600' }]),
    )
  })

  // A switch says what it does when on; what follows when it is off, or a field when blank, the user can tell.
  it('explains no switch by its off side and no field by its blank one', async () => {
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={new FakeBackend()} />)

    await screen.findByLabelText('Connect automatically')
    expect(screen.queryByText(/Off:/)).toBeNull()
    expect(screen.queryByText(/Blank:/)).toBeNull()
  })

  it('says a window size applies at the next start', async () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    fireEvent.click(await screen.findByRole('tab', { name: 'Main window' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Main window width' }), { target: { value: '1600' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

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
    // CarPlay's round icon button, not a text one.
    expect(screen.queryByText(/‹ CarPlay/)).toBeNull()
    expect(screen.queryByText(/press Show CarPlay/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'CarPlay' }))
    unmount()

    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)
    expect(screen.queryByRole('button', { name: 'CarPlay' })).toBeNull()
    expect(screen.getByText('‹ Back')).toBeTruthy()
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(backend.shown).toEqual(['carplay', 'start'])
  })

  it('shows a window button pressed while its window is shown and hides the window on a click', () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings' })} backend={backend} />)

    const altscreen = screen.getByRole('button', { name: 'AltScreen' })
    const controls = screen.getByRole('button', { name: 'Controls' })
    expect(altscreen.getAttribute('aria-pressed')).toBe('true')
    expect(controls.getAttribute('aria-pressed')).toBe('true')

    fireEvent.click(controls)
    fireEvent.click(altscreen)

    expect(backend.windows).toEqual([
      { name: 'controls', visible: false },
      { name: 'altscreen', visible: false },
    ])
  })

  it('shows a hidden window on a click and waits for the app to say it is shown', () => {
    const backend = new FakeBackend()
    const windows = { altscreen: { available: true, visible: false }, controls: { available: true, visible: false } }
    const { rerender } = render(<SettingsScreen state={appState({ screen: 'settings', windows })} backend={backend} />)

    const altscreen = screen.getByRole('button', { name: 'AltScreen' })
    expect(altscreen.getAttribute('aria-pressed')).toBe('false')
    expect((altscreen as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(altscreen)
    fireEvent.click(screen.getByRole('button', { name: 'Controls' }))

    expect(backend.windows).toEqual([
      { name: 'altscreen', visible: true },
      { name: 'controls', visible: true },
    ])
    expect(altscreen.getAttribute('aria-pressed')).toBe('false')

    rerender(
      <SettingsScreen
        state={appState({ screen: 'settings', windows: { ...windows, altscreen: { available: true, visible: true } } })}
        backend={backend}
      />,
    )
    expect(screen.getByRole('button', { name: 'AltScreen' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('disables the button of a window that is not available', () => {
    const backend = new FakeBackend()
    const windows = { altscreen: { available: false, visible: false }, controls: { available: true, visible: true } }
    render(<SettingsScreen state={appState({ screen: 'settings', windows })} backend={backend} />)

    const altscreen = screen.getByRole('button', { name: 'AltScreen' }) as HTMLButtonElement
    expect(altscreen.disabled).toBe(true)
    expect(altscreen.getAttribute('aria-pressed')).toBe('false')
    expect((screen.getByRole('button', { name: 'Controls' }) as HTMLButtonElement).disabled).toBe(false)

    fireEvent.click(altscreen)
    expect(backend.windows).toEqual([])
  })

  it('offers a reconnect for a Wi-Fi change', () => {
    const backend = new FakeBackend()
    render(<SettingsScreen state={appState({ screen: 'settings', reconnectPending: ['wifi.ssid'] })} backend={backend} />)

    fireEvent.click(screen.getByText('Reconnect now'))

    expect(backend.reconnects).toBe(1)
  })
})

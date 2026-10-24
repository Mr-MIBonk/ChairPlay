import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { appState, FakeBackend, withKeys } from '../testing'
import { sectionOf } from './sections'
import { Shell } from './Shell'

afterEach(cleanup)

const button = (name: string) => screen.getByRole('button', { name }) as HTMLButtonElement
const live = { phase: 'active', canShowCarPlay: true } as const

describe('Waiting screen', () => {
  // Connecting on its own, the app shows black with where the connection stands, not the settings.
  it('shows where the connection stands instead of the settings while the app connects on its own', () => {
    render(<Shell state={appState({ phase: 'calling', waiting: true })} backend={new FakeBackend()} />)

    expect(screen.getByRole('status').textContent).toContain('Waiting for the iPhone')
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Connect' })).toBeNull()
  })

  it('opens the settings when asked', () => {
    const backend = new FakeBackend()
    render(<Shell state={appState({ phase: 'starting', waiting: true })} backend={backend} />)

    fireEvent.click(button('Settings'))

    expect(backend.shown).toEqual(['settings'])
  })
})

describe('Shell toolbar', () => {
  // A narrow window shows the buttons' icons alone (CSS); each keeps its name for the screen reader and the tests.
  it('names each window button whether or not its label shows', () => {
    render(<Shell state={appState(live)} backend={new FakeBackend()} />)

    for (const name of ['CarPlay', 'AltScreen', 'Controls']) {
      expect(button(name).getAttribute('aria-label')).toBe(name)
      expect(button(name).querySelector('.label')?.textContent).toBe(name)
    }
  })

  it('before the Wi-Fi is set, Connect and the windows are off and say why', () => {
    render(<Shell state={appState({ phase: 'setup' })} backend={new FakeBackend()} />)

    expect(button('Connect').disabled).toBe(true)
    expect(button('Connect').title).toBe('Enter the Wi-Fi network first')
    for (const name of ['CarPlay', 'AltScreen', 'Controls']) {
      expect(button(name).disabled).toBe(true)
      expect(button(name).title).toBe('Connect the iPhone first')
    }
    expect(screen.queryByRole('button', { name: 'Reconnect' })).toBeNull()
  })

  it('connects from idle', () => {
    const backend = new FakeBackend()
    render(<Shell state={appState({ phase: 'idle' })} backend={backend} />)

    fireEvent.click(button('Connect'))

    expect(backend.connects).toBe(1)
    expect(screen.queryByRole('button', { name: 'Disconnect' })).toBeNull()
  })

  // Reconnect and Disconnect take Connect's place from the bring-up's start, so a stuck one can be ended.
  it('reconnects or disconnects while connecting and connected', () => {
    for (const phase of ['starting', 'calling', 'joining', 'retrying', 'active'] as const) {
      const backend = new FakeBackend()
      render(<Shell state={appState({ phase })} backend={backend} />)

      expect(screen.queryByRole('button', { name: 'Connect' })).toBeNull()
      fireEvent.click(button('Reconnect'))
      fireEvent.click(button('Disconnect'))

      expect([backend.reconnects, backend.disconnects]).toEqual([1, 1])
      cleanup()
    }
  })

  it('shows CarPlay with a session, and not without one', () => {
    const backend = new FakeBackend()
    render(<Shell state={appState(live)} backend={backend} />)

    expect(button('CarPlay').title).toBe('Show CarPlay (Esc)')
    fireEvent.click(button('CarPlay'))
    expect(backend.shown).toEqual(['carplay'])
    cleanup()

    render(<Shell state={appState({ phase: 'calling' })} backend={backend} />)
    expect(button('CarPlay').disabled).toBe(true)
  })

  // In a browser CarPlay shows in ChairPlay's own window, which the page cannot bring up.
  it('cannot show CarPlay from a browser', () => {
    render(<Shell state={appState({ ...live, embedded: false })} backend={new FakeBackend()} />)

    expect(button('CarPlay').disabled).toBe(true)
    expect(button('CarPlay').title).toBe("CarPlay shows in ChairPlay's window")
  })

  it('shows and hides the windows, pressed while shown', () => {
    const backend = new FakeBackend()
    const windows = { altscreen: { available: true, visible: true }, controls: { available: true, visible: false } }
    render(<Shell state={appState({ ...live, windows })} backend={backend} />)

    expect(button('AltScreen').getAttribute('aria-pressed')).toBe('true')
    expect(button('Controls').getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(button('AltScreen'))
    fireEvent.click(button('Controls'))

    expect(backend.windows).toEqual([{ name: 'altscreen', visible: false }, { name: 'controls', visible: true }])
  })

  it('keeps the AltScreen button off while the AltScreen is turned off', () => {
    const windows = { altscreen: { available: false, visible: false }, controls: { available: true, visible: true } }
    render(<Shell state={appState({ ...live, windows })} backend={new FakeBackend()} />)

    expect(button('AltScreen').disabled).toBe(true)
    expect(button('AltScreen').title).toBe('Turn on the AltScreen on its page first')
  })

  it('says where the connection stands', () => {
    render(<Shell state={appState({ phase: 'idle' })} backend={new FakeBackend()} />)

    expect(screen.getByText('Not connected').closest('[title]')?.getAttribute('title')).toBe(
      'Press Connect when the iPhone is nearby.',
    )
  })

  it('names the pages whose changes wait for a restart', () => {
    const state = appState({ ...live, restartPending: ['altscreen.enabled', 'display.width', 'display.height'] })
    render(<Shell state={state} backend={new FakeBackend()} />)

    expect(screen.getByText('Restart needed').closest('[title]')?.getAttribute('title')).toBe(
      'Restart ChairPlay to apply: CarPlay, AltScreen',
    )
    expect(screen.getByRole('tab', { name: /CarPlay/ }).querySelector('[title="Applies after restart"]')).toBeTruthy()
    expect(screen.getByRole('tab', { name: /Audio/ }).querySelector('[title="Applies after restart"]')).toBeNull()
  })

  it('puts the settings shown on Experiments on Experiments, whatever their key starts with', () => {
    const state = appState({ ...live, restartPending: ['log.carplay.data'] })
    render(<Shell state={state} backend={new FakeBackend()} />)

    expect(screen.getByText('Restart needed').closest('[title]')?.getAttribute('title')).toBe(
      'Restart ChairPlay to apply: Experiments',
    )
    expect(sectionOf('altscreen.card.area')).toBe('altscreen')
    expect(sectionOf('log.level')).toBe('experiments')
  })

  it('names the Reconnect key as bound, and none while it is unbound', () => {
    render(<Shell state={withKeys(appState(live), { reconnect: 'F5' })} backend={new FakeBackend()} />)
    expect(button('Reconnect').title).toBe('Reconnect: end the session and connect again (F5)')
    cleanup()

    render(<Shell state={withKeys(appState(live), { reconnect: null })} backend={new FakeBackend()} />)
    expect(button('Reconnect').title).toBe('Reconnect: end the session and connect again')
  })

  it('has no Restart needed while nothing waits for one', () => {
    render(<Shell state={appState(live)} backend={new FakeBackend()} />)

    expect(screen.queryByText('Restart needed')).toBeNull()
  })
})

describe('Shell fading', () => {
  // The window hides the page a fade's time after CarPlay is to show; the page fades to black meanwhile.
  it('fades away as CarPlay takes the window, and back as it returns', () => {
    const { container, rerender } = render(<Shell state={appState({ ...live, screen: 'carplay' })} backend={new FakeBackend()} />)
    expect(container.querySelector('.shell')?.classList.contains('away')).toBe(true)

    rerender(<Shell state={appState({ ...live, screen: 'settings' })} backend={new FakeBackend()} />)

    expect(container.querySelector('.shell')?.classList.contains('away')).toBe(false)
  })

  // In a browser nothing covers the page: it stays.
  it('stays in a browser', () => {
    const { container } = render(<Shell state={appState({ ...live, screen: 'carplay', embedded: false })} backend={new FakeBackend()} />)

    expect(container.querySelector('.shell')?.classList.contains('away')).toBe(false)
  })
})

describe('Shell', () => {
  // A narrow window shows the sections' icons alone (CSS): each keeps its name, and says it when hovered.
  it('names each section whether or not its label shows', () => {
    render(<Shell state={appState({ ...live, restartPending: ['display.width'] })} backend={new FakeBackend()} />)

    for (const name of ['Connection', 'CarPlay', 'AltScreen', 'Keyboard', 'Audio', 'Experiments', 'About']) {
      const tab = screen.getByRole('tab', { name })
      expect(tab.title).toBe(name)
      expect(tab.querySelector('.label')?.textContent).toBe(name)
    }
  })

  it('opens on Connection and switches pages from the section list', () => {
    render(<Shell state={appState(live)} backend={new FakeBackend()} />)

    const names = screen.getAllByRole('tab').map((tab) => tab.textContent)
    expect(names).toEqual(['Connection', 'CarPlay', 'AltScreen', 'Keyboard', 'Audio', 'Experiments', 'About'])
    expect(screen.getByRole('tab', { name: 'Connection' }).getAttribute('aria-selected')).toBe('true')

    fireEvent.click(screen.getByRole('tab', { name: 'Audio' }))

    expect(screen.getByRole('tab', { name: 'Audio' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tab', { name: 'Connection' }).getAttribute('aria-selected')).toBe('false')
  })

  it('shows CarPlay on Escape when it can', () => {
    const backend = new FakeBackend()
    render(<Shell state={appState(live)} backend={backend} />)

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(backend.shown).toEqual(['carplay'])
  })

  // The settings have the keyboard while they show: the app's own keys (F11, F12 and such) still reach it, as pressed
  // on the window, and the app keeps the arrows, Enter and the like to the page. What is typed in a field stays there.
  it("hands the app's keys on, not what is typed in a field", async () => {
    const backend = new FakeBackend()
    render(<Shell state={appState(live)} backend={backend} />)

    fireEvent.keyDown(window, { code: 'F11', key: 'F11' })
    fireEvent.keyDown(window, { code: 'F11', key: 'F11', repeat: true })
    fireEvent.keyUp(window, { code: 'F11', key: 'F11' })
    fireEvent.keyDown(window, { code: 'Equal', key: '=', shiftKey: true })
    fireEvent.keyDown(window, { code: 'ShiftLeft', key: 'Shift', shiftKey: true })
    const field = await screen.findByRole('textbox', { name: 'Network' })
    fireEvent.keyDown(field, { code: 'Minus', key: '-' })
    fireEvent.keyDown(field, { code: 'F12', key: 'F12' })

    expect(backend.keys).toEqual([
      'F11 press settings', 'F11 repeat settings', 'F11 release settings', 'Shift+Equal press settings', 'F12 press settings',
    ])
  })

  it('leaves Escape alone without a session, or when a page used it', () => {
    const backend = new FakeBackend()
    render(<Shell state={appState({ phase: 'calling' })} backend={backend} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    cleanup()

    render(<Shell state={appState(live)} backend={backend} />)
    const used = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })
    used.preventDefault()
    window.dispatchEvent(used)

    expect(backend.shown).toEqual([])
  })
})

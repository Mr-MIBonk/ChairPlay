import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { ControlsScreen } from './ControlsScreen'
import { appState, FakeBackend, withKeys } from './testing'

afterEach(cleanup)

const live = { canShowCarPlay: true }

describe('ControlsScreen', () => {
  // A button holds its control while it is held, as a key does, so CarPlay can tell a long press.
  it('presses a button on the pointer going down and releases it on the pointer going up', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState(live)} backend={backend} />)

    const back = screen.getByRole('button', { name: 'Back' })
    fireEvent.pointerDown(back)
    fireEvent.pointerUp(back)
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Next track' }))
    fireEvent.pointerCancel(screen.getByRole('button', { name: 'Next track' }))

    expect(backend.controls).toEqual(['back down', 'back up', 'media.next down', 'media.next up'])
  })

  it('has every button of the car, the volume and the knob', () => {
    render(<ControlsScreen state={appState(live)} backend={new FakeBackend()} />)

    for (const name of ['Back', 'Home', 'Siri', 'Previous track', 'Play or pause', 'Next track', 'Volume down', 'Mute',
      'Volume up', 'Answer', 'End', 'Select', 'Tilt up', 'Tilt down', 'Tilt left', 'Tilt right', 'Turn back',
      'Turn forward', 'Zoom in', 'Zoom out']) {
      expect(screen.getByRole('button', { name })).toBeTruthy()
    }
  })

  // The wheel over the knob turns it, a step a notch, down moving the focus forward as the main window's wheel does.
  it('turns the knob with the wheel', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState(live)} backend={backend} />)

    const knob = screen.getByRole('group', { name: 'Knob' })
    fireEvent.wheel(knob, { deltaY: 100 })
    fireEvent.wheel(knob, { deltaY: -100 })

    expect(backend.controls).toEqual(['knob.next down', 'knob.next up', 'knob.previous down', 'knob.previous up'])
  })

  // The AltScreen's modes pick one directly, rather than stepping through them as F11 does.
  it('shows the AltScreen mode that is on and picks another', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState(live)} backend={backend} />)

    expect(screen.getByRole('radio', { name: 'Map' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'HUD' }).getAttribute('aria-checked')).toBe('false')
    fireEvent.click(screen.getByRole('radio', { name: 'HUD' }))

    expect(backend.altscreens).toEqual(['hud'])
  })

  it('disables the AltScreen buttons while there is no AltScreen to work', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState({ canShowCarPlay: false })} backend={backend} />)

    const zoom = screen.getByRole('button', { name: 'Zoom in' }) as HTMLButtonElement
    expect(zoom.disabled).toBe(true)
    fireEvent.pointerDown(zoom)
    fireEvent.click(screen.getByRole('radio', { name: 'HUD' }))
    expect((screen.getByRole('radio', { name: 'HUD' }) as HTMLButtonElement).disabled).toBe(true)

    expect(backend.controls).toEqual([])
    expect(backend.altscreens).toEqual([])
  })

  // A hidden AltScreen shows nothing to zoom or switch: its buttons wait for the window to be on screen.
  it('disables the AltScreen buttons while its window is hidden', () => {
    const windows = { altscreen: { available: true, visible: false }, controls: { available: true, visible: true } }
    render(<ControlsScreen state={appState({ ...live, windows })} backend={new FakeBackend()} />)

    expect((screen.getByRole('button', { name: 'Zoom in' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('radio', { name: 'HUD' }) as HTMLButtonElement).disabled).toBe(true)
  })

  // A disabled choice shows nothing chosen: the mode the AltScreen last had is not one it shows now.
  it('marks no AltScreen mode while there is no AltScreen to work', () => {
    render(<ControlsScreen state={appState({ canShowCarPlay: false })} backend={new FakeBackend()} />)

    for (const name of ['HUD', 'Map', 'Map + card', 'Turn card']) {
      expect(screen.getByRole('radio', { name }).getAttribute('aria-checked')).toBe('false')
    }
  })

  // Windows shows access keys while Alt is held; the buttons show theirs the same way.
  it('shows each button its key while Alt is held', () => {
    const state = appState(live)
    const { rerender } = render(<ControlsScreen state={state} backend={new FakeBackend()} />)
    expect(screen.queryByText('Esc')).toBeNull()

    rerender(<ControlsScreen state={{ ...state, controls: { ...state.controls!, altHeld: true } }} backend={new FakeBackend()} />)

    // As Windows names the keys: Escape is Esc, Equal is =.
    expect(screen.getByText('Esc')).toBeTruthy()
    expect(screen.getByText('=')).toBeTruthy()
    expect(screen.getByText('F11')).toBeTruthy()
    expect(screen.getByText('Ctrl+M')).toBeTruthy()
  })

  // Every button shows its key in its top-right corner, over the button; its icon and label stay as they are.
  it('puts every key in its button\'s corner, the labels staying', () => {
    render(<ControlsScreen state={appState({ ...live, controlsKeys: true })} backend={new FakeBackend()} />)

    const back = screen.getByRole('button', { name: 'Back' })
    expect(within(back).getByText('Esc').className).toContain('kb')
    expect(within(back).getByText('Back')).toBeTruthy()
    expect(within(screen.getByRole('button', { name: 'Mute' })).getByText('Ctrl+M').className).toContain('kb')
  })

  // A click on a button gives the page the keyboard; every key of the app still works, as pressed on the window.
  it('hands every key on to the app', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState(live)} backend={backend} />)

    fireEvent.keyDown(window, { code: 'ArrowUp', key: 'ArrowUp' })
    fireEvent.keyUp(window, { code: 'ArrowUp', key: 'ArrowUp' })

    expect(backend.keys).toEqual(['Up press everything', 'Up release everything'])
  })

  // Tab turns the knob; it must not also move a focus ring over the buttons, nor Enter or Space press one.
  it('keeps the keys it hands on from moving about the page', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState(live)} backend={backend} />)

    expect(fireEvent.keyDown(window, { code: 'Tab', key: 'Tab' })).toBe(false)
    expect(fireEvent.keyDown(window, { code: 'Enter', key: 'Enter' })).toBe(false)
    expect(backend.keys).toEqual(['Tab press everything', 'Enter press everything'])
  })

  // The Keyboard page's Show keys on the buttons.
  it('shows each button its key always when the settings say so', () => {
    render(<ControlsScreen state={appState({ ...live, controlsKeys: true })} backend={new FakeBackend()} />)

    expect(screen.getByText('Esc')).toBeTruthy()
  })

  it('shows the keys while Alt is held over the page itself', () => {
    render(<ControlsScreen state={appState(live)} backend={new FakeBackend()} />)

    fireEvent.keyDown(window, { key: 'Alt' })
    expect(screen.getByText('Esc')).toBeTruthy()
    fireEvent.keyUp(window, { key: 'Alt' })
    expect(screen.queryByText('Esc')).toBeNull()
  })

  // The iPhone says whether it plays: the button shows what a press does, pause while playing, play otherwise.
  it('shows pause while the iPhone plays and play otherwise', () => {
    const state = appState(live)
    const glyph = () => screen.getByRole('button', { name: 'Play or pause' }).querySelector('svg')?.getAttribute('data-glyph')
    const { rerender } = render(<ControlsScreen state={state} backend={new FakeBackend()} />)
    expect(glyph()).toBe('play')

    rerender(<ControlsScreen state={{ ...state, controls: { ...state.controls!, playing: true } }} backend={new FakeBackend()} />)

    expect(glyph()).toBe('pause')
  })

  it('shows the mute button pressed while ChairPlay is muted', () => {
    const state = appState(live)
    const { rerender } = render(<ControlsScreen state={state} backend={new FakeBackend()} />)
    expect(screen.getByRole('button', { name: 'Mute' }).getAttribute('aria-pressed')).toBe('false')

    rerender(<ControlsScreen state={{ ...state, controls: { ...state.controls!, volume: { level: 60, muted: true } } }} backend={new FakeBackend()} />)

    expect(screen.getByRole('button', { name: 'Mute' }).getAttribute('aria-pressed')).toBe('true')
  })

  // The views' key is the one bound on the Keyboard page, not F11 whatever it is.
  it('names the key that steps through the views as bound', () => {
    render(<ControlsScreen state={withKeys(appState(live), { 'altscreen.content': 'F10' })} backend={new FakeBackend()} />)

    expect(screen.getByRole('radiogroup', { name: 'AltScreen content' }).title).toBe('F10 steps through them')
  })
})

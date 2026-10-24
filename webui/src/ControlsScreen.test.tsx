import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { ControlsScreen } from './ControlsScreen'
import { appState } from './testing'
import { FakeBackend } from './fakeBackend'

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

  // The wheel over the knob turns it, a step a notch, down moving the focus forward as the main window's wheel does.
  it('turns the knob with the wheel', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState(live)} backend={backend} />)

    const knob = screen.getByRole('group', { name: 'Knob' })
    fireEvent.wheel(knob, { deltaY: 100 })
    fireEvent.wheel(knob, { deltaY: -100 })

    expect(backend.controls).toEqual(['knob.next down', 'knob.next up', 'knob.previous down', 'knob.previous up'])
  })

  it('disables the zoom while there is no AltScreen to work', () => {
    const backend = new FakeBackend()
    render(<ControlsScreen state={appState({ canShowCarPlay: false })} backend={backend} />)

    const zoom = screen.getByRole('button', { name: 'Zoom in' }) as HTMLButtonElement
    expect(zoom.disabled).toBe(true)
    expect((screen.getByRole('button', { name: 'Zoom out' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.pointerDown(zoom)

    expect(backend.controls).toEqual([])
  })

  // A hidden AltScreen shows nothing to zoom: the zoom waits for the window to be on screen.
  it('disables the zoom while the AltScreen is hidden', () => {
    const windows = { altscreen: { available: true, visible: false }, controls: { available: true, visible: true } }
    render(<ControlsScreen state={appState({ ...live, windows })} backend={new FakeBackend()} />)

    expect((screen.getByRole('button', { name: 'Zoom in' }) as HTMLButtonElement).disabled).toBe(true)
  })

  // Windows shows access keys while Alt is held; the buttons show theirs the same way.
  it('shows each button its key while Alt is held', () => {
    const state = appState(live)
    const { rerender } = render(<ControlsScreen state={state} backend={new FakeBackend()} />)
    expect(screen.queryByText('Esc')).toBeNull()

    rerender(
      <ControlsScreen
        state={{ ...state, controls: { ...state.controls!, altHeld: true } }}
        backend={new FakeBackend()}
      />,
    )

    expect(screen.getByText('Esc')).toBeTruthy()
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

  it('shows the mute button pressed while ChairPlay is muted', () => {
    const state = appState(live)
    const { rerender } = render(<ControlsScreen state={state} backend={new FakeBackend()} />)
    expect(screen.getByRole('button', { name: 'Mute' }).getAttribute('aria-pressed')).toBe('false')

    rerender(
      <ControlsScreen
        state={{ ...state, controls: { ...state.controls!, volume: { level: 60, muted: true } } }}
        backend={new FakeBackend()}
      />,
    )

    expect(screen.getByRole('button', { name: 'Mute' }).getAttribute('aria-pressed')).toBe('true')
  })
})

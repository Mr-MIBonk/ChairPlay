import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

const live = { phase: 'active', canShowCarPlay: true } as const

/** The shell on the Keyboard page, once the bindings are in. */
async function keyboard(backend = new FakeBackend()) {
  render(<Shell state={appState(live)} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'Keyboard' }))
  await screen.findByText('Turn forward', { selector: 'label' })
  return backend
}

/** An action's row, by the label on it. */
const row = (label: string) => screen.getByText(label, { selector: 'label' }).closest('.row') as HTMLElement
const keycap = (label: string, key: string) => within(row(label)).getByRole('button', { name: key })
const press = (init: KeyboardEventInit) => fireEvent.keyDown(window, init)
const waiting = () => screen.queryByText('Press a key · Esc cancels')

describe('Keyboard page, the list', () => {
  it('shows each action with its keys as Windows names them, the fixed ones locked', async () => {
    await keyboard()

    expect(within(row('Back')).getByTitle('Esc always goes back').textContent).toBe('Esc')
    expect(within(row('Back')).queryByRole('button', { name: 'Esc' })).toBeNull()
    expect(keycap('Back', 'Backspace')).toBeTruthy()
    expect(within(row('Select')).getByTitle('Enter always selects').textContent).toBe('Enter')
    expect(keycap('Select', 'Num Enter')).toBeTruthy()
    for (const key of ['=', 'Shift+=', 'Num +']) expect(keycap('Zoom in', key)).toBeTruthy()
  })

  it('offers + only below three keys', async () => {
    await keyboard()

    expect(within(row('Home')).getByRole('button', { name: 'Add a key' })).toBeTruthy()
    expect(within(row('Zoom in')).queryByRole('button', { name: 'Add a key' })).toBeNull()
  })

  it('says Off for an action with no keys', async () => {
    const backend = new FakeBackend()
    backend.bindings!.actions['key.siri'].keys = []
    await keyboard(backend)

    expect(within(row('Siri')).getByText('Off')).toBeTruthy()
  })
})

describe('Keyboard page, catching a key', () => {
  it('replaces a key with the next one pressed', async () => {
    const backend = await keyboard()

    fireEvent.click(keycap('Home', 'Home'))
    expect(waiting()).toBeTruthy()
    press({ code: 'F6', key: 'F6' })

    await waitFor(() => expect(backend.controlsSaved).toEqual([{ 'key.home': 'F6' }]))
    expect(await within(row('Home')).findByRole('button', { name: 'F6' })).toBeTruthy()
    expect(waiting()).toBeNull()
  })

  // event.code is the key's place, so a letter is the same key in any layout; Ctrl, Alt, and Shift go in front.
  it('adds a key at the end, with the modifiers held, named by its place on the keyboard', async () => {
    const backend = await keyboard()

    fireEvent.click(within(row('Siri')).getByRole('button', { name: 'Add a key' }))
    press({ code: 'KeyS', key: 'ы', ctrlKey: true, shiftKey: true })

    await waitFor(() => expect(backend.controlsSaved).toEqual([{ 'key.siri': 'S, Ctrl+Shift+S' }]))
  })

  it('names the number pad, the arrows, and the punctuation as the file does', async () => {
    const backend = await keyboard()

    for (const code of ['NumpadEnter', 'ArrowLeft', 'BracketLeft']) {
      fireEvent.click(within(row('Home')).getAllByRole('button')[0])
      press({ code })
      await waitFor(() => expect(waiting()).toBeNull())
    }

    expect(backend.controlsSaved.map((changes) => changes['key.home'])).toEqual(['KP_Enter', 'Left', 'LeftBracket'])
  })

  it('cancels the wait on Esc, which goes no further', async () => {
    const backend = await keyboard()

    fireEvent.click(keycap('Home', 'Home'))
    press({ code: 'Escape', key: 'Escape' })

    expect(waiting()).toBeNull()
    expect(backend.controlsSaved).toEqual([])
    expect(backend.shown).toEqual([])
  })

  // Enter always selects: it is never taken for another action, and the wait goes on.
  it('takes no Enter for another action', async () => {
    const backend = await keyboard()

    fireEvent.click(within(row('Home')).getByRole('button', { name: 'Add a key' }))
    press({ code: 'Enter', key: 'Enter' })

    expect(waiting()).toBeTruthy()
    expect(screen.getByText('Enter always selects')).toBeTruthy()
    expect(backend.controlsSaved).toEqual([])
  })

  it('waits on through a modifier pressed alone', async () => {
    const backend = await keyboard()

    fireEvent.click(keycap('Home', 'Home'))
    press({ code: 'ShiftLeft', key: 'Shift', shiftKey: true })
    press({ code: 'ControlRight', key: 'Control', ctrlKey: true })

    expect(waiting()).toBeTruthy()
    expect(backend.controlsSaved).toEqual([])
  })

  it("says when a key can't be used, and waits on", async () => {
    const backend = await keyboard()

    fireEvent.click(keycap('Home', 'Home'))
    press({ code: 'MediaPlayPause' })

    expect(screen.getByText("This key can't be used")).toBeTruthy()
    expect(waiting()).toBeTruthy()
    expect(backend.controlsSaved).toEqual([])
  })

  // A key bound elsewhere moves at once: the app takes it from the other action, and the list shows both.
  it('shows a key moved from another action there too', async () => {
    const backend = await keyboard()

    fireEvent.click(keycap('Home', 'Home'))
    press({ code: 'F7' })

    expect(await within(row('Previous track')).findByText('Off')).toBeTruthy()
    expect(backend.controlsSaved).toEqual([{ 'key.home': 'F7' }])
  })

  it('shows the reason for a key the app refuses', async () => {
    const backend = new FakeBackend()
    backend.nextControlsOutcome = { ok: false, errors: { 'key.home': 'key.home = #400: "#400" is not a key' } }
    await keyboard(backend)

    fireEvent.click(keycap('Home', 'Home'))
    press({ code: 'F6' })

    expect(await within(row('Home')).findByText('key.home = #400: "#400" is not a key')).toBeTruthy()
  })
})

describe('Keyboard page, removing and resetting', () => {
  it('removes a key on a right-click, but never a fixed one', async () => {
    const backend = await keyboard()

    fireEvent.contextMenu(within(row('Back')).getByTitle('Esc always goes back'))
    fireEvent.contextMenu(keycap('Back', 'Backspace'))

    await waitFor(() => expect(backend.controlsSaved).toEqual([{ 'key.back': 'Escape' }]))
  })

  it('puts back the default keys of a row that differs from them', async () => {
    const backend = new FakeBackend()
    backend.bindings!.actions['key.home'].keys = ['F6']
    await keyboard(backend)
    expect(within(row('Siri')).queryByRole('button', { name: 'Reset' })).toBeNull()

    fireEvent.click(within(row('Home')).getByRole('button', { name: 'Reset' }))

    await waitFor(() => expect(backend.controlsSaved).toEqual([{ 'key.home': 'Home' }]))
  })
})

describe('Keyboard page, the mouse and held keys', () => {
  it('saves the wheel, its direction, and its step', async () => {
    const backend = await keyboard()

    fireEvent.click(within(screen.getByRole('radiogroup', { name: 'Wheel' })).getByRole('radio', { name: 'Off' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Invert' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Step' }), { target: { value: '2' } })
    fireEvent.blur(screen.getByRole('textbox', { name: 'Step' }))

    await waitFor(() =>
      expect(backend.controlsSaved).toEqual([
        { 'wheel.action': 'none' },
        { 'wheel.invert': 'true' },
        { 'wheel.notches.per.step': '2' },
      ]),
    )
  })

  it('saves how often a held key repeats, and the zoom its own', async () => {
    const backend = await keyboard()

    fireEvent.change(screen.getByRole('textbox', { name: 'Repeat every' }), { target: { value: '5' } })
    fireEvent.blur(screen.getByRole('textbox', { name: 'Repeat every' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Zoom repeat every' }), { target: { value: '0' } })
    fireEvent.blur(screen.getByRole('textbox', { name: 'Zoom repeat every' }))

    await waitFor(() => expect(backend.controlsSaved).toEqual([{ 'key.repeat.every': '5' }, { 'key.zoom.repeat.every': '0' }]))
  })

  it('saves showing the keys on the controls window', async () => {
    const backend = await keyboard()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Show keys on the buttons' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'ui.controls.keys': 'true' }]))
  })
})

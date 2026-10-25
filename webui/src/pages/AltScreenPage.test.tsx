import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState } from '../testing'
import { FakeBackend } from '../fakeBackend'

afterEach(cleanup)

/** The shell on the AltScreen page, once the settings are in. */
async function altscreen(backend = new FakeBackend(), state = appState({ phase: 'active', canShowCarPlay: true })) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'AltScreen' }))
  return backend
}

/** A live session with the AltScreen on Map + card, the one view with a turn card to place. */
function onMapCard() {
  const state = appState({ phase: 'active', canShowCarPlay: true })
  return { ...state, controls: { ...state.controls!, altscreen: 'map-card' as const } }
}

/** The row labelled [label], for its own fields where two rows have fields of the same names. */
const row = (label: string) => screen.getByText(label, { selector: 'label' }).closest('.row') as HTMLElement
const inRow = (label: string, name: string) => within(row(label)).getByRole('textbox', { name }) as HTMLInputElement

const textbox = (name: string) => screen.getByRole('textbox', { name }) as HTMLInputElement
const radio = (group: string, name: string) =>
  within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
const chosen = (group: string) =>
  within(screen.getByRole('radiogroup', { name: group }))
    .getAllByRole('radio')
    .filter((button) => button.getAttribute('aria-checked') === 'true')
    .map((button) => button.textContent)

describe('AltScreen page', () => {
  it('turns the AltScreen on', async () => {
    const backend = await altscreen()
    const enabled = screen.getByRole('checkbox', { name: 'Enabled' }) as HTMLInputElement
    expect(enabled.checked).toBe(false)

    fireEvent.click(enabled)

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.enabled': 'true' }]))
  })

  // Off, the AltScreen has nothing to set: everything but Enabled waits for it.
  it('holds every other setting while the AltScreen is off', async () => {
    await altscreen()
    const held = screen.getByRole('radiogroup', { name: 'Size' }).closest('[inert]') as HTMLElement

    expect(held).not.toBeNull()
    expect(held.contains(screen.getByRole('radiogroup', { name: 'Area' }))).toBe(true)
    expect(held.contains(screen.getByRole('checkbox', { name: 'Enabled' }))).toBe(false)
    cleanup()

    const backend = new FakeBackend()
    backend.data = { ...backend.data, values: { ...backend.data.values, 'altscreen.enabled': 'true' } }
    await altscreen(backend)
    expect(document.querySelector('[inert]')).toBeNull()
  })

  it('saves a preset as both numbers', async () => {
    const backend = await altscreen()
    expect(chosen('Size')).toEqual(['1440×540'])

    fireEvent.click(radio('Size', '1920×720'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.width': '1920', 'altscreen.height': '720' }]))
  })

  it('marks a refused size, with no preset chosen', async () => {
    const backend = new FakeBackend()
    backend.nextOutcome = { ok: false, errors: { 'altscreen.width': 'Enter an even number of pixels' } }
    await altscreen(backend)

    fireEvent.change(textbox('Width'), { target: { value: '1441' } })
    fireEvent.keyDown(textbox('Width'), { key: 'Enter' })

    await waitFor(() => expect(textbox('Width').getAttribute('aria-invalid')).toBe('true'))
    expect(chosen('Size')).toEqual([])
  })

  // The AltScreen's window dragged to another size: Actual size brings it back to one screen pixel per picture pixel.
  it('offers Actual size while the AltScreen window differs from its picture', async () => {
    const altscreenWindow = {
      width: 1200,
      height: 450,
      pictureWidth: 1440,
      pictureHeight: 540,
      minWidth: 0,
      minHeight: 0,
    }
    const backend = await altscreen(
      new FakeBackend(),
      appState({ phase: 'active', canShowCarPlay: true, altscreenWindow }),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Actual size' }))

    expect(backend.actualSized).toEqual(['altscreen'])
  })

  // The saved insets drawn on the AltScreen for a moment: a dotted green frame round what stays clear.
  it('shows the safe area on the AltScreen window, which it needs', async () => {
    const altscreenWindow = {
      width: 1440,
      height: 540,
      pictureWidth: 1440,
      pictureHeight: 540,
      minWidth: 0,
      minHeight: 0,
    }
    const backend = await altscreen(
      new FakeBackend(),
      appState({ phase: 'active', canShowCarPlay: true, altscreenWindow }),
    )

    fireEvent.click(within(row('Insets')).getByRole('button', { name: 'Show frame' }))

    expect(backend.viewFrames).toEqual(['map'])
    cleanup()
    await altscreen()
    expect(screen.queryByRole('button', { name: 'Show frame' })).toBeNull()
  })

  // The view as the controls window switches it: what the AltScreen shows now, changed at once.
  it('switches what the AltScreen shows, as the controls window does', async () => {
    const backend = await altscreen()
    expect(chosen('View')).toEqual(['Map'])

    fireEvent.click(radio('View', 'Map + card'))

    expect(backend.altscreens).toEqual(['map-card'])
    expect(backend.saved).toEqual([])
  })

  // Without a session there is no map to show: the group is off and shows nothing chosen.
  it('keeps the view off without a session', async () => {
    const backend = await altscreen(new FakeBackend(), appState({ phase: 'calling', canShowCarPlay: false }))

    expect(chosen('View')).toEqual([])
    fireEvent.click(radio('View', 'HUD'))
    expect(backend.altscreens).toEqual([])
  })

  // The turn card's place means nothing but on Map + card: over the map, the turn card alone, or the HUD, it is not
  // there at all. Without a session, the view the AltScreen opens on decides.
  it("offers the turn card's place on Map + card alone", async () => {
    const state = appState({ phase: 'active', canShowCarPlay: true })
    for (const view of ['card', 'hud', 'map'] as const) {
      await altscreen(new FakeBackend(), { ...state, controls: { ...state.controls!, altscreen: view } })
      expect(screen.queryByRole('radiogroup', { name: 'Card position' })).toBeNull()
      cleanup()
    }
    await altscreen(new FakeBackend(), { ...state, controls: { ...state.controls!, altscreen: 'map-card' } })
    expect(screen.getByRole('radiogroup', { name: 'Card position' })).toBeTruthy()
    cleanup()

    const saved = new FakeBackend()
    saved.data = { ...saved.data, values: { ...saved.data.values, 'altscreen.content': 'map-card' } }
    const waiting = appState({ phase: 'calling', canShowCarPlay: false })
    await altscreen(saved, { ...waiting, controls: { ...waiting.controls!, altscreen: null } })
    expect(screen.getByRole('radiogroup', { name: 'Card position' })).toBeTruthy()
  })

  it('keeps the map stream under the HUD when asked, off until set', async () => {
    const backend = await altscreen()
    const keep = screen.getByRole('checkbox', { name: 'Keep the map stream under the HUD' }) as HTMLInputElement
    expect(keep.checked).toBe(false)

    fireEvent.click(keep)

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.hud.stream': 'true' }]))
  })

  it('saves where the turn card starts, Auto until set', async () => {
    const backend = await altscreen(new FakeBackend(), onMapCard())
    expect(chosen('Card position')).toEqual(['Auto'])

    fireEvent.click(radio('Card position', 'Top'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.cards': 'top' }]))
  })

  // The margins are the view's the AltScreen shows, inside its part of the frame: Map's here.
  it('saves the safe area of the view shown', async () => {
    const backend = await altscreen()
    fireEvent.change(inRow('Insets', 'Bottom'), { target: { value: '60' } })
    fireEvent.blur(inRow('Insets', 'Bottom'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.map.safe.bottom': '60' }]))
  })

  // The view shown has its own part of the frame, and its own margins inside that part.
  it('sets the part of the frame and the margins of the view shown', async () => {
    const backend = new FakeBackend()
    backend.data = { ...backend.data, values: { ...backend.data.values, 'altscreen.card.area': 'right5' } }
    const state = appState({ phase: 'active', canShowCarPlay: true })
    await altscreen(backend, { ...state, controls: { ...state.controls!, altscreen: 'card' } })
    const parts = screen.getByRole('radiogroup', { name: 'Area' })

    expect(chosen('Area')).toEqual(['Right 5/8'])
    fireEvent.click(within(parts).getByRole('radio', { name: 'Center 3/8' }))
    fireEvent.change(inRow('Insets', 'Top'), { target: { value: '10' } })
    fireEvent.blur(inRow('Insets', 'Top'))

    await waitFor(() =>
      expect(backend.saved).toEqual([{ 'altscreen.card.area': 'center3' }, { 'altscreen.card.safe.top': '10' }]),
    )
  })

  // The HUD is ChairPlay's own, on the whole window: neither a part of the frame nor margins to set.
  it('sets neither part nor margins for the HUD', async () => {
    const backend = new FakeBackend()
    const altscreenWindow = {
      width: 1440,
      height: 540,
      pictureWidth: 1440,
      pictureHeight: 540,
      minWidth: 0,
      minHeight: 0,
    }
    const state = appState({ phase: 'active', canShowCarPlay: true, altscreenWindow })
    await altscreen(backend, { ...state, controls: { ...state.controls!, altscreen: 'hud' } })
    const parts = screen.getByRole('radiogroup', { name: 'Area' })

    expect(
      within(parts)
        .getAllByRole('radio')
        .every((radio) => (radio as HTMLButtonElement).disabled),
    ).toBe(true)
    expect(['Top', 'Bottom', 'Left', 'Right'].every((edge) => inRow('Insets', edge).disabled)).toBe(true)
    expect(within(row('Insets')).queryByRole('button', { name: 'Show frame' })).toBeNull()
  })
})

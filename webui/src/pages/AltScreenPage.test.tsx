import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend, withKeys } from '../testing'

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
const radio = (group: string, name: string) => within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
const chosen = (group: string) =>
  within(screen.getByRole('radiogroup', { name: group }))
    .getAllByRole('radio')
    .filter((button) => button.getAttribute('aria-checked') === 'true')
    .map((button) => button.textContent)

describe('AltScreen page', () => {
  it('turns the AltScreen on, marked as experimental', async () => {
    const backend = await altscreen()
    const enabled = screen.getByRole('checkbox', { name: 'Enabled' }) as HTMLInputElement
    expect(enabled.checked).toBe(false)
    expect(screen.getByText('Experimental')).toBeTruthy()

    fireEvent.click(enabled)

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.enabled': 'true' }]))
  })

  // Off, the AltScreen has nothing to set: everything under Enabled waits for it, and says so.
  it('holds every other setting while the AltScreen is off', async () => {
    await altscreen()
    const held = screen.getByRole('radiogroup', { name: 'Size' }).closest('[inert]') as HTMLElement

    expect(held).not.toBeNull()
    expect(held.title).toBe('Turn on the AltScreen to set it')
    expect(held.contains(screen.getByRole('radiogroup', { name: 'Area' }))).toBe(true)
    expect(held.contains(screen.getByRole('checkbox', { name: 'Enabled' }))).toBe(false)
    cleanup()

    const backend = new FakeBackend()
    backend.data = { ...backend.data, values: { ...backend.data.values, 'altscreen.enabled': 'true' } }
    await altscreen(backend)
    expect(document.querySelector('[inert]')).toBeNull()
  })

  // A preset is its size alone: no car named on hover.
  it('saves a preset as both numbers', async () => {
    const backend = await altscreen()
    expect(chosen('Size')).toEqual(['1440×540'])
    expect(within(screen.getByRole('radiogroup', { name: 'Size' })).getAllByRole('radio').map((preset) => preset.title)).toEqual(['', '', '', ''])

    fireEvent.click(radio('Size', '1920×720'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.width': '1920', 'altscreen.height': '720' }]))
  })

  it('keeps a refused size with the reason, and no preset chosen', async () => {
    const backend = new FakeBackend()
    backend.nextOutcome = { ok: false, errors: { 'altscreen.width': 'Enter an even number of pixels' } }
    await altscreen(backend)

    fireEvent.change(textbox('Width'), { target: { value: '1441' } })
    fireEvent.keyDown(textbox('Width'), { key: 'Enter' })

    expect(await screen.findByText('Enter an even number of pixels')).toBeTruthy()
    expect(chosen('Size')).toEqual([])
  })

  // The AltScreen's window dragged to another size: Actual size brings it back to one screen pixel per picture pixel.
  it('offers Actual size while the AltScreen window differs from its picture', async () => {
    const altscreenWindow = { width: 1200, height: 450, pictureWidth: 1440, pictureHeight: 540, minWidth: 0, minHeight: 0 }
    const backend = await altscreen(new FakeBackend(), appState({ phase: 'active', canShowCarPlay: true, altscreenWindow }))

    const actual = screen.getByRole('button', { name: 'Actual size' })
    expect(actual.title).toBe('Resize the window to 1440×540, one screen pixel per picture pixel')
    fireEvent.click(actual)

    expect(backend.actualSized).toEqual(['altscreen'])
  })

  // The saved insets drawn on the AltScreen for a moment: a dotted green frame round what stays clear.
  it('shows the safe area on the AltScreen window, which it needs', async () => {
    const altscreenWindow = { width: 1440, height: 540, pictureWidth: 1440, pictureHeight: 540, minWidth: 0, minHeight: 0 }
    const backend = await altscreen(new FakeBackend(), appState({ phase: 'active', canShowCarPlay: true, altscreenWindow }))

    const show = within(row('Insets')).getByRole('button', { name: 'Show frame' })
    expect(show.title).toBe('Show the safe area on the AltScreen for a second')
    fireEvent.click(show)

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
    // The controls window has the views alone, not the card's position or the part: the row says so, not the group.
    expect(screen.queryByText('Also on the controls window')).toBeNull()
    expect(row('Content').title).toBe('What the AltScreen shows now, as the controls window switches it too; it opens on it again')
    // The HUD is no picture from the iPhone: ChairPlay draws it from the data CarPlay sends, which the words under the
    // views say.
    const hint = screen.getByText(
      "HUD is ChairPlay's own screen, drawn from the route, song, and call data the iPhone sends rather than from CarPlay's video",
    )
    expect(hint.closest('.row')!.previousElementSibling).toBe(row('Content'))
  })

  // Without a session there is no map to show: the group is off and shows nothing chosen.
  it('keeps the view off without a session', async () => {
    const backend = await altscreen(new FakeBackend(), appState({ phase: 'calling', canShowCarPlay: false }))

    expect(chosen('View')).toEqual([])
    fireEvent.click(radio('View', 'HUD'))
    expect(backend.altscreens).toEqual([])
  })

  // The turn card's place means nothing but on Map + card: over the map, the turn card alone, or the HUD it waits.
  it("holds the turn card's place while the view has no card to place", async () => {
    const state = appState({ phase: 'active', canShowCarPlay: true })
    for (const view of ['card', 'hud', 'map'] as const) {
      await altscreen(new FakeBackend(), { ...state, controls: { ...state.controls!, altscreen: view } })
      const group = screen.getByRole('radiogroup', { name: 'Card position' })

      expect(within(group).getAllByRole('radio').every((radio) => (radio as HTMLButtonElement).disabled)).toBe(true)
      expect(group.closest('.row')!.getAttribute('title')).toBe('Show Map + card to place the turn card')
      cleanup()
    }
  })

  it('saves where the turn card starts, Auto until set', async () => {
    const backend = await altscreen(new FakeBackend(), onMapCard())
    expect(chosen('Card position')).toEqual(['Auto'])
    const places = within(screen.getByRole('radiogroup', { name: 'Card position' })).getAllByRole('radio')
    expect(places.map((place) => place.textContent)).toEqual(['Auto', 'Left', 'Right', 'Top'])
    // Seen with Google Maps: left of a left-hand drive car's cluster, right of a right-hand drive one's.
    expect(places[0].title).toBe("On the driver's side, as the CarPlay page sets it")

    fireEvent.click(radio('Card position', 'Top'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.cards': 'top' }]))
  })

  // Four choices or more share the length of a row's controls, the network's name and security on Connection.
  it('stretches the long groups to the length of a row', async () => {
    await altscreen()

    for (const name of ['Size', 'View', 'Card position']) {
      expect(screen.getByRole('radiogroup', { name }).classList.contains('wide')).toBe(true)
    }
  })

  // Every size is the clusters' 8:3, which hovering the size says, for one typed in.
  it('offers the 8:3 sizes', async () => {
    await altscreen()

    const sizes = within(screen.getByRole('radiogroup', { name: 'Size' })).getAllByRole('radio').map((radio) => radio.textContent)
    expect(sizes).toEqual(['1280×480', '1440×540', '1600×600', '1920×720'])
    // Under the fields, in words rather than on hover.
    const hint = screen.getByText('Most clusters are 8:3, so the areas below split the width into eighths')
    expect(hint.closest('.row')!.previousElementSibling).toBe(textbox('Width').closest('.row'))
  })

  // The margins are the view's the AltScreen shows, inside its part of the frame: Map's here.
  it('saves the safe area of the view shown', async () => {
    const backend = await altscreen()
    fireEvent.change(inRow('Insets', 'Bottom'), { target: { value: '60' } })
    fireEvent.blur(inRow('Insets', 'Bottom'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.map.safe.bottom': '60' }]))
  })

  // The view chosen in Content shows in a part of the frame, one of its eighths, set under it; its margins inside that
  // part are the Safe area below.
  it("sets the part of the frame and the margins of the view shown", async () => {
    const backend = new FakeBackend()
    backend.data = { ...backend.data, values: { ...backend.data.values, 'altscreen.card.area': 'right5' } }
    const state = appState({ phase: 'active', canShowCarPlay: true })
    await altscreen(backend, { ...state, controls: { ...state.controls!, altscreen: 'card' } })
    const parts = screen.getByRole('radiogroup', { name: 'Area' })

    expect(within(parts).getAllByRole('radio').map((radio) => radio.textContent)).toEqual([
      'Left 5/8', 'Full screen', 'Right 5/8', 'Left 4/8', 'Center 4/8', 'Right 4/8', 'Left 3/8', 'Center 3/8', 'Right 3/8',
    ])
    // Three rows of three, as the parts lie: the fives, the halves, the threes.
    expect(parts.style.getPropertyValue('--columns')).toBe('3')
    expect(chosen('Area')).toEqual(['Right 5/8'])
    expect(screen.queryByRole('radiogroup', { name: 'For' })).toBeNull()
    // Under the view and where its turn card goes, as long as the view's own choice.
    const labels = [...document.querySelectorAll('.row > label')].map((label) => label.textContent)
    expect(labels.indexOf('Area')).toBe(labels.indexOf('Card position') + 1)
    // Not a wide group of six, which a large window stretches to six plates: as long as Content's four (CSS).
    expect(parts.classList.contains('wide')).toBe(false)
    expect(parts.classList.contains('view-areas')).toBe(true)
    fireEvent.click(within(parts).getByRole('radio', { name: 'Center 3/8' }))
    fireEvent.change(inRow('Insets', 'Top'), { target: { value: '10' } })
    fireEvent.blur(inRow('Insets', 'Top'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.card.area': 'center3' }, { 'altscreen.card.safe.top': '10' }]))
  })

  // The HUD is ChairPlay's own, on the whole window: neither a part of the frame nor margins to set.
  it('sets neither part nor margins for the HUD', async () => {
    const backend = new FakeBackend()
    const altscreenWindow = { width: 1440, height: 540, pictureWidth: 1440, pictureHeight: 540, minWidth: 0, minHeight: 0 }
    const state = appState({ phase: 'active', canShowCarPlay: true, altscreenWindow })
    await altscreen(backend, { ...state, controls: { ...state.controls!, altscreen: 'hud' } })
    const parts = screen.getByRole('radiogroup', { name: 'Area' })

    expect(within(parts).getAllByRole('radio').every((radio) => (radio as HTMLButtonElement).disabled)).toBe(true)
    expect(parts.closest('.row')!.getAttribute('title')).toBe('Show Map, Map + card, or Turn card to set its part of the frame')
    expect(['Top', 'Bottom', 'Left', 'Right'].every((edge) => inRow('Insets', edge).disabled)).toBe(true)
    expect(row('Insets').getAttribute('title')).toBe('Show Map, Map + card, or Turn card to set its safe area')
    expect(within(row('Insets')).queryByRole('button', { name: 'Show frame' })).toBeNull()
  })

  it('names the key that moves the turn card as bound, and none while it is unbound', async () => {
    const live = onMapCard()
    const turnCard = () => screen.getByText('Card position', { selector: 'label' }).closest('[title]')?.getAttribute('title')

    await altscreen(new FakeBackend(), withKeys(live, { 'altscreen.cards': 'Ctrl+T' }))
    expect(turnCard()).toBe('Where the turn card starts; Ctrl+T moves it while CarPlay runs')
    cleanup()

    await altscreen(new FakeBackend(), withKeys(live, { 'altscreen.cards': null }))
    expect(turnCard()).toBe('Where the turn card starts')
  })
})

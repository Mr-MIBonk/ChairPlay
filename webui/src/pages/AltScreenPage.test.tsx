import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

/** The shell on the AltScreen page, once the settings are in. */
async function altscreen(backend = new FakeBackend(), state = appState({ phase: 'active', canShowCarPlay: true })) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'AltScreen' }))
  return backend
}

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

  it('names the cars a preset fits, and saves it as both numbers', async () => {
    const backend = await altscreen()
    expect(chosen('Size')).toEqual(['1440×540'])
    expect(radio('Size', '1440×540').title).toBe('Audi Virtual Cockpit, 12.3 inch')

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

    const show = within(textbox('Top').closest('.row') as HTMLElement).getByRole('button', { name: 'Show frame' })
    expect(show.title).toBe('Show the safe area on the AltScreen for 3 seconds')
    fireEvent.click(show)

    expect(backend.framesShown).toBe(1)
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

  it('saves where the turn card starts, Auto until set', async () => {
    const backend = await altscreen()
    expect(chosen('Turn card')).toEqual(['Auto'])

    fireEvent.click(radio('Turn card', 'Top'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.cards': 'top' }]))
  })

  // Four choices or more share the length of a row's controls, the network's name and security on Connection.
  it('stretches the long groups to the length of a row', async () => {
    await altscreen()

    for (const name of ['Size', 'View', 'Turn card']) {
      expect(screen.getByRole('radiogroup', { name }).classList.contains('wide')).toBe(true)
    }
  })

  // Every size is the clusters' 8:3.
  it('offers the 8:3 sizes, 1600×600 between the Audi and the common one', async () => {
    await altscreen()

    const sizes = within(screen.getByRole('radiogroup', { name: 'Size' })).getAllByRole('radio').map((radio) => radio.textContent)
    expect(sizes).toEqual(['1280×480', '1440×540', '1600×600', '1920×720'])
  })

  it('saves the safe area', async () => {
    const backend = await altscreen()
    expect(textbox('Bottom').closest('.row')?.querySelector('label')?.textContent).toBe('Insets')

    fireEvent.change(textbox('Bottom'), { target: { value: '60' } })
    fireEvent.blur(textbox('Bottom'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.safe.bottom': '60' }]))
  })
})

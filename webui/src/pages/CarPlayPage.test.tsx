import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

/** The shell on the CarPlay page, once the settings are in. */
async function carplay(backend = new FakeBackend(), state = appState({ phase: 'active', canShowCarPlay: true })) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  // The tab carries a ⟳ while one of its settings waits for a restart.
  fireEvent.click(screen.getByRole('tab', { name: /^CarPlay/ }))
  return backend
}

const textbox = (name: string) => screen.getByRole('textbox', { name }) as HTMLInputElement
const radio = (group: string, name: string) => within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
const chosen = (group: string) =>
  within(screen.getByRole('radiogroup', { name: group }))
    .getAllByRole('radio')
    .filter((button) => button.getAttribute('aria-checked') === 'true')
    .map((button) => button.textContent)

describe('CarPlay page, display', () => {
  // display.width and display.height are blank in the file: the size the app runs at is the one chosen.
  it('picks the preset of the size the app runs at, and saves a preset as both numbers', async () => {
    const backend = await carplay()
    expect(chosen('Size')).toEqual(['1280×720'])
    expect(textbox('Width').placeholder).toBe('1280')
    expect(textbox('Height').placeholder).toBe('720')

    fireEvent.click(radio('Size', '1920×720'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'display.width': '1920', 'display.height': '720' }]))
    expect(chosen('Size')).toEqual(['1920×720'])
    expect(textbox('Width').value).toBe('1920')
  })

  it('saves a width typed in, or keeps it with the reason', async () => {
    const backend = await carplay()

    fireEvent.change(textbox('Width'), { target: { value: '1024' } })
    fireEvent.keyDown(textbox('Width'), { key: 'Enter' })
    await waitFor(() => expect(backend.saved).toEqual([{ 'display.width': '1024' }]))

    backend.nextOutcome = { ok: false, errors: { 'display.width': 'Enter an even number of pixels' } }
    fireEvent.change(textbox('Width'), { target: { value: '1025' } })
    fireEvent.keyDown(textbox('Width'), { key: 'Enter' })

    expect(await screen.findByText('Enter an even number of pixels')).toBeTruthy()
    expect(textbox('Width').value).toBe('1025')
    expect(chosen('Size')).toEqual([])
  })

  // The window was resized: Actual size brings it back to one screen pixel per picture pixel.
  it('offers Actual size while the window differs from the picture', async () => {
    const mainWindow = { width: 1024, height: 576, pictureWidth: 1280, pictureHeight: 720, minWidth: 1000, minHeight: 600 }
    const backend = await carplay(new FakeBackend(), appState({ phase: 'active', canShowCarPlay: true, mainWindow }))

    const actual = screen.getByRole('button', { name: 'Actual size' })
    expect(actual.title).toBe('Resize the window to 1280×720, one screen pixel per picture pixel')
    fireEvent.click(actual)

    expect(backend.actualSized).toEqual(['main'])
  })

  // At 150 % display scale an 800x480 picture is smaller than the window can get: the button could do nothing.
  // At 200 % display scale the window's smallest size outgrows an 800x480 picture: Actual size goes below it.
  it('offers Actual size though the picture is smaller than the window gets on its own', async () => {
    const mainWindow = { width: 1600, height: 960, pictureWidth: 800, pictureHeight: 480, minWidth: 1600, minHeight: 960 }
    const backend = await carplay(new FakeBackend(), appState({ phase: 'active', canShowCarPlay: true, mainWindow }))

    fireEvent.click(screen.getByRole('button', { name: 'Actual size' }))

    expect(backend.actualSized).toEqual(['main'])
  })

  // Actual size is the window's: a size chosen for the next connection stays chosen, the window going back to the
  // picture in use.
  it('brings the window back to the picture in use, leaving a size chosen for the next connection', async () => {
    const fake = new FakeBackend()
    fake.data.values['display.width'] = '1920'
    fake.data.values['display.height'] = '1080'
    const mainWindow = { width: 1024, height: 576, pictureWidth: 1280, pictureHeight: 720, minWidth: 1000, minHeight: 600 }
    const backend = await carplay(fake, appState({ phase: 'active', canShowCarPlay: true, mainWindow }))

    const actual = screen.getByRole('button', { name: 'Actual size' })
    expect(actual.title).toBe('Resize the window to 1280×720, one screen pixel per picture pixel')
    fireEvent.click(actual)

    expect(backend.actualSized).toEqual(['main'])
    expect(backend.saved).toEqual([])
  })

  // A changed setting's mark ends its row, after the unit, as the size's does.
  it('marks a changed frame rate after its unit', async () => {
    await carplay(new FakeBackend(), appState({ phase: 'active', canShowCarPlay: true, reconnectPending: ['display.fps'] }))

    const row = screen.getByRole('radiogroup', { name: 'Frame rate' }).parentElement!
    const last = [...row.children].slice(-2).map((child) => child.textContent)
    expect(last).toEqual(['fps', '⟳'])
  })

  // A size chosen waits for the next connection, as its mark says; there is nothing to make actual yet.
  it('has no Actual size for a size waiting for the next connection', async () => {
    const fake = new FakeBackend()
    fake.data.values['display.width'] = '1920'
    const mainWindow = { width: 1280, height: 720, pictureWidth: 1280, pictureHeight: 720, minWidth: 0, minHeight: 0 }
    await carplay(fake, appState({ phase: 'active', canShowCarPlay: true, mainWindow }))

    expect(screen.queryByRole('button', { name: 'Actual size' })).toBeNull()
  })

  it('has no Actual size while the window is at it', async () => {
    const mainWindow = { width: 1280, height: 720, pictureWidth: 1280, pictureHeight: 720, minWidth: 0, minHeight: 0 }
    await carplay(new FakeBackend(), appState({ phase: 'active', canShowCarPlay: true, mainWindow }))

    expect(screen.queryByRole('button', { name: 'Actual size' })).toBeNull()
  })

  it('saves the frame rate, 60 until one is set', async () => {
    const backend = await carplay()
    expect(chosen('Frame rate')).toEqual(['60'])

    fireEvent.click(radio('Frame rate', '30'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'display.fps': '30' }]))
  })

  // The wide screens of Audi and Mazda among the sizes, the 480 px tall ones first, in two rows.
  it('offers the sizes of wide car screens too', async () => {
    await carplay()

    const sizes = within(screen.getByRole('radiogroup', { name: 'Size' })).getAllByRole('radio')
    expect(sizes.map((size) => size.textContent)).toEqual(['800×480', '1024×480', '1280×480', '1280×720', '1920×720', '1920×1080'])
    expect(radio('Size', '1024×480').title).toBe('Audi MIB2, 8.3 in')
    expect(radio('Size', '1280×480').title).toBe('Mazda 3 and CX-30, 8.8 in')
  })

  // With touch declared CarPlay shows scroll buttons; with none it leaves them out, and the mouse still taps and drags.
  it('saves what the touchscreen can do, None until set', async () => {
    const backend = await carplay()
    expect(chosen('Touchscreen')).toEqual(['None'])
    expect(radio('Touchscreen', 'None').title).toBe(
      'No touchscreen: CarPlay leaves out its scroll buttons; the mouse still taps and drags, the keys move the focus',
    )

    fireEvent.click(radio('Touchscreen', 'Swipe'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'display.touch': 'swipe' }]))
  })

  it('saves the physical size in millimetres or pixels per inch', async () => {
    const backend = await carplay()
    expect(textbox('Physical width').placeholder).toBe('200')
    expect(textbox('Physical height').placeholder).toBe('auto')
    expect(textbox('Pixels per inch').placeholder).toBe('auto')

    for (const [name, value] of [['Physical width', '310'], ['Physical height', '175'], ['Pixels per inch', '160']]) {
      fireEvent.change(textbox(name), { target: { value } })
      fireEvent.blur(textbox(name))
    }

    await waitFor(() =>
      expect(backend.saved).toEqual([
        { 'display.physical.width.mm': '310' },
        { 'display.physical.height.mm': '175' },
        { 'display.ppi': '160' },
      ]),
    )
  })

  it('sizes the physical size fields as the size in pixels', async () => {
    await carplay()

    for (const name of ['Physical width', 'Physical height', 'Pixels per inch']) {
      expect(textbox(name).className).toBe(textbox('Width').className)
    }
  })
})

describe('CarPlay page, look', () => {
  it("shows the app's defaults while nothing is set", async () => {
    await carplay()

    expect(chosen('Theme')).toEqual(['Windows'])
    expect(chosen('Dock')).toEqual(['Auto'])
    expect(chosen('Driving side')).toEqual(['Left-hand'])
    expect(chosen('Outside safe area')).toEqual(['Draw UI'])
    expect(chosen('Corners')).toEqual(['Square'])
  })

  it('saves each choice under its key', async () => {
    const backend = await carplay()

    fireEvent.click(radio('Theme', 'Dark'))
    fireEvent.click(radio('Dock', 'Driver side'))
    fireEvent.click(radio('Driving side', 'Right-hand'))
    fireEvent.click(radio('Outside safe area', 'Black'))
    fireEvent.click(radio('Corners', 'Rounded'))

    await waitFor(() =>
      expect(backend.saved).toEqual([
        { 'display.theme': 'dark' },
        { 'display.dock': 'driver' },
        { 'display.right.hand.drive': 'true' },
        { 'display.safe.draw.outside': 'false' },
        { 'display.corners': 'rounded' },
      ]),
    )
  })
})

describe('CarPlay page, changes waiting for a restart', () => {
  // The app reads the screen's settings as it starts: a changed one is marked until then, beside its control.
  it('marks each changed setting that waits for a restart', async () => {
    const state = appState({ phase: 'active', canShowCarPlay: true, restartPending: ['display.corners', 'display.safe.top', 'display.width'] })
    await carplay(new FakeBackend(), state)

    const mark = (row: HTMLElement | null | undefined) => row?.querySelector('[title="Changed; applies after restart"]')
    expect(mark(radio('Corners', 'Square').closest('.row'))).toBeTruthy()
    expect(mark(textbox('Top').closest('.row'))).toBeTruthy()
    expect(mark(textbox('Width').closest('.row'))).toBeTruthy()
    expect(mark(radio('Theme', 'Dark').closest('.row'))).toBeFalsy()
  })
})

describe('CarPlay page, safe area', () => {
  it('saves each edge, and gives a refused one its reason under the frame', async () => {
    const backend = await carplay()
    expect(textbox('Top').closest('.row')?.querySelector('label')?.textContent).toBe('Insets')
    expect(textbox('Top').placeholder).toBe('0')

    fireEvent.change(textbox('Top'), { target: { value: '40' } })
    fireEvent.blur(textbox('Top'))
    await waitFor(() => expect(backend.saved).toEqual([{ 'display.safe.top': '40' }]))

    backend.nextOutcome = {
      ok: false,
      errors: { 'display.safe.left': 'The left and right safe areas together must be less than the width (1280 px)' },
    }
    fireEvent.change(textbox('Left'), { target: { value: '1400' } })
    fireEvent.blur(textbox('Left'))

    expect(await screen.findByText('The left and right safe areas together must be less than the width (1280 px)')).toBeTruthy()
    expect(textbox('Left').className).toContain('err')
  })
})

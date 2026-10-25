import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { AppState } from '../api'
import { Shell } from '../shell/Shell'
import { appState } from '../testing'
import { FakeBackend } from '../fakeBackend'
import { forgetPresets } from './AreaChooser'

afterEach(() => {
  cleanup()
  forgetPresets()
})

const KEY = 'altscreen.map.area.custom'

/** The shell on the AltScreen page, a live session showing [view] with the AltScreen open, once the settings are in. */
async function altscreen(backend: FakeBackend, view: 'map' | 'map-card' | 'card' = 'map') {
  const state = appState({ phase: 'active', canShowCarPlay: true })
  const open: AppState = {
    ...state,
    controls: { ...state.controls!, altscreen: view },
    altscreenWindow: {
      width: 1440,
      height: 540,
      pictureWidth: 1440,
      pictureHeight: 540,
      minWidth: 640,
      minHeight: 240,
    },
    altscreenWells: [{ x: 266.4, y: 270, radius: 244.8 }],
  }
  render(<Shell state={open} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'AltScreen' }))
  return backend
}

/** A backend with the AltScreen on and [values] saved. */
function having(values: Record<string, string>) {
  const backend = new FakeBackend()
  backend.data = { ...backend.data, values: { ...backend.data.values, 'altscreen.enabled': 'true', ...values } }
  return backend
}

const radio = (group: string, name: string) =>
  within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
const field = (name: string) => screen.getByRole('textbox', { name }) as HTMLInputElement
const rectangle = () => screen.getByLabelText('Custom area')

/** Drags [element] by [dx], [dy] screen pixels (frame pixels in jsdom, where the drawing has no size). */
function drag(element: Element, dx: number, dy: number) {
  fireEvent.pointerDown(element, { pointerId: 1, button: 0, clientX: 100, clientY: 50 })
  fireEvent.pointerMove(element, { pointerId: 1, clientX: 100 + dx / 2, clientY: 50 + dy / 2 })
  fireEvent.pointerMove(element, { pointerId: 1, clientX: 100 + dx, clientY: 50 + dy })
  fireEvent.pointerUp(element, { pointerId: 1, clientX: 100 + dx, clientY: 50 + dy })
}

describe('AltScreen custom area', () => {
  it('starts the rectangle as the preset the group had, the first time Custom is chosen', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'center5' }))

    fireEvent.click(radio('Area', 'Custom'))

    await waitFor(() =>
      expect(backend.saved).toEqual([{ 'altscreen.map.area': 'custom', [KEY]: '272, 0, 896, 540' }]),
    )
  })

  it('starts it as the whole frame after Full screen', async () => {
    const backend = await altscreen(having({ 'altscreen.card.area': 'full' }), 'card')

    fireEvent.click(radio('Area', 'Custom'))

    await waitFor(() =>
      expect(backend.saved).toEqual([
        { 'altscreen.card.area': 'custom', 'altscreen.card.area.custom': '0, 0, 1440, 540' },
      ]),
    )
  })

  it('keeps a rectangle already saved when Custom is chosen again', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'left4', [KEY]: '100, 20, 640, 400' }))

    fireEvent.click(radio('Area', 'Custom'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.map.area': 'custom' }]))
    expect(field('Area x').value).toBe('100')
  })

  it('saves a typed position snapped to the grid', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'custom', [KEY]: '272, 0, 896, 540' }))

    fireEvent.change(field('Area x'), { target: { value: '413' } })
    fireEvent.blur(field('Area x'))

    await waitFor(() => expect(backend.saved).toEqual([{ [KEY]: '412, 0, 896, 540' }]))
  })

  it('takes a saved rectangle into a smaller saved frame, as the app does', async () => {
    const backend = await altscreen(
      having({
        'altscreen.width': '1280',
        'altscreen.height': '480',
        'altscreen.map.area': 'custom',
        [KEY]: '1200, 0, 400, 300',
      }),
    )

    expect(field('Area x').value).toBe('880')
    fireEvent.change(field('Area y'), { target: { value: '20' } })
    fireEvent.blur(field('Area y'))

    await waitFor(() => expect(backend.saved).toEqual([{ [KEY]: '880, 20, 400, 300' }]))
  })

  it('saves a drag once, on release, with the moved rectangle', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'custom', [KEY]: '272, 0, 896, 540' }))

    drag(rectangle(), 51, 30)

    await waitFor(() => expect(backend.saved).toEqual([{ [KEY]: '322, 0, 896, 540' }]))
    expect(field('Area x').value).toBe('322')
  })

  it('resizes by a corner, the opposite one kept', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'custom', [KEY]: '272, 0, 896, 540' }))

    drag(screen.getByLabelText('Top-left corner'), 100, 100)

    await waitFor(() => expect(backend.saved).toEqual([{ [KEY]: '376, 100, 792, 440' }]))
  })

  it('puts the drawing back on the saved rectangle when the app refuses the drag', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'custom', [KEY]: '272, 0, 896, 540' }))
    backend.nextOutcome = { ok: false, errors: { [KEY]: 'The area does not fit the frame.' } }

    drag(rectangle(), 51, 0)

    await screen.findByText('The area does not fit the frame.')
    expect(rectangle().getAttribute('x')).toBe('272')
    expect(field('Area x').value).toBe('272')
  })

  it("shows the group's area on the AltScreen for the view shown", async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'custom', [KEY]: '272, 0, 896, 540' }), 'map-card')

    fireEvent.click(screen.getByRole('button', { name: 'Show area' }))

    expect(backend.viewFrames).toEqual(['map-card'])
  })
})

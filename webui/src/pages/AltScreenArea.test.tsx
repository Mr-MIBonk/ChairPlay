import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState } from '../testing'
import { FakeBackend } from '../fakeBackend'
import { forgetPresets } from './AreaChooser'

afterEach(() => {
  cleanup()
  forgetPresets()
})

/** The shell on the AltScreen page, a live session showing [view], once the settings are in. */
async function altscreen(backend: FakeBackend, view: 'map' | 'map-card' | 'card' = 'map') {
  const state = appState({ phase: 'active', canShowCarPlay: true })
  render(<Shell state={{ ...state, controls: { ...state.controls!, altscreen: view } }} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'AltScreen' }))
  return backend
}

/** A backend with [values] saved. */
function having(values: Record<string, string>) {
  const backend = new FakeBackend()
  backend.data = { ...backend.data, values: { ...backend.data.values, ...values } }
  return backend
}

const radio = (group: string, name: string) =>
  within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
const chosen = (group: string) =>
  within(screen.getByRole('radiogroup', { name: group }))
    .getAllByRole('radio')
    .filter((button) => button.getAttribute('aria-checked') === 'true')
    .map((button) => button.textContent)
const inInsets = (name: string) =>
  within(screen.getByText('Insets', { selector: 'label' }).closest('.row') as HTMLElement).getByRole('textbox', {
    name,
  }) as HTMLInputElement
const setInset = (name: string, value: string) => {
  fireEvent.change(inInsets(name), { target: { value } })
  fireEvent.blur(inInsets(name))
}

describe('AltScreen area', () => {
  it('saves Full screen and Custom', async () => {
    // A rectangle saved before: Custom does not start a new one.
    const saved = { 'altscreen.map.area.custom': '0, 0, 64, 64' }
    const backend = await altscreen(having({ 'altscreen.map.area': 'left4', ...saved }))
    expect(chosen('Area')).toEqual(['Preset'])

    fireEvent.click(radio('Area', 'Full screen'))
    fireEvent.click(radio('Area', 'Custom'))

    await waitFor(() =>
      expect(backend.saved).toEqual([{ 'altscreen.map.area': 'full' }, { 'altscreen.map.area': 'custom' }]),
    )
  })

  it('starts Preset on Center 5/8 for maps and Center 3/8 for Turn card, and shows the grid', async () => {
    const backend = await altscreen(new FakeBackend())
    expect(chosen('Area')).toEqual(['Full screen'])
    expect(screen.queryByRole('radiogroup', { name: 'Area preset' })).toBeNull()

    fireEvent.click(radio('Area', 'Preset'))
    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.map.area': 'center5' }]))
    cleanup()

    const card = await altscreen(having({ 'altscreen.card.area': 'full' }), 'card')
    fireEvent.click(radio('Area', 'Preset'))
    await waitFor(() => expect(card.saved).toEqual([{ 'altscreen.card.area': 'center3' }]))
  })

  it('shows the grid for a preset, and saves the one chosen', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'center5' }))
    expect(chosen('Area preset')).toEqual(['Center 5/8'])

    fireEvent.click(radio('Area preset', 'Right 4/8'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.map.area': 'right4' }]))
  })

  it('brings back the preset chosen before Full screen', async () => {
    const backend = await altscreen(having({ 'altscreen.map.area': 'right4' }))
    expect(chosen('Area preset')).toEqual(['Right 4/8'])
    cleanup()
    backend.data = { ...backend.data, values: { ...backend.data.values, 'altscreen.map.area': 'full' } }
    await altscreen(backend)

    fireEvent.click(radio('Area', 'Preset'))

    await waitFor(() => expect(backend.saved).toEqual([{ 'altscreen.map.area': 'right4' }]))
  })

  it('puts Map and Map + card in one group, apart from Turn card', async () => {
    const backend = await altscreen(new FakeBackend(), 'map-card')

    fireEvent.click(radio('Area', 'Custom'))
    setInset('Left', '5')

    await waitFor(() =>
      expect(backend.saved).toEqual([
        { 'altscreen.map.area': 'custom', 'altscreen.map.area.custom': '0, 0, 1440, 540' },
        { 'altscreen.map.safe.left': '5' },
      ]),
    )
    cleanup()

    const card = await altscreen(new FakeBackend(), 'card')
    setInset('Right', '7')
    await waitFor(() => expect(card.saved).toEqual([{ 'altscreen.card.safe.right': '7' }]))
  })
})

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState } from '../testing'
import { FakeBackend } from '../fakeBackend'

afterEach(cleanup)

it('saves enabling and disabling text input through the settings API', async () => {
  const backend = new FakeBackend()
  backend.data.values['keyboard.text.input'] = 'false'
  render(<Shell state={appState({ phase: 'active', canShowCarPlay: true })} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'Keyboard' }))
  const toggle = await screen.findByRole('switch', { name: 'Type in CarPlay' })
  fireEvent.click(toggle)
  await waitFor(() => expect(backend.saved).toEqual([{ 'keyboard.text.input': 'true' }]))
  fireEvent.click(toggle)
  await waitFor(() => expect(backend.saved).toEqual([
    { 'keyboard.text.input': 'true' }, { 'keyboard.text.input': 'false' },
  ]))
})

it('reflects whether a keyboard change is still waiting for the next connection', async () => {
  const backend = new FakeBackend()
  const state = appState({ phase: 'active', canShowCarPlay: true })
  const page = render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'Keyboard' }))
  await screen.findByRole('switch', { name: 'Type in CarPlay' })
  expect(screen.queryByTitle('Changed; applies at the next connection')).toBeNull()
  page.rerender(<Shell state={{ ...state, reconnectPending: ['keyboard.text.input'] }} backend={backend} />)
  expect(screen.queryByTitle('Changed; applies at the next connection')).not.toBeNull()
  page.rerender(<Shell state={state} backend={backend} />)
  expect(screen.queryByTitle('Changed; applies at the next connection')).toBeNull()
})

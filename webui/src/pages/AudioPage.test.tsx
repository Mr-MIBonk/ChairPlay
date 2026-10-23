import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

/** The shell on the Audio page, once the settings are in. */
async function audio(backend = new FakeBackend()) {
  render(<Shell state={appState({ phase: 'active', canShowCarPlay: true })} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'Audio' }))
  return backend
}

const select = (name: string) => screen.getByRole('combobox', { name }) as HTMLSelectElement
const options = (name: string) => within(select(name)).getAllByRole('option').map((option) => option.textContent)

describe('Audio page volume', () => {
  // The app's own volume, as the volume keys and the controls window set it; Windows' stays as it is.
  it('shows the volume and sets it with the slider', async () => {
    const backend = await audio()

    const slider = screen.getByRole('slider', { name: 'Volume' }) as HTMLInputElement
    expect(slider.value).toBe('60')
    expect(screen.getByText('60%')).toBeTruthy()
    fireEvent.change(slider, { target: { value: '37' } })

    expect(backend.volumes).toEqual([37])
    expect(screen.getByText('37%')).toBeTruthy()
  })

  // The music starts silent while the iPhone's first bursts buffer, then rises; it fades out over the same time as
  // CarPlay disconnects or the app closes.
  it('sets how the music fades in and out', async () => {
    const backend = await audio()

    const silence = screen.getByRole('textbox', { name: 'Buffering pause' }) as HTMLInputElement
    expect(silence.placeholder).toBe('2000')
    fireEvent.change(silence, { target: { value: '1500' } })
    fireEvent.blur(silence)
    const fade = screen.getByRole('textbox', { name: 'Fade' }) as HTMLInputElement
    expect(fade.placeholder).toBe('800')
    fireEvent.change(fade, { target: { value: '500' } })
    fireEvent.blur(fade)

    await waitFor(() => expect(backend.saved).toEqual([{ 'audio.start.silence.ms': '1500' }, { 'audio.start.fade.ms': '500' }]))
  })

  // Navigation prompts lower the music to the iPhone's level, as a car's head unit does, or to the level set when it
  // sends none or is not let.
  it('sets how low prompts put the music', async () => {
    const backend = await audio()

    const level = screen.getByRole('slider', { name: 'Music at' }) as HTMLInputElement
    expect(level.value).toBe('30')
    expect(level.step).toBe('5')
    expect(screen.getByText('30%')).toBeTruthy()
    fireEvent.change(level, { target: { value: '20' } })
    const iPhone = screen.getByRole('checkbox', { name: "Prefer the iPhone's level" }) as HTMLInputElement
    expect(iPhone.checked).toBe(true)
    fireEvent.click(iPhone)

    await waitFor(() => expect(backend.saved).toEqual([{ 'audio.duck.percent': '20' }, { 'audio.duck.iphone': 'false' }]))
  })

  it('says when the volume is muted', async () => {
    const state = appState({ phase: 'active', canShowCarPlay: true })
    render(<Shell state={{ ...state, controls: { ...state.controls!, volume: { level: 60, muted: true } } }} backend={new FakeBackend()} />)
    await screen.findByRole('textbox', { name: 'Network' })
    fireEvent.click(screen.getByRole('tab', { name: 'Audio' }))

    expect(screen.getByText('Muted')).toBeTruthy()
  })
})

describe('Audio page', () => {
  it('lists the devices after the Windows default, which is chosen while none is set', async () => {
    await audio()

    expect(options('Output')).toEqual(['Windows default', 'Speakers (Realtek Audio)', 'Headphones (USB Audio)'])
    expect(options('Microphone')).toEqual(['Windows default', 'Microphone (Realtek Audio)'])
    expect(select('Output').value).toBe('default')
  })

  it('saves a device by its name, and the Windows default as default', async () => {
    const backend = await audio()

    fireEvent.change(select('Output'), { target: { value: 'Headphones (USB Audio)' } })
    fireEvent.change(select('Microphone'), { target: { value: 'default' } })

    await waitFor(() =>
      expect(backend.saved).toEqual([{ 'audio.output': 'Headphones (USB Audio)' }, { 'audio.input': 'default' }]),
    )
  })

  // A device unplugged, or a part of a name written in the file, stays chosen rather than turning into the default.
  it('keeps a saved device the list does not have', async () => {
    const backend = new FakeBackend()
    backend.data = { ...backend.data, values: { ...backend.data.values, 'audio.output': 'Car stereo' } }
    await audio(backend)

    expect(select('Output').value).toBe('Car stereo')
    expect(options('Output')).toContain('Car stereo (not found)')
  })

  it('saves echo cancellation, marked as experimental', async () => {
    const backend = await audio()
    const echo = screen.getByRole('checkbox', { name: 'Echo cancellation for calls through speakers' }) as HTMLInputElement
    expect(echo.checked).toBe(false)
    expect(screen.getByText('Experimental')).toBeTruthy()

    fireEvent.click(echo)

    await waitFor(() => expect(backend.saved).toEqual([{ 'audio.echo.cancellation': 'true' }]))
  })
})

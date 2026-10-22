import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { FakeBackend } from './testing'
import { WifiForm } from './WifiForm'

afterEach(cleanup)

const wifi = { ssid: 'Home', security: 'WPA_WPA2', passphraseSet: true }

describe('WifiForm', () => {
  it('never shows the stored password, only that one is set', () => {
    render(<WifiForm wifi={wifi} backend={new FakeBackend()} running={false} />)

    const password = screen.getByLabelText('Password') as HTMLInputElement
    expect(password.value).toBe('')
    expect(password.placeholder).toContain('Saved')
    expect(password.type).toBe('password')
  })

  it('says when no password is set', () => {
    render(<WifiForm wifi={{ ...wifi, passphraseSet: false }} backend={new FakeBackend()} running={false} />)

    expect((screen.getByLabelText('Password') as HTMLInputElement).placeholder).toBe('Not set')
  })

  it('saves only what changed, and keeps the stored password unless a new one is typed', async () => {
    const backend = new FakeBackend()
    render(<WifiForm wifi={wifi} backend={backend} running={true} />)

    fireEvent.change(screen.getByLabelText('Network name'), { target: { value: 'Garage' } })
    fireEvent.click(screen.getByText('Save Wi-Fi'))

    expect(await screen.findByText('Saved. Applies with the next connection.')).toBeTruthy()
    expect(backend.saved).toEqual([{ 'wifi.ssid': 'Garage' }])
  })

  it('sends a typed password and clears the field after saving', async () => {
    const backend = new FakeBackend()
    render(<WifiForm wifi={wifi} backend={backend} running={false} />)
    const password = screen.getByLabelText('Password') as HTMLInputElement

    fireEvent.change(password, { target: { value: 'correct horse' } })
    fireEvent.click(screen.getByText('Save Wi-Fi'))

    expect(await screen.findByText('Saved.')).toBeTruthy()
    expect(backend.saved).toEqual([{ 'wifi.passphrase': 'correct horse' }])
    expect(password.value).toBe('')
  })

  it("shows the app's reasons next to the fields", async () => {
    const backend = new FakeBackend()
    backend.nextOutcome = { ok: false, errors: { 'wifi.passphrase': 'A WPA password is 8 to 63 characters' } }
    render(<WifiForm wifi={wifi} backend={backend} running={false} />)

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'short' } })
    fireEvent.click(screen.getByText('Save Wi-Fi'))

    expect((await screen.findByRole('alert')).textContent).toBe('A WPA password is 8 to 63 characters')
  })

  it('has nothing to save until something changes', () => {
    render(<WifiForm wifi={wifi} backend={new FakeBackend()} running={false} />)

    expect((screen.getByText('Save Wi-Fi') as HTMLButtonElement).disabled).toBe(true)
  })
})

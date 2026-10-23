import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

async function about() {
  render(<Shell state={appState({ phase: 'active', canShowCarPlay: true })} backend={new FakeBackend()} />)
  await screen.findByRole('textbox', { name: 'Network' })
  fireEvent.click(screen.getByRole('tab', { name: 'About' }))
}

const row = (label: string) => screen.getByText(label, { selector: 'label' }).parentElement!.textContent

describe('About page', () => {
  it('names the app and what it is', async () => {
    await about()

    expect(screen.getByText('ChairPlay', { selector: '.about-name' })).toBeTruthy()
    expect(screen.getByText('Wireless CarPlay receiver for Windows')).toBeTruthy()
  })

  it('credits the work it is built on, and its license', async () => {
    await about()

    expect(row('CarPlay core')).toBe('CarPlay coreDiPlay by shihabal3amri')
    expect(row('HUD maneuvers')).toBe('HUD maneuversLuKa, mib2q-carplay-rgi')
    expect(row('License')).toBe('LicenseGPL-3.0')
  })

  it("says Apple's names are Apple's", async () => {
    await about()

    expect(
      screen.getByText('CarPlay, iPhone, AirPlay, and Siri are trademarks of Apple Inc. ChairPlay is not affiliated with Apple.'),
    ).toBeTruthy()
  })
})

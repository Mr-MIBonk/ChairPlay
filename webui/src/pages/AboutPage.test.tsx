import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(cleanup)

async function about(state = appState({ phase: 'active', canShowCarPlay: true })) {
  render(<Shell state={state} backend={new FakeBackend()} />)
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

  it('says which build this is, and which DiPlay core it has', async () => {
    await about()

    expect(row('Build')).toBe('Build4e6d50df · 2026-10-23')
    expect(row('DiPlay core')).toBe('DiPlay core143f4674 · 2026-10-21')
  })

  it('says so when the build did not record them', async () => {
    await about(appState({ phase: 'active', canShowCarPlay: true, about: { app: null, core: null, details: '' } }))

    expect(row('Build')).toBe('BuildNot recorded')
  })

  it('credits the work it is built on, and its license', async () => {
    await about()

    expect(row('CarPlay core')).toBe('CarPlay coreDiPlay by shihabal3amri')
    expect(row('HUD maneuvers')).toBe('HUD maneuversLuKa, mib2q-carplay-rgi')
    expect(row('License')).toBe('LicenseGPL-3.0 · Third-party notices')
  })

  it('links the credits and the support pages', async () => {
    const backend = new FakeBackend()
    render(<Shell state={appState({ phase: 'active', canShowCarPlay: true })} backend={backend} />)
    await screen.findByRole('textbox', { name: 'Network' })
    fireEvent.click(screen.getByRole('tab', { name: 'About' }))

    for (const name of ['DiPlay', 'mib2q-carplay-rgi', 'Third-party notices', 'New issue on GitHub', 'Discussions',
      'Pull requests', 'github.com/NumberOneBot/ChairPlay', 'Releases']) {
      fireEvent.click(screen.getByRole('button', { name }))
    }

    expect(backend.opened).toEqual(['diplay', 'rgi', 'notices', 'issues', 'discussions', 'pull-requests', 'source', 'releases'])
  })

  it('copies the details a report needs', async () => {
    const writeText = vi.fn(async () => {})
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    await about()

    fireEvent.click(screen.getByRole('button', { name: 'Copy details' }))

    expect(writeText).toHaveBeenCalledWith(appState().about.details)
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy()
    vi.unstubAllGlobals()
  })

  it('opens Get started again', async () => {
    await about()

    fireEvent.click(screen.getByRole('button', { name: 'Show again' }))

    expect(await screen.findByRole('heading', { name: 'Get started' })).toBeTruthy()
  })

  it("says Apple's names are Apple's", async () => {
    await about()

    expect(
      screen.getByText('CarPlay, iPhone, AirPlay, and Siri are trademarks of Apple Inc. ChairPlay is not affiliated with Apple.'),
    ).toBeTruthy()
  })
})

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AppState } from '../api'
import { Shell } from '../shell/Shell'
import { appState, FakeBackend } from '../testing'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const live = { phase: 'active', canShowCarPlay: true } as const

/** The shell, which opens on Connection, once the settings are in. */
async function connection(state: AppState = appState(live), backend = new FakeBackend()) {
  render(<Shell state={state} backend={backend} />)
  await screen.findByRole('textbox', { name: 'Network' })
  return backend
}

const textbox = (name: string) => screen.getByRole('textbox', { name }) as HTMLInputElement
const password = () => screen.getByLabelText('Password') as HTMLInputElement

describe('Connection page, Wi-Fi', () => {
  it('shows the network and its security', async () => {
    await connection()

    expect(textbox('Network').value).toBe('Home')
    expect((screen.getByRole('combobox', { name: 'Security' }) as HTMLSelectElement).value).toBe('WPA_WPA2')
  })

  it('saves the network on Enter', async () => {
    const backend = await connection()

    fireEvent.change(textbox('Network'), { target: { value: 'Car' } })
    fireEvent.keyDown(textbox('Network'), { key: 'Enter' })

    await waitFor(() => expect(backend.saved).toEqual([{ 'wifi.ssid': 'Car' }]))
  })

  it('saves the security as soon as it is chosen', async () => {
    const backend = await connection()

    fireEvent.change(screen.getByRole('combobox', { name: 'Security' }), { target: { value: 'WPA3_ONLY' } })

    await waitFor(() => expect(backend.saved).toEqual([{ 'wifi.security': 'WPA3_ONLY' }]))
  })

  // The saved password never reaches the page: the field shows only that there is one, and saves what is typed.
  it('saves a typed password and empties the field', async () => {
    const backend = await connection()
    expect(password().value).toBe('')
    expect(password().placeholder).toBe('••••••••••')

    fireEvent.change(password(), { target: { value: 'secret123' } })
    fireEvent.keyDown(password(), { key: 'Enter' })

    await waitFor(() => expect(backend.saved).toEqual([{ 'wifi.passphrase': 'secret123' }]))
    await waitFor(() => expect(password().value).toBe(''))
  })

  it('says when no password is set', async () => {
    await connection(appState({ ...live, wifi: { ssid: 'Home', security: 'WPA_WPA2', passphraseSet: false } }))

    expect(password().placeholder).toBe('Not set')
  })

  it('shows the typed password with the eye', async () => {
    await connection()

    fireEvent.click(screen.getByRole('button', { name: 'Show the password' }))

    expect(password().type).toBe('text')
    expect(screen.getByRole('button', { name: 'Hide the password' })).toBeTruthy()
  })

  it('keeps a refused password with the reason', async () => {
    const backend = new FakeBackend()
    backend.nextOutcome = { ok: false, errors: { 'wifi.passphrase': 'A WPA password has 8 to 63 characters' } }
    await connection(appState(live), backend)

    fireEvent.change(password(), { target: { value: 'short' } })
    fireEvent.keyDown(password(), { key: 'Enter' })

    expect(await screen.findByText('A WPA password has 8 to 63 characters')).toBeTruthy()
    expect(password().value).toBe('short')
  })
})

describe('Connection page, iPhone', () => {
  it('saves the switches at once, Connect when ChairPlay starts marked as applying after restart', async () => {
    const backend = await connection()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Connect when ChairPlay starts' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Show CarPlay as soon as the iPhone connects' }))

    await waitFor(() => expect(backend.saved).toEqual([{ 'ui.autoconnect': 'false' }, { 'ui.autoshow': 'false' }]))
    const row = screen.getByRole('checkbox', { name: 'Connect when ChairPlay starts' }).closest('.row') as HTMLElement
    expect(within(row).getByTitle('Applies after restart')).toBeTruthy()
  })

  it('saves the name, the address, and the time to join, with the defaults shown while blank', async () => {
    const backend = await connection()
    expect(textbox('Name on iPhone').placeholder).toBe('ChairPlay')
    expect(textbox('Bluetooth address').placeholder).toBe('Any paired iPhone')
    expect(textbox('Time to join Wi-Fi').placeholder).toBe('30')

    for (const [name, value] of [['Name on iPhone', 'Car'], ['Bluetooth address', '01:23:45:67:89:AB'], ['Time to join Wi-Fi', '45']]) {
      fireEvent.change(textbox(name), { target: { value } })
      fireEvent.blur(textbox(name))
    }

    await waitFor(() =>
      expect(backend.saved).toEqual([
        { 'device.name': 'Car' },
        { 'iphone.address': '01:23:45:67:89:AB' },
        { 'iphone.join.seconds': '45' },
      ]),
    )
  })
})

describe('Connection page, the paired iPhone', () => {
  it('names the paired iPhone and forgets it', async () => {
    const backend = await connection()

    const row = screen.getByText('Paired iPhone', { selector: 'label' }).closest('.row') as HTMLElement
    expect(row.textContent).toContain("Kate's iPhone")
    fireEvent.click(within(row).getByRole('button', { name: 'Forget' }))

    await waitFor(() => expect(backend.forgets).toBe(1))
  })

  // Windows lists no paired iPhone: the message says how to pair, on both sides.
  it('says how to pair one when none is paired', async () => {
    const backend = await connection(appState({ ...live, setup: { ...appState().setup, pairedIphone: null } }))

    const row = screen.getByText('Paired iPhone', { selector: 'label' }).closest('.row') as HTMLElement
    expect(row.textContent).toContain('None')
    const message = screen.getByRole('alert')
    expect(message.textContent).toContain('No iPhone is paired with this PC.')
    fireEvent.click(within(message).getByRole('button', { name: 'Open Bluetooth settings' }))

    expect(backend.opened).toEqual(['bluetooth'])
  })

  it('offers Get started from the message', async () => {
    await connection(appState({ ...live, setup: { ...appState().setup, pairedIphone: null } }))

    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: 'Get started' }))

    expect(await screen.findByRole('heading', { name: 'Get started' })).toBeTruthy()
  })
})

describe('Connection page, messages', () => {
  const setup = (changes: Partial<AppState['setup']>) => ({ ...appState().setup, ...changes })

  it('shows none while nothing is wrong', async () => {
    await connection()

    expect(screen.queryAllByRole('alert')).toEqual([])
  })

  it('says where the MFi files go and opens the folder', async () => {
    const backend = await connection(appState({ ...live, setup: setup({ mfiPresent: false }) }))

    const message = screen.getByRole('alert')
    expect(message.textContent).toContain('MFi files not found.')
    expect(message.textContent).toContain('C:\\ChairPlay\\offline-mfi')
    fireEvent.click(within(message).getByRole('button', { name: 'Open' }))

    expect(backend.opened).toEqual(['mfi-folder'])
  })

  it('gives the firewall command to copy when no rule lets the iPhone in', async () => {
    const writeText = vi.fn(async () => {})
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    await connection(appState({ ...live, setup: setup({ firewall: 'missing' }) }))

    const message = screen.getByRole('alert')
    expect(message.textContent).toContain('The firewall blocks the iPhone.')
    fireEvent.click(within(message).getByRole('button', { name: 'Copy' }))

    expect(writeText).toHaveBeenCalledWith(appState().setup.firewallCommands)
    expect(await within(message).findByRole('button', { name: 'Copied' })).toBeTruthy()
  })

  // Windows asks for the administrator's consent; the message goes once the live checks find the rule.
  it('adds the firewall rule with the consent Windows asks for', async () => {
    const backend = await connection(appState({ ...live, setup: setup({ firewall: 'missing' }) }))

    const message = screen.getByRole('alert')
    expect(message.textContent).toContain('or run this in Terminal (Admin):')
    fireEvent.click(within(message).getByRole('button', { name: 'Add the rule' }))

    await waitFor(() => expect(backend.firewallRules).toBe(1))
  })

  it('says when the rule was not added', async () => {
    const backend = new FakeBackend()
    backend.consent = false
    await connection(appState({ ...live, setup: setup({ firewall: 'missing' }) }), backend)

    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: 'Add the rule' }))

    expect(await screen.findByText("The rule wasn't added. Run the command in Terminal (Admin) instead.")).toBeTruthy()
  })

  it("opens the firewall's rules when one blocks ChairPlay", async () => {
    const backend = await connection(appState({ ...live, setup: setup({ firewall: 'blocked' }) }))

    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: "Open the firewall's rules" }))

    expect(backend.opened).toEqual(['firewall-rules'])
  })

  it('shows the last error with its time, and opens Bluetooth settings for an iPhone that did not answer', async () => {
    const error = { message: 'RFCOMM connect failed: 10060', at: '14:02:11' }
    const backend = await connection(appState({ phase: 'retrying', error }))

    const message = screen.getByRole('alert')
    expect(message.textContent).toContain("Couldn't reach the iPhone.")
    expect(message.textContent).toContain('14:02:11')
    expect(message.title).toBe('RFCOMM connect failed: 10060')
    fireEvent.click(within(message).getByRole('button', { name: 'Open Bluetooth settings' }))

    expect(backend.opened).toEqual(['bluetooth'])
  })

  it('points a join that timed out to the Wi-Fi, not to Bluetooth', async () => {
    const error = { message: 'Bluetooth control ended TIMEOUT before the iPhone joined over Wi-Fi', at: '14:02:11' }
    await connection(appState({ phase: 'retrying', error }))

    const message = screen.getByRole('alert')
    expect(message.textContent).toContain("The iPhone didn't join the Wi-Fi.")
    expect(within(message).queryByRole('button', { name: 'Open Bluetooth settings' })).toBeNull()
  })

  it('drops the last error once a session is active', async () => {
    await connection(appState({ ...live, error: { message: 'RFCOMM connect failed', at: '14:02:11' } }))

    expect(screen.queryAllByRole('alert')).toEqual([])
  })
})

import type { AppState } from '../api'
import { errorKind } from '../status'

export type StepState = 'done' | 'now' | 'todo'

export interface Step {
  state: StepState
  /** What the step's line says at its right end. */
  note: string
}

/** What the Wi-Fi step has to say instead of its explanation, when something is wrong. */
export type WifiProblem = { kind: 'failed'; ssid: string } | { kind: 'other-network'; pcWifi: string } | null

export interface Steps {
  mfi: Step
  /** Null while the firewall is off: there is nothing to let ChairPlay through, and the guide is a step shorter. */
  firewall: Step | null
  pair: Step
  wifi: Step
  wifiProblem: WifiProblem
  allDone: boolean
  /** Why Connect waits, for its tooltip; null once every step is done. */
  connectWaits: string | null
}

/**
 * Get started's four steps as the app's state has them: each done, open now (the first one not done), or still to do.
 * A join that timed out reopens the Wi-Fi step, since the password is the likely cause, until a password is saved
 * after it ([joinFailureSeen] is that error's time).
 */
export function setupSteps(state: AppState, joinFailureSeen: string | null): Steps {
  const { setup, wifi, error } = state
  const firewallDone = setup.firewall !== 'missing' && setup.firewall !== 'blocked'
  const failed = error !== null && state.phase !== 'active' && errorKind(error.message) === 'join' && error.at !== joinFailureSeen
  const passwordNeeded = wifi.security !== 'NONE' && !wifi.passphraseSet
  const otherNetwork = wifi.ssid !== '' && setup.pcWifi !== null && setup.pcWifi !== wifi.ssid
  const wifiDone = wifi.ssid !== '' && !passwordNeeded && !otherNetwork && !failed
  const done = [setup.mfiPresent, firewallDone, setup.pairedIphone !== null, wifiDone]
  const open = done.indexOf(false)
  const stateOf = (index: number): StepState => (done[index] ? 'done' : index === open ? 'now' : 'todo')
  const firewallNote = {
    present: 'Rule added', unknown: 'Not checked', missing: 'Rule missing', blocked: 'Blocked by a rule', off: 'Firewall off',
  }[setup.firewall]
  const wifiNote = wifiDone
    ? wifi.ssid
    : failed
      ? 'iPhone didn’t join'
      : otherNetwork
        ? 'Not this PC’s network'
        : wifi.ssid === ''
          ? 'Not set'
          : 'Password needed'
  const waits = [
    'Put the MFi files in place first',
    'Let ChairPlay through the firewall first',
    'Pair the iPhone first',
    failed ? 'Check the Wi-Fi password first' : 'Enter the Wi-Fi network first',
  ]
  return {
    mfi: { state: stateOf(0), note: setup.mfiPresent ? 'Found' : 'Not found' },
    firewall: setup.firewall === 'off' ? null : { state: stateOf(1), note: firewallNote },
    pair: { state: stateOf(2), note: setup.pairedIphone ?? 'No iPhone paired' },
    wifi: { state: stateOf(3), note: wifiNote },
    wifiProblem: failed
      ? { kind: 'failed', ssid: wifi.ssid }
      : otherNetwork
        ? { kind: 'other-network', pcWifi: setup.pcWifi! }
        : null,
    allDone: open === -1,
    connectWaits: open === -1 ? null : waits[open],
  }
}

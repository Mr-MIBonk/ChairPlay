// Fakes for the tests: an app state and a backend that records what the screens ask for.
import type { AppState, Backend, SaveOutcome, Screen, SettingsData, WindowName } from './api'

export function appState(overrides: Partial<AppState> = {}): AppState {
  return {
    version: 1,
    phase: 'calling',
    screen: 'start',
    error: null,
    knownIphone: false,
    canShowCarPlay: false,
    autoshow: true,
    embedded: true,
    wifi: { ssid: 'Home', security: 'WPA_WPA2', passphraseSet: true },
    reconnectPending: [],
    restartPending: [],
    windows: { altscreen: { available: true, visible: true }, controls: { available: true, visible: true } },
    setup: {
      mfiFolder: 'C:\\ChairPlay\\offline-mfi',
      mfiPresent: true,
      firewall: 'present',
      firewallCommands: 'netsh advfirewall firewall add rule name="ChairPlay CarPlay inbound"',
      settingsFile: 'C:\\ChairPlay\\settings.properties',
    },
    ...overrides,
  }
}

export class FakeBackend implements Backend {
  saved: Record<string, string>[] = []
  shown: Screen[] = []
  windows: { name: WindowName; visible: boolean }[] = []
  connects = 0
  reconnects = 0
  nextOutcome: SaveOutcome | null = null
  data: SettingsData = {
    values: {
      'wifi.ssid': 'Home',
      'wifi.security': 'WPA_WPA2',
      'ui.autoconnect': 'true',
      'ui.autoshow': 'true',
      'altscreen.enabled': 'false',
      'window.width': '',
      'window.height': '',
      'altscreen.width': '1440',
      'altscreen.height': '542',
    },
    passphraseSet: true,
    applies: {
      'wifi.ssid': 'reconnect',
      'wifi.passphrase': 'reconnect',
      'wifi.security': 'reconnect',
      'ui.autoconnect': 'restart',
      'ui.autoshow': 'now',
      'altscreen.enabled': 'restart',
      'window.width': 'restart',
      'window.height': 'restart',
      'altscreen.width': 'restart',
      'altscreen.height': 'restart',
    },
    display: { width: 1280, height: 720 },
  }

  async settings() {
    return this.data
  }

  async save(changes: Record<string, string>): Promise<SaveOutcome> {
    this.saved.push(changes)
    const outcome = this.nextOutcome
    this.nextOutcome = null
    if (outcome) return outcome
    return { ok: true, applies: Object.fromEntries(Object.keys(changes).map((key) => [key, this.data.applies[key]])) }
  }

  async connect() {
    this.connects++
    return null
  }

  async reconnect() {
    this.reconnects++
  }

  async show(screen: Screen) {
    this.shown.push(screen)
    return true
  }

  async window(name: WindowName, visible: boolean) {
    this.windows.push({ name, visible })
    return true
  }
}

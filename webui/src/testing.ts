// Fakes for the tests: an app state and a backend that records what the screens ask for.
import type { AltscreenContent, AppState, Backend, SaveOutcome, Screen, SettingsData, WindowName, WindowsPlace } from './api'

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
    controls: {
      keys: {
        'knob.left': 'Left', 'knob.right': 'Right', 'knob.up': 'Up', 'knob.down': 'Down', 'knob.next': 'Tab',
        'knob.previous': 'Shift+Tab', select: 'Enter', back: 'Escape', home: 'Home', siri: 'S',
        'media.previous': 'F7', 'media.playpause': 'F8', 'media.next': 'F9', 'call.answer': 'F3', 'call.end': 'F4',
        'altscreen.content': 'F11', 'altscreen.zoom.in': 'Equal', 'altscreen.zoom.out': 'Minus',
        'volume.up': 'Ctrl+Up', 'volume.down': 'Ctrl+Down', 'volume.mute': 'Ctrl+M',
      },
      volume: { level: 60, muted: false },
      altscreen: 'map',
      altHeld: false,
    },
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
      'altscreen.height': '540',
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

  disconnects = 0

  async disconnect() {
    this.disconnects++
  }

  async show(screen: Screen) {
    this.shown.push(screen)
    return true
  }

  async window(name: WindowName, visible: boolean) {
    this.windows.push({ name, visible })
    return true
  }

  /** The controls window's buttons, as "action down" or "action up". */
  controls: string[] = []
  altscreens: AltscreenContent[] = []

  async control(action: string, down: boolean) {
    this.controls.push(`${action} ${down ? 'down' : 'up'}`)
  }

  async altscreen(content: AltscreenContent) {
    this.altscreens.push(content)
    return true
  }

  opened: WindowsPlace[] = []

  async open(place: WindowsPlace) {
    this.opened.push(place)
    return true
  }
}

// Fakes for the tests: an app state and a backend that records what the screens ask for.
import type {
  AltscreenContent,
  AppState,
  Backend,
  ControlsData,
  DemoKind,
  KeyPhase,
  PictureWindow,
  SaveOutcome,
  Screen,
  SettingsData,
  WindowName,
  WindowsPlace,
} from './api'

export function appState(overrides: Partial<AppState> = {}): AppState {
  return {
    version: 1,
    phase: 'calling',
    screen: 'start',
    error: null,
    knownIphone: false,
    canShowCarPlay: false,
    autoshow: true,
    waiting: false,
    demo: { playing: null, available: true },
    gps: null,
    embedded: true,
    wifi: { ssid: 'Home', security: 'WPA_WPA2', passphraseSet: true },
    reconnectPending: [],
    restartPending: [],
    windows: { altscreen: { available: true, visible: true }, controls: { available: true, visible: true } },
    mainWindow: null,
    altscreenWindow: null,
    controlsKeys: false,
    setupDone: true,
    accent: '#0a84ff',
    about: {
      app: { commit: '4e6d50df', date: '2026-10-23' },
      core: { commit: '143f4674', date: '2026-10-21' },
      details: 'ChairPlay 4e6d50df (2026-10-23)\nDiPlay core 143f4674 (2026-10-21)\nWindows 11 24H2 (build 26300)',
    },
    controls: {
      keys: {
        'knob.left': 'Left',
        'knob.right': 'Right',
        'knob.up': 'Up',
        'knob.down': 'Down',
        'knob.next': 'Tab',
        'knob.previous': 'Shift+Tab',
        select: 'Enter',
        back: 'Escape',
        home: 'Home',
        siri: 'S',
        'media.previous': 'F7',
        'media.playpause': 'F8',
        'media.next': 'F9',
        'call.answer': 'F3',
        'call.end': 'F4',
        reconnect: 'Ctrl+R',
        screenshot: 'F12',
        'altscreen.content': 'F11',
        'altscreen.zoom.in': 'Equal',
        'altscreen.zoom.out': 'Minus',
        'altscreen.cards': 'Shift+F11',
        'volume.up': 'Ctrl+Up',
        'volume.down': 'Ctrl+Down',
        'volume.mute': 'Ctrl+M',
        drive: 'F10',
      },
      volume: { level: 60, muted: false },
      altscreen: 'map',
      altHeld: false,
      playing: false,
    },
    setup: {
      mfiFolder: 'C:\\ChairPlay\\offline-mfi',
      mfiPresent: true,
      firewall: 'present',
      firewallCommands: 'netsh advfirewall firewall add rule name="ChairPlay CarPlay inbound"',
      pairedIphone: "Kate's iPhone",
      pcWifi: 'Home',
      settingsFile: 'C:\\ChairPlay\\settings.properties',
    },
    ...overrides,
  }
}

/** [state] with the actions in [keys] bound to other keys; null leaves one unbound. */
export function withKeys(state: AppState, keys: Record<string, string | null>): AppState {
  return { ...state, controls: { ...state.controls!, keys: { ...state.controls!.keys, ...keys } } }
}

/** ControlAction's settings and default keys, in its order. */
const DEFAULT_KEYS: [string, string[]][] = [
  ['key.knob.left', ['Left']],
  ['key.knob.right', ['Right']],
  ['key.knob.up', ['Up']],
  ['key.knob.down', ['Down']],
  ['key.knob.next', ['Tab']],
  ['key.knob.previous', ['Shift+Tab']],
  ['key.select', ['Enter', 'KP_Enter', 'Space']],
  ['key.back', ['Escape', 'Backspace']],
  ['key.home', ['Home']],
  ['key.media.previous', ['F7']],
  ['key.media.playpause', ['F8']],
  ['key.media.next', ['F9']],
  ['key.call.answer', ['F3']],
  ['key.call.end', ['F4']],
  ['key.siri', ['S']],
  ['key.reconnect', ['Ctrl+R']],
  ['key.screenshot', ['F12']],
  ['key.altscreen.content', ['F11']],
  ['key.altscreen.zoom.in', ['Equal', 'Shift+Equal', 'KP_Add']],
  ['key.altscreen.zoom.out', ['Minus', 'KP_Subtract']],
  ['key.altscreen.cards', ['Shift+F11']],
  ['key.volume.up', ['Ctrl+Up']],
  ['key.volume.down', ['Ctrl+Down']],
  ['key.volume.mute', ['Ctrl+M']],
  ['key.drive', ['F10']],
]

/** The bindings as a fresh controls.properties has them. */
export function controlsData(): ControlsData {
  return {
    actions: Object.fromEntries(
      DEFAULT_KEYS.map(([key, keys]) => [
        key,
        {
          keys: [...keys],
          defaults: [...keys],
          fixed: key === 'key.back' ? ['Escape'] : key === 'key.select' ? ['Enter'] : [],
        },
      ]),
    ),
    settings: {
      'wheel.action': 'knob',
      'wheel.invert': 'false',
      'wheel.notches.per.step': '1',
      'key.repeat.every': '3',
      'key.zoom.repeat.every': '10',
    },
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
      'log.carplay.data': 'restart',
    },
    display: { width: 1280, height: 720 },
    audio: { outputs: ['Speakers (Realtek Audio)', 'Headphones (USB Audio)'], inputs: ['Microphone (Realtek Audio)'] },
  }

  async settings() {
    return this.data
  }

  /** The password settings.properties holds, which only the eye asks for. */
  passphrase: string | null = 'secret123'

  async savedPassphrase() {
    return this.passphrase
  }

  /** The HUD demos asked for, null for a stop. */
  demos: (DemoKind | null)[] = []

  async demo(kind: DemoKind | null) {
    this.demos.push(kind)
    return true
  }

  /** The volume levels the Audio page set. */
  volumes: number[] = []

  async volume(level: number) {
    this.volumes.push(level)
    return true
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

  /** Keys handed on from a page, as "name phase settings" or "name phase everything". */
  keys: string[] = []

  async key(name: string, phase: KeyPhase, everything: boolean) {
    this.keys.push(`${name} ${phase} ${everything ? 'everything' : 'settings'}`)
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

  framesShown = 0

  /** The views whose frames were asked for, by Show frame under View areas. */
  viewFrames: string[] = []

  async showAltscreenFrame(view?: string) {
    if (view) this.viewFrames.push(view)
    else this.framesShown++
    return true
  }

  forgets = 0

  async forget() {
    this.forgets++
    return true
  }

  firewallRules = 0
  consent = true

  async addFirewallRule() {
    this.firewallRules++
    return this.consent
  }

  /** The windows Actual size was asked of, in order. */
  actualSized: PictureWindow[] = []

  async actualSize(window: PictureWindow) {
    this.actualSized.push(window)
    return true
  }

  bindings: ControlsData | null = controlsData()
  controlsSaved: Record<string, string>[] = []
  nextControlsOutcome: SaveOutcome | null = null

  async keyBindings() {
    return this.bindings && structuredClone(this.bindings)
  }

  /** Saves as the app does: a key taken from another action leaves it. */
  async saveKeyBindings(changes: Record<string, string>): Promise<SaveOutcome> {
    this.controlsSaved.push(changes)
    const outcome = this.nextControlsOutcome
    this.nextControlsOutcome = null
    if (outcome) return outcome
    const data = this.bindings!
    const applies: Record<string, 'now'> = {}
    for (const [key, value] of Object.entries(changes)) {
      applies[key] = 'now'
      if (!data.actions[key]) {
        data.settings[key] = value
        continue
      }
      const keys = value
        .split(',')
        .map((name) => name.trim())
        .filter(Boolean)
      data.actions[key].keys = keys
      for (const [other, action] of Object.entries(data.actions)) {
        if (other === key || !action.keys.some((name) => keys.includes(name))) continue
        action.keys = action.keys.filter((name) => !keys.includes(name))
        applies[other] = 'now'
      }
    }
    return { ok: true, applies }
  }
}

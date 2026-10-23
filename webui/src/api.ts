// The app's API (UiServer.kt): JSON over HTTP on 127.0.0.1, with the token from the page's own address.

export type Phase = 'setup' | 'idle' | 'starting' | 'calling' | 'joining' | 'active' | 'retrying'
export type Screen = 'start' | 'settings' | 'carplay'
/** Whether Windows Firewall lets the iPhone in; 'off' when it is off for the networks the PC is on. */
export type Firewall = 'present' | 'missing' | 'blocked' | 'unknown' | 'off'
export type Applies = 'now' | 'reconnect' | 'restart'
/** The app's windows besides the main one: the AltScreen and the on-screen car controls. */
export type WindowName = 'altscreen' | 'controls'
/** A key going down, repeating while held, or coming up. */
export type KeyPhase = 'press' | 'repeat' | 'release'

/** What the AltScreen shows (AltscreenContent in DesktopConfig.kt). */
export type AltscreenContent = 'hud' | 'map' | 'map-card' | 'card'

/** The Windows pages, folder, and web pages a link can open (UiController.PLACES). */
/** The HUD's demos the Experiments page plays: a made-up route, and the lanes switching under one maneuver. */
export type DemoKind = 'maneuvers' | 'lanes'

export type WindowsPlace =
  | 'bluetooth' | 'wifi' | 'firewall' | 'firewall-rules' | 'mfi-folder'
  | 'issues' | 'discussions' | 'pull-requests' | 'author' | 'source' | 'releases' | 'notices' | 'diplay' | 'rgi'
  | 'logs' | 'screenshots'

/** What the controls window shows beside its buttons (UiControls in Main.kt). */
export interface ControlsState {
  /** Each action's first key as controls.properties names it, by its name there without "key."; null when unbound. */
  keys: Record<string, string | null>
  volume: { level: number; muted: boolean }
  /** What the AltScreen shows; null without an AltScreen. */
  altscreen: AltscreenContent | null
  /** Alt is held in one of ChairPlay's windows: the buttons show their keys. */
  altHeld: boolean
  /** The iPhone plays (its now playing says so): the play/pause button shows pause. */
  playing: boolean
}

/** A window's client area, the picture it shows, and the smallest it gets, in screen pixels, for Actual size. */
export interface WindowSizes {
  width: number
  height: number
  pictureWidth: number
  pictureHeight: number
  minWidth: number
  minHeight: number
}

/** A window that has an Actual size. */
export type PictureWindow = 'main' | 'altscreen'

/** A commit by its short id and the day it was made. */
export interface BuildCommit {
  commit: string
  date: string
}

/** A window is available while its feature is on; only then can it be shown or hidden. */
export interface WindowState {
  available: boolean
  visible: boolean
}

export interface AppState {
  version: number
  phase: Phase
  screen: Screen
  error: { message: string; at: string } | null
  knownIphone: boolean
  canShowCarPlay: boolean
  autoshow: boolean
  /** The app connects on its own (ui.autoconnect, or a session lost from CarPlay): the page waits, black, until it fails. */
  waiting: boolean
  /** The HUD's demos on the AltScreen (Experiments): the one playing, and whether one can (the AltScreen is on). */
  demo: { playing: DemoKind | null; available: boolean } | null
  /** The page is inside ChairPlay's window rather than a browser. */
  embedded: boolean
  wifi: { ssid: string; security: string; passphraseSet: boolean }
  reconnectPending: string[]
  restartPending: string[]
  windows: Record<WindowName, WindowState>
  /**
   * The main window's client area, the picture's size, and the smallest the window gets (Windows' display scale grows
   * it), in screen pixels; null without a main window.
   */
  mainWindow: WindowSizes | null
  /** The AltScreen's window, the same way; null without an AltScreen. */
  altscreenWindow: WindowSizes | null
  /** Null when the app has no controls window. */
  controls: ControlsState | null
  /** The controls window shows each button's key always, not only while Alt is held (ui.controls.keys). */
  controlsKeys: boolean
  /** Get started is done: an iPhone has connected since the last Forget this iPhone. */
  setupDone: boolean
  /** ui.accent, #rrggbb: the colour every accent shade of the pages is made from. */
  accent: string
  /** Which build this is: this repository's commit and the DiPlay core's; null for one the build did not record. */
  about: {
    app: BuildCommit | null
    core: BuildCommit | null
    /** What a report needs, for Copy details and New issue: the build, the core, and Windows. */
    details: string
  }
  setup: {
    mfiFolder: string
    mfiPresent: boolean
    firewall: Firewall
    firewallCommands: string
    /** The paired iPhone's name, as Windows lists it; null when none is paired. Read again every few seconds. */
    pairedIphone: string | null
    /** The Wi-Fi network this PC is on; null when it is on none. */
    pcWifi: string | null
    settingsFile: string
  }
}

export interface SettingsData {
  /** Every setting the screens change, except the Wi-Fi password, which never leaves the app. */
  values: Record<string, string>
  passphraseSet: boolean
  applies: Record<string, Applies>
  display: { width: number; height: number }
  /** The sound devices Java Sound lists, by name; empty when it fails. */
  audio: { outputs: string[]; inputs: string[] }
}

/** One action of controls.properties, by its setting ("key.home"): its keys as the file names them, in order. */
export interface ActionKeys {
  keys: string[]
  defaults: string[]
  /** The keys it has whatever the file says: Escape for Back, Enter for Select. */
  fixed: string[]
}

/** The Keyboard page's data (GET /api/controls): the actions in the file's order, then the wheel and repeat. */
export interface ControlsData {
  actions: Record<string, ActionKeys>
  settings: Record<string, string>
}

export type SaveOutcome =
  | { ok: true; applies: Record<string, Applies> }
  | { ok: false; errors: Record<string, string> }

/** What the screens need from the app; tests pass a fake. */
export interface Backend {
  settings(): Promise<SettingsData>
  save(changes: Record<string, string>): Promise<SaveOutcome>
  /** The saved Wi-Fi password, for the eye beside it; null when none is saved. Nothing else carries it. */
  savedPassphrase(): Promise<string | null>
  /** Plays a HUD demo on the AltScreen, or stops it (null); false when none can play. */
  demo(kind: DemoKind | null): Promise<boolean>
  /** Sets the app's volume to [level] percent, unmuting it. */
  volume(level: number): Promise<boolean>
  /** Null when connecting started, else why not. */
  connect(): Promise<string | null>
  reconnect(): Promise<void>
  /** Ends the session or the bring-up; the app then waits for Connect. */
  disconnect(): Promise<void>
  show(screen: Screen): Promise<boolean>
  /** Shows or hides a window; false when it is not available. Its new state comes with the next app state. */
  window(name: WindowName, visible: boolean): Promise<boolean>
  /** Presses ([down]) or releases a controls window button: a key of controls.properties without "key.". */
  control(action: string, down: boolean): Promise<void>
  /**
   * Hands a key pressed on the page on to the app, as pressed on the window: its name as controls.properties writes
   * it. [everything] over the controls window; over the settings the app takes only its own keys (F11, F12 and such).
   */
  key(name: string, phase: KeyPhase, everything: boolean): Promise<void>
  /** Shows [content] on the AltScreen; false when it cannot be shown now. */
  altscreen(content: AltscreenContent): Promise<boolean>
  /** Opens a Windows page or the MFi folder; false when Windows did not open it. */
  open(place: WindowsPlace): Promise<boolean>
  /** Resizes [window] to one screen pixel per picture pixel; false when there is no such window. */
  actualSize(window: PictureWindow): Promise<boolean>
  /** Adds the firewall rule with the consent Windows asks for; false when it was not given or the rule failed. */
  addFirewallRule(): Promise<boolean>
  /** Forget this iPhone: ChairPlay's pairing records go, and Get started opens. */
  forget(): Promise<boolean>
  /** Frames the AltScreen's saved safe area on its window for a moment; false without an AltScreen. */
  showAltscreenFrame(): Promise<boolean>
  /** The key bindings; null when the app has none to change. */
  keyBindings(): Promise<ControlsData | null>
  /** Saves controls.properties changes; the actions a key moved from are among those saved. */
  saveKeyBindings(changes: Record<string, string>): Promise<SaveOutcome>
}

export class ApiError extends Error {}

export class Api implements Backend {
  constructor(private readonly token: string) {}

  private async request(method: 'GET' | 'POST', path: string, body?: unknown): Promise<{ status: number; data: any }> {
    const response = await fetch(`/api/${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${this.token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const data = await response.json().catch(() => ({}))
    if (response.status === 401) throw new ApiError('Token refused. Open the address printed in the console again.')
    return { status: response.status, data }
  }

  async state(): Promise<AppState> {
    return (await this.request('GET', 'state')).data as AppState
  }

  async settings(): Promise<SettingsData> {
    return (await this.request('GET', 'settings')).data as SettingsData
  }

  async save(changes: Record<string, string>): Promise<SaveOutcome> {
    return this.saveTo('settings', changes)
  }

  async savedPassphrase(): Promise<string | null> {
    return ((await this.request('GET', 'passphrase')).data as { passphrase: string | null }).passphrase
  }

  async demo(kind: DemoKind | null): Promise<boolean> {
    return (await this.request('POST', 'demo', { kind })).status === 200
  }

  async volume(level: number): Promise<boolean> {
    return (await this.request('POST', 'volume', { level })).status === 200
  }

  async showAltscreenFrame(): Promise<boolean> {
    return (await this.request('POST', 'altscreen-frame', {})).status === 200
  }

  async forget(): Promise<boolean> {
    return (await this.request('POST', 'forget', {})).status === 200
  }

  async addFirewallRule(): Promise<boolean> {
    return (await this.request('POST', 'firewall-rule', {})).status === 200
  }

  async keyBindings(): Promise<ControlsData | null> {
    const { status, data } = await this.request('GET', 'controls')
    return status === 200 ? (data as ControlsData) : null
  }

  async saveKeyBindings(changes: Record<string, string>): Promise<SaveOutcome> {
    return this.saveTo('controls', changes)
  }

  private async saveTo(path: 'settings' | 'controls', changes: Record<string, string>): Promise<SaveOutcome> {
    const { status, data } = await this.request('POST', path, { changes })
    if (status === 200) return { ok: true, applies: data.applies ?? {} }
    if (status === 400 && data.errors) return { ok: false, errors: data.errors }
    throw new ApiError(data.error ?? `saving failed (${status})`)
  }

  async connect(): Promise<string | null> {
    const { status, data } = await this.request('POST', 'connect', {})
    return status === 200 ? null : (data.error ?? `failed (${status})`)
  }

  async reconnect(): Promise<void> {
    await this.request('POST', 'reconnect', {})
  }

  async disconnect(): Promise<void> {
    await this.request('POST', 'disconnect', {})
  }

  async show(screen: Screen): Promise<boolean> {
    return (await this.request('POST', 'screen', { screen })).status === 200
  }

  async window(name: WindowName, visible: boolean): Promise<boolean> {
    return (await this.request('POST', 'window', { name, visible })).status === 200
  }

  async control(action: string, down: boolean): Promise<void> {
    await this.request('POST', 'control', { action, down })
  }

  async key(name: string, phase: KeyPhase, everything: boolean): Promise<void> {
    await this.request('POST', 'key', { name, phase, everything })
  }

  async altscreen(content: AltscreenContent): Promise<boolean> {
    return (await this.request('POST', 'altscreen', { content })).status === 200
  }

  async open(place: WindowsPlace): Promise<boolean> {
    return (await this.request('POST', 'open', { place })).status === 200
  }

  async actualSize(window: PictureWindow): Promise<boolean> {
    return (await this.request('POST', 'window-size', { actual: true, window })).status === 200
  }

  /**
   * Calls [onState] with the state now and on every change, over server-sent events; while those fail (the app is
   * gone or restarting) it polls instead. Returns a function that stops it.
   */
  subscribe(onState: (state: AppState) => void, onLost: (lost: boolean) => void): () => void {
    let source: EventSource | null = null
    let poll: number | undefined
    let stopped = false
    const open = () => {
      source = new EventSource(`/api/events?token=${encodeURIComponent(this.token)}`)
      source.onmessage = (event) => {
        onLost(false)
        onState(JSON.parse(event.data) as AppState)
      }
      source.onerror = () => {
        onLost(true)
        source?.close()
        if (!stopped) poll = window.setTimeout(retry, 2000)
      }
    }
    const retry = async () => {
      try {
        onState(await this.state())
        onLost(false)
        open()
      } catch {
        if (!stopped) poll = window.setTimeout(retry, 2000)
      }
    }
    open()
    return () => {
      stopped = true
      source?.close()
      window.clearTimeout(poll)
    }
  }
}

/** The token in the page's address (?token=...). */
export function tokenFrom(search: string): string | null {
  return new URLSearchParams(search).get('token')
}

// The app's API (UiServer.kt): JSON over HTTP on 127.0.0.1, with the token from the page's own address.

export type Phase = 'setup' | 'idle' | 'starting' | 'calling' | 'joining' | 'active' | 'retrying'
export type Screen = 'start' | 'settings' | 'carplay'
export type Firewall = 'present' | 'missing' | 'blocked' | 'unknown'
export type Applies = 'now' | 'reconnect' | 'restart'
/** The app's windows besides the main one: the AltScreen and the on-screen car controls. */
export type WindowName = 'altscreen' | 'controls'

/** What the AltScreen shows (AltscreenContent in DesktopConfig.kt). */
export type AltscreenContent = 'hud' | 'map' | 'map-card' | 'card'

/** The Windows pages and folder a link can open (UiController.PLACES). */
export type WindowsPlace = 'bluetooth' | 'wifi' | 'firewall' | 'firewall-rules' | 'mfi-folder'

/** What the controls window shows beside its buttons (UiControls in Main.kt). */
export interface ControlsState {
  /** Each action's first key as controls.properties names it, by its name there without "key."; null when unbound. */
  keys: Record<string, string | null>
  volume: { level: number; muted: boolean }
  /** What the AltScreen shows; null without an AltScreen. */
  altscreen: AltscreenContent | null
  /** Alt is held in one of ChairPlay's windows: the buttons show their keys. */
  altHeld: boolean
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
  /** The page is inside ChairPlay's window rather than a browser. */
  embedded: boolean
  wifi: { ssid: string; security: string; passphraseSet: boolean }
  reconnectPending: string[]
  restartPending: string[]
  windows: Record<WindowName, WindowState>
  /** The main window's client area and the picture's size, in screen pixels; null without a main window. */
  mainWindow: { width: number; height: number; pictureWidth: number; pictureHeight: number } | null
  /** Null when the app has no controls window. */
  controls: ControlsState | null
  setup: {
    mfiFolder: string
    mfiPresent: boolean
    firewall: Firewall
    firewallCommands: string
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

export type SaveOutcome =
  | { ok: true; applies: Record<string, Applies> }
  | { ok: false; errors: Record<string, string> }

/** What the screens need from the app; tests pass a fake. */
export interface Backend {
  settings(): Promise<SettingsData>
  save(changes: Record<string, string>): Promise<SaveOutcome>
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
  /** Shows [content] on the AltScreen; false when it cannot be shown now. */
  altscreen(content: AltscreenContent): Promise<boolean>
  /** Opens a Windows page or the MFi folder; false when Windows did not open it. */
  open(place: WindowsPlace): Promise<boolean>
  /** Resizes the main window to one screen pixel per picture pixel; false without a main window. */
  actualSize(): Promise<boolean>
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
    const { status, data } = await this.request('POST', 'settings', { changes })
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

  async altscreen(content: AltscreenContent): Promise<boolean> {
    return (await this.request('POST', 'altscreen', { content })).status === 200
  }

  async open(place: WindowsPlace): Promise<boolean> {
    return (await this.request('POST', 'open', { place })).status === 200
  }

  async actualSize(): Promise<boolean> {
    return (await this.request('POST', 'window-size', { actual: true })).status === 200
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

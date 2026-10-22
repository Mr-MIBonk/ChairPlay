// The app's API (UiServer.kt): JSON over HTTP on 127.0.0.1, with the token from the page's own address.

export type Phase = 'setup' | 'idle' | 'starting' | 'calling' | 'joining' | 'active' | 'retrying'
export type Screen = 'start' | 'settings' | 'carplay'
export type Firewall = 'present' | 'missing' | 'blocked' | 'unknown'
export type Applies = 'now' | 'reconnect' | 'restart'

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
  show(screen: Screen): Promise<boolean>
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

  async show(screen: Screen): Promise<boolean> {
    return (await this.request('POST', 'screen', { screen })).status === 200
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

import { useEffect, useId, useState, type FormEvent, type ReactNode } from 'react'
import type { Applies, AppState, Backend, SettingsData, WindowState } from './api'
import { appliesText, CarPlayButton, Card, FieldError, Note, SwitchRow } from './components'

/** The names the screen gives the settings it changes, for the lists of changes waiting for a restart. */
export const LABELS: Record<string, string> = {
  'ui.autoconnect': 'Connect automatically',
  'ui.autoshow': 'Show CarPlay automatically',
  'altscreen.enabled': 'AltScreen',
  'altscreen.width': 'AltScreen size',
  'altscreen.height': 'AltScreen size',
  'window.width': 'Main window size',
  'window.height': 'Main window size',
}

/** The sections the sidebar lists, in its order; the first is shown when the screen opens. */
const SECTIONS = [
  { id: 'connection', title: 'Connection' },
  { id: 'altscreen', title: 'AltScreen' },
  { id: 'window', title: 'Main window' },
] as const

type Section = (typeof SECTIONS)[number]['id']

/** Opened from the start screen, and by the car button on the CarPlay home screen. */
export function SettingsScreen({ state, backend }: { state: AppState; backend: Backend }) {
  const [settings, setSettings] = useState<SettingsData | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [section, setSection] = useState<Section>(SECTIONS[0].id)
  const ids = useId()
  const toCarPlay = state.canShowCarPlay && state.embedded

  useEffect(() => {
    backend.settings().then(setSettings, (error) => setLoadError(String(error)))
  }, [backend])

  // Escape goes back, like the back button.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') backend.show(toCarPlay ? 'carplay' : 'start')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [backend, toCarPlay])

  const restart = [...new Set(state.restartPending.map((key) => LABELS[key] ?? key))]

  return (
    <div className="screen">
      <header className="topbar">
        {toCarPlay ? (
          <CarPlayButton onClick={() => backend.show('carplay')} />
        ) : (
          <button type="button" className="button-back" onClick={() => backend.show('start')}>
            ‹ Back
          </button>
        )}
        <h1 className="topbar-title">Settings</h1>
        <span className="topbar-spacer" />
      </header>

      {restart.length > 0 && <Note tone="warn">Applies after restart: {restart.join(', ')}.</Note>}
      {state.reconnectPending.length > 0 && (
        <Note>
          The Wi-Fi change applies with the next connection.{' '}
          <button type="button" className="link" onClick={() => backend.reconnect()}>
            Reconnect now
          </button>
        </Note>
      )}
      {loadError && <Note tone="warn">{loadError}</Note>}

      <div className="settings-layout">
        <nav className="sidebar" aria-label="Settings">
          <div className="window-toggles">
            <WindowToggle
              label="AltScreen"
              icon={<AltScreenIcon />}
              window={state.windows.altscreen}
              onToggle={(visible) => backend.window('altscreen', visible)}
            />
            <WindowToggle
              label="Controls"
              icon={<ControlsIcon />}
              window={state.windows.controls}
              onToggle={(visible) => backend.window('controls', visible)}
            />
          </div>
          <div className="section-list" role="tablist" aria-orientation="vertical">
            {SECTIONS.map(({ id, title }) => (
              <button
                key={id}
                type="button"
                role="tab"
                id={`${ids}-tab-${id}`}
                aria-controls={`${ids}-panel-${id}`}
                aria-selected={section === id}
                className="section-tab"
                onClick={() => setSection(id)}
              >
                {title}
              </button>
            ))}
          </div>
        </nav>
        {settings && <SettingsForms settings={settings} backend={backend} onSaved={setSettings} section={section} ids={ids} />}
      </div>
    </div>
  )
}

/**
 * Shows or hides one of the app's other windows: pressed while it is shown, disabled while its feature is off. It
 * changes only when the app's state says the window did, not on the click.
 */
function WindowToggle(props: { label: string; icon: ReactNode; window: WindowState; onToggle: (visible: boolean) => void }) {
  const { available, visible } = props.window
  const shown = available && visible
  return (
    <button
      type="button"
      className="window-toggle"
      aria-pressed={shown}
      disabled={!available}
      onClick={() => props.onToggle(!shown)}
    >
      <span className="window-toggle-icon">{props.icon}</span>
      {props.label}
    </button>
  )
}

/** A wide screen with two dials, like an instrument cluster. */
function AltScreenIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <rect x="2" y="6" width="20" height="12" rx="3" />
      <path d="M5.5 14.5a2.5 2.5 0 0 1 5 0M13.5 14.5a2.5 2.5 0 0 1 5 0" />
    </svg>
  )
}

/** A rotary knob with arrows on four sides, like a car's controller. */
function ControlsIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <circle cx="12" cy="12" r="3" fill="currentColor" />
      <path d="M12 5.2l1.7 2h-3.4zM12 18.8l1.7-2h-3.4zM5.2 12l2-1.7v3.4zM18.8 12l-2-1.7v3.4z" fill="currentColor" />
    </svg>
  )
}

/** All sections stay mounted, the others hidden, so a size typed but not saved survives a look elsewhere. */
function SettingsForms({
  settings,
  backend,
  onSaved,
  section,
  ids,
}: {
  settings: SettingsData
  backend: Backend
  onSaved: (settings: SettingsData) => void
  section: Section
  /** The prefix of the tabs' and panels' ids. */
  ids: string
}) {
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [errors, setErrors] = useState<Record<string, string>>({})

  /** Saves [changes]; [group] names where the outcome is shown. */
  async function save(group: string, changes: Record<string, string>): Promise<boolean> {
    try {
      const outcome = await backend.save(changes)
      if (!outcome.ok) {
        setErrors({ ...errors, ...outcome.errors })
        setNotes({ ...notes, [group]: '' })
        return false
      }
      const cleared = { ...errors }
      Object.keys(changes).forEach((key) => delete cleared[key])
      setErrors(cleared)
      setNotes({ ...notes, [group]: appliesText(Object.values(outcome.applies) as Applies[]) })
      onSaved({ ...settings, values: { ...settings.values, ...changes } })
      return true
    } catch (error) {
      setNotes({ ...notes, [group]: error instanceof Error ? error.message : String(error) })
      return false
    }
  }

  const value = (key: string) => settings.values[key] ?? ''
  const toggle = (group: string, key: string) => (checked: boolean) => save(group, { [key]: String(checked) })
  const panel = { selected: section, ids }

  return (
    <div className="settings-pane">
      <Panel id="connection" {...panel}>
        <SwitchRow
          label="Connect automatically"
          detail="At start."
          checked={value('ui.autoconnect') !== 'false'}
          onChange={toggle('connection', 'ui.autoconnect')}
        />
        <SwitchRow
          label="Show CarPlay automatically"
          detail="When the iPhone connects."
          checked={value('ui.autoshow') !== 'false'}
          onChange={toggle('connection', 'ui.autoshow')}
        />
        {notes.connection && <Note tone="ok">{notes.connection}</Note>}
      </Panel>

      <Panel id="altscreen" {...panel}>
        <SwitchRow
          label="AltScreen"
          detail="Instrument cluster screen, in its own window."
          checked={value('altscreen.enabled') === 'true'}
          onChange={toggle('altscreen', 'altscreen.enabled')}
        />
        <SizeForm
          label="Size"
          name="AltScreen"
          keys={['altscreen.width', 'altscreen.height']}
          values={[value('altscreen.width'), value('altscreen.height')]}
          placeholders={['1440', '540']}
          help="Pixels, even numbers."
          errors={errors}
          onSave={(changes) => save('altscreen', changes)}
        />
        {notes.altscreen && <Note tone="ok">{notes.altscreen}</Note>}
      </Panel>

      <Panel id="window" {...panel}>
        <SizeForm
          label="Size"
          name="Main window"
          keys={['window.width', 'window.height']}
          values={[value('window.width'), value('window.height')]}
          placeholders={[String(settings.display.width), String(settings.display.height)]}
          help="Pixels."
          errors={errors}
          onSave={(changes) => save('window', changes)}
        />
        {notes.window && <Note tone="ok">{notes.window}</Note>}
      </Panel>
    </div>
  )
}

/** One section's content, shown while its tab is selected. */
function Panel({ id, selected, ids, children }: { id: Section; selected: Section; ids: string; children: ReactNode }) {
  return (
    <div role="tabpanel" id={`${ids}-panel-${id}`} aria-labelledby={`${ids}-tab-${id}`} hidden={selected !== id}>
      <Card>{children}</Card>
    </div>
  )
}

function SizeForm(props: {
  label: string
  /** What the size is of, for screen readers and tests. */
  name: string
  keys: [string, string]
  values: [string, string]
  placeholders: [string, string]
  help: string
  errors: Record<string, string>
  onSave: (changes: Record<string, string>) => Promise<boolean>
}) {
  const [width, setWidth] = useState(props.values[0])
  const [height, setHeight] = useState(props.values[1])
  const id = useId()
  const changed = width !== props.values[0] || height !== props.values[1]

  async function submit(event: FormEvent) {
    event.preventDefault()
    const changes: Record<string, string> = {}
    if (width !== props.values[0]) changes[props.keys[0]] = width
    if (height !== props.values[1]) changes[props.keys[1]] = height
    await props.onSave(changes)
  }

  return (
    <form className="size-form" onSubmit={submit} noValidate>
      <label htmlFor={`${id}-w`} className="row-label">
        {props.label}
      </label>
      <div className="size-inputs">
        <input
          id={`${id}-w`}
          aria-label={`${props.name} width`}
          inputMode="numeric"
          value={width}
          placeholder={props.placeholders[0]}
          onChange={(event) => setWidth(event.target.value)}
        />
        <span className="times">×</span>
        <input
          aria-label={`${props.name} height`}
          inputMode="numeric"
          value={height}
          placeholder={props.placeholders[1]}
          onChange={(event) => setHeight(event.target.value)}
        />
        <button type="submit" className="button" disabled={!changed}>
          Save
        </button>
      </div>
      <div className="row-detail">{props.help}</div>
      <FieldError message={props.errors[props.keys[0]]} />
      <FieldError message={props.errors[props.keys[1]]} />
    </form>
  )
}

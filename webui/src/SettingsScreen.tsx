import { useEffect, useId, useState, type FormEvent } from 'react'
import type { Applies, AppState, Backend, SettingsData } from './api'
import { appliesText, Card, FieldError, Note, SwitchRow } from './components'

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

/** Opened from the start screen, and by the car button on the CarPlay home screen. */
export function SettingsScreen({ state, backend }: { state: AppState; backend: Backend }) {
  const [settings, setSettings] = useState<SettingsData | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
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
        <button type="button" className="button-back" onClick={() => backend.show(toCarPlay ? 'carplay' : 'start')}>
          ‹ {toCarPlay ? 'CarPlay' : 'Back'}
        </button>
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
      {settings && <SettingsForms settings={settings} backend={backend} onSaved={setSettings} />}
    </div>
  )
}

function SettingsForms({
  settings,
  backend,
  onSaved,
}: {
  settings: SettingsData
  backend: Backend
  onSaved: (settings: SettingsData) => void
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

  return (
    <>
      <Card title="Connection">
        <SwitchRow
          label="Connect automatically"
          detail="At start. Off: press Connect."
          checked={value('ui.autoconnect') !== 'false'}
          onChange={toggle('connection', 'ui.autoconnect')}
        />
        <SwitchRow
          label="Show CarPlay automatically"
          detail="When the iPhone connects. Off: press Show CarPlay."
          checked={value('ui.autoshow') !== 'false'}
          onChange={toggle('connection', 'ui.autoshow')}
        />
        {notes.connection && <Note tone="ok">{notes.connection}</Note>}
      </Card>

      <Card title="AltScreen">
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
          placeholders={['1440', '542']}
          help="Pixels, even numbers."
          errors={errors}
          onSave={(changes) => save('altscreen', changes)}
        />
        {notes.altscreen && <Note tone="ok">{notes.altscreen}</Note>}
      </Card>

      <Card title="Main window">
        <SizeForm
          label="Size"
          name="Main window"
          keys={['window.width', 'window.height']}
          values={[value('window.width'), value('window.height')]}
          placeholders={[String(settings.display.width), String(settings.display.height)]}
          help={`Pixels. Blank: the CarPlay screen's ${settings.display.width} × ${settings.display.height}.`}
          errors={errors}
          onSave={(changes) => save('window', changes)}
        />
        {notes.window && <Note tone="ok">{notes.window}</Note>}
      </Card>
    </>
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

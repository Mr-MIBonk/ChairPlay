import { useEffect, useId, useState, type FormEvent } from 'react'
import type { AppState, Backend } from './api'
import { appliesText, FieldError, Note } from './components'

export const SECURITY_CHOICES: [string, string][] = [
  ['WPA_WPA2', 'WPA2 (most networks)'],
  ['WPA3_TRANSITION', 'WPA2 / WPA3'],
  ['WPA3_ONLY', 'WPA3 only'],
  ['WEP', 'WEP'],
  ['NONE', 'Open (no password)'],
]

/**
 * The Wi-Fi network the PC and the iPhone share. The stored password is never shown or sent back: the field
 * starts empty and says whether one is set, and only a typed password is saved.
 */
export function WifiForm({ wifi, backend, running }: { wifi: AppState['wifi']; backend: Backend; running: boolean }) {
  const [ssid, setSsid] = useState(wifi.ssid)
  const [security, setSecurity] = useState(wifi.security)
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [touched, setTouched] = useState(false)
  const ids = { ssid: useId(), password: useId(), security: useId() }

  // Follows the app while the form is untouched, as when the settings file changes or another window saves.
  useEffect(() => {
    if (!touched) {
      setSsid(wifi.ssid)
      setSecurity(wifi.security)
    }
  }, [wifi.ssid, wifi.security, touched])

  const changes: Record<string, string> = {}
  if (ssid !== wifi.ssid) changes['wifi.ssid'] = ssid
  if (security !== wifi.security) changes['wifi.security'] = security
  if (password !== '') changes['wifi.passphrase'] = password
  const changed = Object.keys(changes).length > 0

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!changed) return
    setBusy(true)
    setNote(null)
    try {
      const outcome = await backend.save(changes)
      if (outcome.ok) {
        setErrors({})
        setPassword('')
        setTouched(false)
        setNote(running ? appliesText(Object.values(outcome.applies)) : 'Saved.')
      } else {
        setErrors(outcome.errors)
      }
    } catch (error) {
      setErrors({ form: error instanceof Error ? error.message : String(error) })
    } finally {
      setBusy(false)
    }
  }

  const edit = (apply: () => void) => {
    apply()
    setTouched(true)
    setNote(null)
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <div className="field">
        <label htmlFor={ids.ssid}>Network name</label>
        <input
          id={ids.ssid}
          value={ssid}
          autoComplete="off"
          spellCheck={false}
          placeholder="The Wi-Fi network's name (SSID)"
          onChange={(event) => edit(() => setSsid(event.target.value))}
        />
        <FieldError message={errors['wifi.ssid']} />
      </div>
      <div className="field">
        <label htmlFor={ids.password}>Password</label>
        <div className="input-with-button">
          <input
            id={ids.password}
            type={reveal ? 'text' : 'password'}
            value={password}
            autoComplete="new-password"
            spellCheck={false}
            placeholder={wifi.passphraseSet ? 'Saved; type a new one to replace it' : 'Not set'}
            onChange={(event) => edit(() => setPassword(event.target.value))}
          />
          <button type="button" className="button-quiet" onClick={() => setReveal(!reveal)}>
            {reveal ? 'Hide' : 'Show'}
          </button>
        </div>
        <FieldError message={errors['wifi.passphrase']} />
      </div>
      <div className="field">
        <label htmlFor={ids.security}>Security</label>
        <select id={ids.security} value={security} onChange={(event) => edit(() => setSecurity(event.target.value))}>
          {SECURITY_CHOICES.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <FieldError message={errors['wifi.security']} />
      </div>
      <FieldError message={errors.form} />
      <div className="actions">
        <button type="submit" className="button" disabled={!changed || busy}>
          Save Wi-Fi
        </button>
        {note && <Note tone="ok">{note}</Note>}
      </div>
    </form>
  )
}

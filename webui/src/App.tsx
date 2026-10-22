import { useEffect, useMemo, useState } from 'react'
import { Api, tokenFrom, type AppState } from './api'
import { Note } from './components'
import { SettingsScreen } from './SettingsScreen'
import { StartScreen } from './StartScreen'

export function App() {
  const token = tokenFrom(window.location.search)
  const api = useMemo(() => (token ? new Api(token) : null), [token])
  const [state, setState] = useState<AppState | null>(null)
  const [lost, setLost] = useState(false)

  useEffect(() => (api ? api.subscribe(setState, setLost) : undefined), [api])

  if (!api) {
    return (
      <div className="screen centered">
        <h1 className="brand">ChairPlay</h1>
        <p className="muted">Open the address ChairPlay writes to its console as it starts; it carries the key to this page.</p>
      </div>
    )
  }
  if (!state) {
    return (
      <div className="screen centered">
        <h1 className="brand">ChairPlay</h1>
        <p className="muted">{lost ? 'ChairPlay is not answering.' : 'Loading…'}</p>
      </div>
    )
  }
  return (
    <>
      {lost && (
        <div className="lost">
          <Note tone="warn">Lost contact with ChairPlay; trying again.</Note>
        </div>
      )}
      {state.screen === 'settings' ? <SettingsScreen state={state} backend={api} /> : <StartScreen state={state} backend={api} />}
    </>
  )
}

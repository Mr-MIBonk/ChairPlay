import { useEffect, useMemo, useState } from 'react'
import { Api, tokenFrom, type AppState } from './api'
import { Note } from './components'
import { ControlsScreen } from './ControlsScreen'
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
        <p className="muted">No key in the address. Open the address printed in the console.</p>
      </div>
    )
  }
  // The controls window opens /controls; everything else is the main window's start and settings screens.
  const controlsPage = window.location.pathname === '/controls'
  if (controlsPage) {
    return state ? <ControlsScreen state={state} backend={api} /> : <div className="controls-screen" />
  }
  if (!state) {
    return (
      <div className="screen centered">
        <p className="muted">{lost ? 'The app is not answering.' : 'Loading…'}</p>
      </div>
    )
  }
  return (
    <>
      {lost && (
        <div className="lost">
          <Note tone="warn">Lost contact with the app; retrying.</Note>
        </div>
      )}
      {state.screen === 'settings' ? <SettingsScreen state={state} backend={api} /> : <StartScreen state={state} backend={api} />}
    </>
  )
}

import { useEffect, useMemo, useState } from 'react'
import { applyAccent } from './accent'
import { Api, tokenFrom, type AppState } from './api'
import { ControlsScreen } from './ControlsScreen'
import { Shell } from './shell/Shell'

export function App() {
  const token = tokenFrom(window.location.search)
  const api = useMemo(() => (token ? new Api(token) : null), [token])
  const [state, setState] = useState<AppState | null>(null)
  const [lost, setLost] = useState(false)

  useEffect(() => (api ? api.subscribe(setState, setLost) : undefined), [api])
  useEffect(() => applyAccent(state?.accent), [state?.accent])

  if (!api) {
    return <p className="app-message">No key in the address. Open the address printed in the console.</p>
  }
  // The controls window opens /controls; everything else is the main window's settings.
  const controlsPage = window.location.pathname === '/controls'
  if (controlsPage) {
    return state ? <ControlsScreen state={state} backend={api} /> : <div className="controls-screen" />
  }
  if (!state) {
    return <p className="app-message">{lost ? 'The app is not answering.' : 'Loading…'}</p>
  }
  return (
    <>
      <Shell state={state} backend={api} />
      {lost && (
        <div className="lost" role="status">
          Lost contact with the app. Trying again…
        </div>
      )}
    </>
  )
}

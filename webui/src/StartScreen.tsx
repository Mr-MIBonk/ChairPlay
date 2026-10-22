import { useState } from 'react'
import type { AppState, Backend } from './api'
import { Card, Note } from './components'
import { describe, errorHint } from './status'
import { WifiForm } from './WifiForm'

/** Shown whenever CarPlay is not: where the connection stands, the Wi-Fi network and the setup steps. */
export function StartScreen({ state, backend }: { state: AppState; backend: Backend }) {
  const status = describe(state)
  const running = state.phase !== 'setup' && state.phase !== 'idle'
  const [actionError, setActionError] = useState<string | null>(null)

  async function connect() {
    setActionError(await backend.connect())
  }

  return (
    <div className="screen">
      <header className="topbar">
        <h1 className="brand">ChairPlay</h1>
        <button type="button" className="button-quiet" onClick={() => backend.show('settings')}>
          Settings
        </button>
      </header>

      <Card className={`status status-${status.tone}`}>
        <div className="status-line">
          <span className={`dot dot-${status.tone}`} aria-hidden="true" />
          <h2 className="status-title">{status.title}</h2>
        </div>
        <p className="status-detail">{status.detail}</p>
        {state.error && state.phase !== 'active' && (
          <div className="status-error">
            <div>
              <span className="muted">Last error at {state.error.at}:</span> {state.error.message}
            </div>
            {errorHint(state.error.message, state.setup) && <div className="hint">{errorHint(state.error.message, state.setup)}</div>}
          </div>
        )}
        {actionError && <Note tone="warn">{actionError}</Note>}
        <div className="actions">
          {state.canShowCarPlay && state.embedded && (
            <button type="button" className="button" onClick={() => backend.show('carplay')}>
              Show CarPlay
            </button>
          )}
          {state.phase === 'idle' && (
            <button type="button" className="button" onClick={connect}>
              Connect
            </button>
          )}
          {running && (
            <button type="button" className="button-secondary" onClick={() => backend.reconnect()}>
              Reconnect now
            </button>
          )}
        </div>
        {state.reconnectPending.length > 0 && (
          <Note>The Wi-Fi change applies with the next connection; Reconnect now applies it at once.</Note>
        )}
      </Card>

      <div className="columns">
        <Card title="Wi-Fi network" className={state.phase === 'setup' ? 'attention' : ''}>
          <p className="card-text">The network this PC is on and the iPhone joins for CarPlay.</p>
          <WifiForm wifi={state.wifi} backend={backend} running={running} />
        </Card>
        <SetupSteps state={state} />
      </div>
    </div>
  )
}

function SetupSteps({ state }: { state: AppState }) {
  const { setup } = state
  const [copied, setCopied] = useState(false)
  async function copy() {
    await navigator.clipboard.writeText(setup.firewallCommands)
    setCopied(true)
  }
  const firewallText = {
    present: 'The rule is in place.',
    missing: 'No rule lets the iPhone in yet. Run these commands from an administrator prompt:',
    blocked:
      'A Windows Firewall rule blocks ChairPlay. Delete it, or set it to Allow, in Windows Defender Firewall › Advanced settings › Inbound Rules.',
    unknown: 'The rules could not be read. If the iPhone cannot connect, run these from an administrator prompt:',
  }[setup.firewall]
  return (
    <Card title="Setting up">
      <ol className="steps">
        <li>
          <strong>Pair the iPhone</strong> with this PC in Windows Settings › Bluetooth &amp; devices.
        </li>
        <li>
          <strong>MFi identity:</strong> put <code>identity.pk8</code> and <code>certificate.p7b</code> into{' '}
          <code className="path">{setup.mfiFolder}</code>.{' '}
          <span className={setup.mfiPresent ? 'ok' : 'warn'}>{setup.mfiPresent ? 'Found.' : 'Not found yet.'}</span>
        </li>
        <li>
          <strong>Windows Firewall:</strong>{' '}
          <span className={setup.firewall === 'present' ? 'ok' : setup.firewall === 'unknown' ? '' : 'warn'}>{firewallText}</span>
          {(setup.firewall === 'missing' || setup.firewall === 'unknown') && (
            <div className="commands">
              <pre>{setup.firewallCommands}</pre>
              <button type="button" className="button-quiet" onClick={copy}>
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          )}
        </li>
        <li>
          <strong>Accept CarPlay</strong> on the iPhone when it asks, the first time it connects.
        </li>
      </ol>
      <p className="card-text muted">
        Every setting is in <code className="path">{setup.settingsFile}</code>.
      </p>
    </Card>
  )
}

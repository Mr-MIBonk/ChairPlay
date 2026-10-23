// What is wrong, above the Connection page's settings: the last error, and the setup problems the app finds. Each
// names the problem, then what to do, then a button for whatever there is to open or copy.
import { useState, type ReactNode } from 'react'
import type { AppState, Backend } from '../api'
import { Message } from '../shell/controls'
import { ShieldIcon } from '../shell/icons'
import { errorKind, type ErrorKind } from '../status'

/** A path or command to read or copy, and its button beside it. */
function CodeLine({ text, children }: { text: string; children: ReactNode }) {
  return (
    <>
      <div className="code" title={text}>
        {text}
      </div>
      {children}
    </>
  )
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      className="btn small"
      title="Copy the command"
      onClick={async () => {
        await navigator.clipboard.writeText(text)
        setCopied(true)
      }}
    >
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

/** The bring-up's last error, until a session is active; the raw message is its tooltip. */
export function ErrorMessage({ state, backend }: { state: AppState; backend: Backend }) {
  const { error, setup } = state
  if (!error || state.phase === 'active') return null
  const kind = errorKind(error.message)
  // Files that are not there have their own message.
  if (kind === 'mfi' && !setup.mfiPresent) return null
  const messages: Record<ErrorKind, [head: string, text: string, actions: ReactNode]> = {
    join: [
      "The iPhone didn't join the Wi-Fi.",
      'It answered over Bluetooth but did not come onto the network. Check the network and the password below, and that this PC is on that network.',
      null,
    ],
    bluetooth: [
      "Couldn't reach the iPhone.",
      'No paired iPhone answered over Bluetooth. Make sure the iPhone is paired, nearby, and has Bluetooth turned on.',
      <div className="acts">
        <button type="button" className="btn small" title="Open Bluetooth settings" onClick={() => backend.open('bluetooth')}>
          Open Bluetooth settings
        </button>
      </div>,
    ],
    wifi: [
      "Couldn't open the Wi-Fi.",
      'Check the network below, and that this PC is connected to it.',
      <div className="acts">
        <button type="button" className="btn small" title="Open Wi-Fi settings" onClick={() => backend.open('wifi')}>
          Open Wi-Fi settings
        </button>
      </div>,
    ],
    mfi: [
      'The MFi files were refused.',
      'Check identity.pk8 and certificate.p7b in this folder:',
      <CodeLine text={setup.mfiFolder}>
        <OpenFolder backend={backend} />
      </CodeLine>,
    ],
    other: ["Couldn't connect.", error.message, null],
  }
  const [head, text, actions] = messages[kind]
  return (
    <Message tone="bad" head={head} text={text} at={error.at} title={error.message}>
      {actions}
    </Message>
  )
}

function OpenFolder({ backend }: { backend: Backend }) {
  return (
    <button type="button" className="btn small" title="Open the folder in File Explorer" onClick={() => backend.open('mfi-folder')}>
      Open
    </button>
  )
}

/** The setup problems the app finds: the MFi files, and the firewall; each goes once it is fixed. */
export function SetupMessages({ state, backend }: { state: AppState; backend: Backend }) {
  const { setup } = state
  return (
    <>
      {!setup.mfiPresent && (
        <Message tone="warn" head="MFi files not found." text="Put identity.pk8 and certificate.p7b in this folder:">
          <CodeLine text={setup.mfiFolder}>
            <OpenFolder backend={backend} />
          </CodeLine>
        </Message>
      )}
      {setup.firewall === 'missing' && (
        <Message
          tone="warn"
          head="The firewall blocks the iPhone."
          text="Windows Firewall drops the iPhone's connections until ChairPlay is let in. Run this in Terminal (Admin):"
        >
          <CodeLine text={setup.firewallCommands}>
            <CopyButton text={setup.firewallCommands} />
          </CodeLine>
        </Message>
      )}
      {setup.firewall === 'unknown' && (
        <Message
          tone="warn"
          head="The firewall's rules could not be read."
          text="If the iPhone cannot connect, run this in Terminal (Admin):"
        >
          <CodeLine text={setup.firewallCommands}>
            <CopyButton text={setup.firewallCommands} />
          </CodeLine>
        </Message>
      )}
      {setup.firewall === 'blocked' && (
        <Message
          tone="warn"
          head="A firewall rule blocks ChairPlay."
          text="Delete the rule, or set it to Allow, in the firewall's Inbound Rules."
        >
          <div className="acts">
            <button
              type="button"
              className="btn small"
              title="Open Windows Defender Firewall with Advanced Security"
              onClick={() => backend.open('firewall-rules')}
            >
              <ShieldIcon />
              Open the firewall's rules
            </button>
          </div>
        </Message>
      )}
    </>
  )
}

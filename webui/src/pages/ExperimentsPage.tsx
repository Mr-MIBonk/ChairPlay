import type { AppState, Backend, DemoKind } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { Changed, parts, pendingOf } from './parts'

const LEVELS: readonly Choice[] = [
  ['error', 'Errors'],
  ['warning', 'Warnings'],
  ['info', 'Info'],
  ['debug', 'Debug'],
  ['trace', 'Trace'],
]

const DEMOS: readonly (readonly [kind: DemoKind, label: string, title: string])[] = [
  ['maneuvers', 'Maneuvers', "Play a made-up route on the AltScreen's HUD"],
  ['lanes', 'Lane changes', "Switch the lanes under one maneuver on the AltScreen's HUD"],
]

/**
 * What is still being tried, and what helps to look into it: the HUD's demos, played on the AltScreen without an iPhone,
 * the app log's level, the CarPlay data log, and the folders the logs and the screenshots go to.
 */
export function ExperimentsPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const demo = state.demo
  const { note } = parts(settings)
  const screenshotKey = state.controls?.keys.screenshot
  return (
    <>
      <Group title="HUD demo" note="On the AltScreen's HUD" />
      {DEMOS.map(([kind, label, title]) => {
        const playing = demo?.playing === kind
        // No AltScreen in this connection, or one turned off on its page since: no demo starts; one playing can stop.
        const off = !demo?.available || settings.value('altscreen.enabled') !== 'true'
        return (
          <Row key={kind} label={label}>
            <button
              type="button"
              className="btn small"
              disabled={off && !playing}
              title={playing ? 'Stop the demo' : off ? 'Turn on the AltScreen on its page first' : title}
              onClick={() => backend.demo(playing ? null : kind)}
            >
              {playing ? 'Stop' : 'Play'}
            </button>
          </Row>
        )
      })}
      <Group title="Logs" note={note(['log.carplay.data'])} />
      <Row label="Log level" title="How much the app's log in the logs folder tells; Trace adds every packet">
        <Segmented
          label="Log level"
          value={settings.value('log.level') || 'info'}
          options={LEVELS}
          onChange={(level) => settings.save({ 'log.level': level })}
        />
      </Row>
      <Row label="CarPlay data" title="The iPhone's route, song, call, and battery messages, one JSON object a line, in the logs folder">
        <Checkbox
          label="Write the iPhone's messages to a file"
          checked={settings.value('log.carplay.data') === 'true'}
          onChange={(checked) => settings.save({ 'log.carplay.data': String(checked) })}
        />
        <Changed keys={['log.carplay.data']} pending={pendingOf(state)} />
      </Row>
      <Row label="Folders">
        <button type="button" className="btn small" title="Open the logs folder in File Explorer" onClick={() => backend.open('logs')}>
          Logs
        </button>
        <button
          type="button"
          className="btn small"
          title={`Open the screenshots folder in File Explorer${screenshotKey ? ` (${keyLabel(screenshotKey)} saves to it)` : ''}`}
          onClick={() => backend.open('screenshots')}
        >
          Screenshots
        </button>
      </Row>
    </>
  )
}

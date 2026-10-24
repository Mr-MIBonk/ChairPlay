import type { AppState, Backend, DemoKind } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { Changed, parts, pendingOf } from './parts'

/**
 * An empty 8:3 frame cut at the edges of the part from eighth [from], [eighths] long, that part filled: where a view
 * area lies, and the room it leaves for dials.
 */
function areaIcon(from: number, eighths: number) {
  const cuts = [from, from + eighths].filter((cut) => cut > 0 && cut < 8)
  return (
    <svg className="area-icon" viewBox="0 0 32 12" aria-hidden="true">
      <rect className="part" x={from * 4} y="0" width={eighths * 4} height="12" />
      <rect className="frame" x="0.5" y="0.5" width="31" height="11" />
      {cuts.map((cut) => (
        <line key={cut} x1={cut * 4} y1="0" x2={cut * 4} y2="12" />
      ))}
    </svg>
  )
}


/**
 * The AltScreen's view areas as ChairPlay declares them for an 8:3 cluster (AltscreenAreas.kt), by their index, laid
 * out as they lie: the whole frame between the two fives, the threes left, middle, and right.
 */
const AREAS: readonly Choice[] = [
  ['1', 'Left 5/8', 'Five eighths at the left, beside a dial', areaIcon(0, 5)],
  ['0', 'Full screen', undefined, areaIcon(0, 8)],
  ['2', 'Right 5/8', 'Five eighths at the right, beside a dial', areaIcon(3, 5)],
  ['3', 'Left 3/8', 'Three eighths at the left, as a side panel', areaIcon(0, 3)],
  ['4', 'Center 3/8', 'Three eighths in the middle, between two dials', areaIcon(2.5, 3)],
  ['5', 'Right 3/8', 'Three eighths at the right, as a side panel', areaIcon(5, 3)],
]

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
 * the AltScreen's view areas, the app log's level, the CarPlay data log, and the folders the logs and the screenshots
 * go to.
 */
export function ExperimentsPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const demo = state.demo
  const { note } = parts(settings)
  const areas = (state.viewAreas?.count ?? 0) >= AREAS.length
  const screenshotKey = state.controls?.keys.screenshot
  return (
    <>
      <Group title="HUD demo" note="On the AltScreen's HUD" />
      {DEMOS.map(([kind, label, title]) => {
        const playing = demo?.playing === kind
        return (
          <Row key={kind} label={label}>
            <button
              type="button"
              className="btn small"
              disabled={!demo?.available}
              title={!demo?.available ? 'Turn on the AltScreen on its page first' : playing ? 'Stop the demo' : title}
              onClick={() => backend.demo(playing ? null : kind)}
            >
              {playing ? 'Stop' : 'Play'}
            </button>
          </Row>
        )
      })}
      <Group title="AltScreen view areas" note={note(['altscreen.experiment.areas'])} />
      <Row title="The AltScreen tells the iPhone of six view areas as it connects, which CarPlay can be moved between">
        <Checkbox
          label="Declare view areas"
          checked={settings.value('altscreen.experiment.areas') === 'true'}
          onChange={(checked) => settings.save({ 'altscreen.experiment.areas': String(checked) })}
        />
        <Changed keys={['altscreen.experiment.areas']} pending={pendingOf(state)} />
      </Row>
      <Row
        label="View area"
        title={areas ? 'Move CarPlay on the AltScreen to this part of its frame' : 'Turn on the view areas, then connect again'}
      >
        <Segmented
          label="View area"
          value={String(state.viewAreas?.current ?? 0)}
          options={AREAS}
          className="rows view-areas"
          disabled={!areas}
          onChange={(index) => void backend.viewArea(Number(index))}
        />
      </Row>
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

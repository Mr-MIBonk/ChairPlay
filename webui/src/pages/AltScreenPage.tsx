import type { AltscreenContent, AppState, Backend } from '../api'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { ActualSize, Changed, parts, pendingOf, SafeArea, SizeRows } from './parts'

/** The instrument clusters a size fits, for its preset's tooltip. */
const PRESETS = [
  [1280, 480, 'BYD DiLink 3: 10.25-inch clusters'],
  [1440, 540, 'Audi Virtual Cockpit, 12.3 inch'],
  [1920, 720, 'BYD DiLink 4 and 5, Geely: the common 12.3-inch cluster'],
] as const

const DISPLAY_KEYS = ['altscreen.enabled', 'altscreen.width', 'altscreen.height']

/** What the AltScreen can show, as the controls window lists it. */
const VIEWS: readonly Choice[] = [['hud', 'HUD'], ['map', 'Map'], ['map-card', 'Map + card'], ['card', 'Turn card']]

/** The second, cluster-sized window: whether it opens, its size, what it starts with, and its safe area. */
export function AltScreenPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const pending = pendingOf(state)
  const { choice, note } = parts(settings, pending)
  const viewOn = state.controls?.altscreen != null && state.windows.altscreen.available && state.canShowCarPlay
  return (
    <>
      <Group title="Display" note={note(DISPLAY_KEYS)} />
      <Row>
        <Checkbox
          label="Enabled"
          checked={settings.value('altscreen.enabled') === 'true'}
          onChange={(checked) => settings.save({ 'altscreen.enabled': String(checked) })}
        />
        <span className="exp">Experimental</span>
        <Changed keys={['altscreen.enabled']} pending={pending} />
      </Row>
      <SizeRows
        settings={settings}
        prefix="altscreen"
        presets={PRESETS}
        fallback={{ width: 1440, height: 540 }}
        after={<ActualSize sizes={state.altscreenWindow} window="altscreen" backend={backend} />}
        pending={pending}
      />
      <Group title="View" note="Also on the controls window" />
      <Row label="Content" title="What the AltScreen shows now; it opens on it again">
        {/* Without a session the AltScreen has no map to show: off, with nothing chosen, as on the controls window. */}
        <Segmented
          label="View"
          value={state.controls?.altscreen ?? ''}
          options={VIEWS}
          disabled={!viewOn}
          onChange={(content) => void backend.altscreen(content as AltscreenContent)}
        />
      </Row>
      <Row label="Turn card" title="Where the turn card starts; Shift+F11 moves it while CarPlay runs">
        {choice('altscreen.cards', 'Turn card', 'auto', [['auto', 'Auto'], ['left', 'Left'], ['right', 'Right'], ['top', 'Top'], ['off', 'Off']], 'fit')}
      </Row>
      <Group title="Safe area" note="Pixels at each edge" />
      <SafeArea
        settings={settings}
        prefix="altscreen.safe"
        label="Insets"
        pending={pending}
        after={
          state.altscreenWindow && (
            <button
              type="button"
              className="btn small show-frame"
              title="Show the safe area on the AltScreen for 3 seconds"
              onClick={() => backend.showAltscreenFrame()}
            >
              Show frame
            </button>
          )
        }
      />
    </>
  )
}

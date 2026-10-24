import type { AltscreenContent, AppState, Backend } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { ActualSize, Changed, parts, pendingOf, SafeArea, SizeRows } from './parts'

/** The instrument clusters a size fits, for its preset's tooltip. */
const PRESETS = [
  [1280, 480, 'BYD DiLink 3: 10.25-inch clusters'],
  [1440, 540, 'Audi Virtual Cockpit, 12.3 inch'],
  [1600, 600],
  [1920, 720,'BYD DiLink 4 and 5, Geely: the common 12.3-inch cluster'],
] as const

const DISPLAY_KEYS = ['altscreen.enabled', 'altscreen.width', 'altscreen.height']

/** What the AltScreen can show, as the controls window lists it. */
const VIEWS: readonly Choice[] = [['hud', 'HUD'], ['map', 'Map'], ['map-card', 'Map + card'], ['card', 'Turn card']]

/** Where the turn card goes over a map view (altscreen.cards). */
const CARDS: readonly Choice[] = [['auto', 'Auto'], ['left', 'Left'], ['right', 'Right'], ['top', 'Top'], ['off', 'Off']]

/** The size blank altscreen.width and .height mean, as the app reads them. */
const ALTSCREEN_FALLBACK = { width: 1440, height: 540 }

/** The second, cluster-sized window: whether it opens, its size, what it starts with, and its safe area. */
export function AltScreenPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const pending = pendingOf(state)
  const { note } = parts(settings, pending)
  // Only a map view has a turn card to place; with no view yet the choice is for the next one.
  const view = state.controls?.altscreen
  const placesCards = view == null || view === 'map' || view === 'map-card'
  const viewOn = state.controls?.altscreen != null && state.windows.altscreen.available && state.canShowCarPlay
  const cardsKey = state.controls?.keys['altscreen.cards']
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
        fallback={ALTSCREEN_FALLBACK}
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
      {/* The turn card alone and the HUD have no turn card to place: the choice waits for a map view. */}
      <Row
        label="Turn card"
        title={
          placesCards
            ? `Where the turn card starts${cardsKey ? `; ${keyLabel(cardsKey)} moves it while CarPlay runs` : ''}`
            : 'Show Map or Map + card to place the turn card'
        }
      >
        <Segmented
          label="Turn card"
          value={settings.value('altscreen.cards') || 'auto'}
          options={CARDS}
          disabled={!placesCards}
          onChange={(cards) => settings.save({ 'altscreen.cards': cards })}
        />
        <Changed keys={['altscreen.cards']} pending={pending} />
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

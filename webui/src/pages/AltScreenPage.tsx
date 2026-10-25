import type { AltscreenContent, AppState, Backend } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { AREA_CHOICES } from './areas'
import { ActualSize, Changed, parts, pendingOf, SafeArea, SizeRows } from './parts'

/** The common sizes of an instrument cluster, all 8:3. */
const PRESETS = [
  [1280, 480],
  [1440, 540],
  [1600, 600],
  [1920, 720],
] as const

const DISPLAY_KEYS = ['altscreen.enabled', 'altscreen.width', 'altscreen.height']

/** What the AltScreen can show, as the controls window lists it; the HUD is drawn by ChairPlay, the rest by the iPhone. */
const VIEWS: readonly Choice[] = [
  ['hud', 'HUD'],
  ['map', 'Map'],
  ['map-card', 'Map + card'],
  ['card', 'Turn card'],
]

/** The iPhone's views that can show in a part of the frame; the HUD is ChairPlay's own and takes the whole window. */
const AREA_VIEWS: readonly string[] = ['map', 'map-card', 'card']

/** Where the turn card goes over a map view (altscreen.cards). */
const CARDS: readonly Choice[] = [
  ['auto', 'Auto', "On the driver's side, as the CarPlay page sets it"],
  ['left', 'Left'],
  ['right', 'Right'],
  ['top', 'Top'],
]

/** The size blank altscreen.width and .height mean, as the app reads them. */
const ALTSCREEN_FALLBACK = { width: 1440, height: 540 }

/** The second, cluster-sized window: whether it opens, its size, what it starts with, and its safe area. */
export function AltScreenPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const pending = pendingOf(state)
  const off = settings.value('altscreen.enabled') !== 'true'
  const { note } = parts(settings, pending)
  const view = state.controls?.altscreen
  const viewOn = state.controls?.altscreen != null && state.windows.altscreen.available && state.canShowCarPlay
  const cardsKey = state.controls?.keys['altscreen.cards']
  // The view chosen in Content, or the one the AltScreen opens on without a session, is the one whose part of the
  // frame and margins the page sets; the HUD, which ChairPlay draws on the whole window, has neither: both are off.
  const shown = view ?? (settings.value('altscreen.content') || 'map')
  // Only Map + card has a turn card to place.
  const placesCards = shown === 'map-card'
  const areaView = AREA_VIEWS.includes(shown) ? shown : null
  const areaKey = `altscreen.${areaView ?? 'map'}.area`
  const safePrefix = areaView ? `altscreen.${areaView}.safe` : 'altscreen.safe'
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
      {/* Off, the AltScreen has nothing to set: the rest waits for it. */}
      <div className={off ? 'held' : undefined} inert={off} title={off ? 'Turn on the AltScreen to set it' : undefined}>
        <SizeRows
          settings={settings}
          prefix="altscreen"
          presets={PRESETS}
          fallback={ALTSCREEN_FALLBACK}
          after={<ActualSize sizes={state.altscreenWindow} window="altscreen" backend={backend} />}
          pending={pending}
        />
        <Row className="hint-row">
          <span className="hint">
            Most instrument clusters have an 8:3 aspect ratio, so the area presets below split the width into eighths
          </span>
        </Row>
        <Group title="View" />
        <Row
          label="Content"
          title="What the AltScreen shows now, as the controls window switches it too; it opens on it again"
        >
          {/* Without a session the AltScreen has no map to show: off, with nothing chosen, as on the controls window. */}
          <Segmented
            label="View"
            value={state.controls?.altscreen ?? ''}
            options={VIEWS}
            disabled={!viewOn}
            onChange={(content) => void backend.altscreen(content as AltscreenContent)}
          />
        </Row>
        {/* Map + card alone has a turn card to place: elsewhere the row is not there. */}
        {placesCards && (
          <Row
            label="Card position"
            title={`Where the turn card starts${cardsKey ? `; ${keyLabel(cardsKey)} moves it while CarPlay runs` : ''}`}
          >
            <Segmented
              label="Card position"
              value={settings.value('altscreen.cards') || 'auto'}
              options={CARDS}
              onChange={(cards) => settings.save({ 'altscreen.cards': cards })}
            />
            <Changed keys={['altscreen.cards']} pending={pending} />
          </Row>
        )}
        {/* The shown view's part of the frame; the HUD has none. */}
        <Row
          label="Area"
          title={
            areaView
              ? 'The part of the frame this view shows in, all of its height; the iPhone leaves the rest black'
              : 'Show Map, Map + card, or Turn card to set its part of the frame'
          }
        >
          <Segmented
            label="Area"
            value={settings.value(areaKey) || 'full'}
            options={AREA_CHOICES}
            className="rows view-areas"
            columns={3}
            disabled={!areaView}
            onChange={(preset) => settings.save({ [areaKey]: preset })}
          />
          <Changed keys={[areaKey]} pending={pending} />
        </Row>
        {/* A part too narrow for the view's margins is refused here, not on a margin. */}
        {settings.error(areaKey) && (
          <Row>
            <span className="errtext">{settings.error(areaKey)}</span>
          </Row>
        )}
        <Row title="Keep the iPhone drawing the map view picked last while the HUD shows, so the map comes back at once; the iPhone works harder">
          <Checkbox
            label="Keep the map stream under the HUD"
            checked={settings.value('altscreen.hud.stream') === 'true'}
            onChange={(checked) => settings.save({ 'altscreen.hud.stream': String(checked) })}
          />
          <Changed keys={['altscreen.hud.stream']} pending={pending} />
        </Row>
        <Row className="hint-row">
          <span className="hint">
            HUD is a custom screen drawn from the route, song, and call data the iPhone sends, not from CarPlay's video
            stream
          </span>
        </Row>
        <Group title="Safe area" note="Pixels at each edge" />
        <SafeArea
          key={safePrefix}
          settings={settings}
          prefix={safePrefix}
          label="Insets"
          pending={pending}
          disabled={!areaView}
          title={areaView ? undefined : 'Show Map, Map + card, or Turn card to set its safe area'}
          after={
            areaView &&
            state.altscreenWindow && (
              <button
                type="button"
                className="btn small show-frame"
                title="Show the safe area on the AltScreen for a second"
                onClick={() => backend.showAltscreenFrame(areaView)}
              >
                Show frame
              </button>
            )
          }
        />
      </div>
    </>
  )
}

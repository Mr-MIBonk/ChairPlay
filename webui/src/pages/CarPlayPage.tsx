import type { AppState, Backend } from '../api'
import { Group, Row } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { ActualSize, Changed, parts, pendingOf, SafeArea, SizeRows } from './parts'

const PRESETS = [
  [800, 480],
  [1280, 720],
  [1920, 720],
  [1920, 1080],
] as const

const DISPLAY_KEYS = ['display.width', 'display.height', 'display.fps', 'display.physical.width.mm', 'display.physical.height.mm', 'display.ppi']
const LOOK_KEYS = ['display.theme', 'display.dock', 'display.right.hand.drive', 'display.safe.draw.outside', 'display.corners']

/** The picture CarPlay draws: its size and frame rate, how large it is, its look, and the safe area. */
export function CarPlayPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const pending = pendingOf(state)
  const { field, choice, note } = parts(settings, pending)
  const displayFallback = settings.data?.display ?? { width: 1280, height: 720 }
  return (
    <>
      <Group title="Display" note={note(DISPLAY_KEYS)} />
      {/* Blank in the file: the size the app runs at, which is its default. */}
      <SizeRows
        settings={settings}
        prefix="display"
        presets={PRESETS}
        fallback={displayFallback}
        after={
          <ActualSize sizes={state.mainWindow} window="main" backend={backend} settings={settings} prefix="display" fallback={displayFallback} />
        }
        pending={pending}
      />
      <Row label="Frame rate">
        {choice('display.fps', 'Frame rate', '60', [['30', '30'], ['60', '60']], 'half')}
        <span className="unit">fps</span>
      </Row>
      <Row label="Physical size" title="Leave blank for 200 mm wide, with the height from the aspect ratio">
        {field('display.physical.width.mm', 'Physical width', '200')}
        <span className="x">×</span>
        {field('display.physical.height.mm', 'Physical height', 'auto')}
        <span className="unit">mm</span>
        <span className="x">or</span>
        {field('display.ppi', 'Pixels per inch', 'auto')}
        <span className="unit">ppi</span>
        <Changed keys={['display.physical.width.mm', 'display.physical.height.mm', 'display.ppi']} pending={pending} />
      </Row>
      <Group title="Look" note={note(LOOK_KEYS)} />
      <Row label="Theme">
        {choice('display.theme', 'Theme', 'windows', [['windows', 'Windows'], ['light', 'Light'], ['dark', 'Dark'], ['phone', 'Phone']])}
      </Row>
      <Row label="Dock">
        {choice('display.dock', 'Dock', 'auto', [['auto', 'Auto'], ['driver', 'Driver side'], ['bottom', 'Bottom']])}
      </Row>
      <Row label="Driving side">
        {choice('display.right.hand.drive', 'Driving side', 'false', [['false', 'Left-hand'], ['true', 'Right-hand']])}
      </Row>
      <Row label="Outside safe area" title="What CarPlay draws outside the safe area (drawUIOutsideSafeArea)">
        {choice('display.safe.draw.outside', 'Outside safe area', 'true', [['false', 'Black'], ['true', 'Draw UI']])}
      </Row>
      <Row label="Corners">
        {choice('display.corners', 'Corners', 'square', [['rounded', 'Rounded'], ['square', 'Square']])}
      </Row>
      <Group title="Safe area" note="Pixels at each edge" />
      <SafeArea settings={settings} prefix="display.safe" label="Insets" pending={pending} />
    </>
  )
}

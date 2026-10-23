import { Checkbox, Group, Row } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { parts, SafeArea, SizeRows } from './parts'

/** The instrument clusters a size fits, for its preset's tooltip. */
const PRESETS = [
  [1280, 480, 'BYD DiLink 3: 10.25-inch clusters'],
  [1440, 540, 'Audi Virtual Cockpit, 12.3 inch'],
  [1920, 720, 'BYD DiLink 4 and 5, Geely: the common 12.3-inch cluster'],
] as const

const DISPLAY_KEYS = ['altscreen.enabled', 'altscreen.width', 'altscreen.height']

/** The second, cluster-sized window: whether it opens, its size, what it starts with, and its safe area. */
export function AltScreenPage({ settings }: { settings: Settings }) {
  const { choice, note } = parts(settings)
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
      </Row>
      <SizeRows settings={settings} prefix="altscreen" presets={PRESETS} fallback={{ width: 1440, height: 540 }} />
      <Group title="Starts with" note="The controls window switches them" />
      <Row label="Content">
        {choice('altscreen.content', 'Content', 'map', [['hud', 'HUD'], ['map', 'Map'], ['map-card', 'Map + card'], ['card', 'Turn card']])}
      </Row>
      <Row label="Turn card">
        {choice('altscreen.cards', 'Turn card', 'auto', [['auto', 'Auto'], ['left', 'Left'], ['right', 'Right'], ['top', 'Top'], ['off', 'Off']])}
      </Row>
      <Group title="Safe area" note="Pixels at each edge" />
      <SafeArea settings={settings} prefix="altscreen.safe" label="Map text kept clear" />
    </>
  )
}

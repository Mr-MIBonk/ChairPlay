import type { ReactNode } from 'react'
import { Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { AREA_CHOICES } from './areas'
import { clamp, formatRect, presetRect, type Frame } from './areaGeometry'
import { Changed, type Pending } from './parts'

/** The views' part of the frame: the maps (Map, Map + card) share one, the Turn card has its own. */
export type AreaGroup = 'map' | 'card'

/** How the part is chosen: the whole frame, one of the presets, or a rectangle of its own. */
const MODES: readonly Choice[] = [
  ['full', 'Full screen', 'The whole frame'],
  ['preset', 'Preset', 'One of the parts in eighths of the frame'],
  ['custom', 'Custom', 'A rectangle of your own'],
]

/** What a group starts with (altscreen.<group>.area) and the preset Preset starts on when none was chosen yet. */
export const DEFAULT_AREA: Record<AreaGroup, string> = { map: 'full', card: 'center3' }
const DEFAULT_PRESET: Record<AreaGroup, string> = { map: 'center5', card: 'center3' }

/** The preset each group had last, so that Preset after Full screen brings it back; kept while the page is open. */
const remembered = new Map<AreaGroup, string>()

/** Forget the presets chosen so far (for a test). */
export function forgetPresets() {
  remembered.clear()
}

/**
 * The Area rows of a group: Full screen, Preset, or Custom, saved as altscreen.<group>.area; under Preset the grid of
 * parts follows, under Custom [onCustom] draws its editor. [disabled] holds both while no view with a part is shown.
 * Custom chosen while altscreen.<group>.area.custom is blank starts it as the part the group had of [frame].
 */
export function AreaChooser(props: {
  group: AreaGroup
  settings: Settings
  pending: Pending
  disabled: boolean
  frame: Frame
  onCustom?: () => ReactNode
}) {
  const { group, settings, pending, disabled } = props
  const key = `altscreen.${group}.area`
  const customKey = `${key}.custom`
  const value = settings.value(key) || DEFAULT_AREA[group]
  const mode = value === 'full' || value === 'custom' ? value : 'preset'
  if (mode === 'preset') remembered.set(group, value)
  const choose = (next: string) => {
    if (next === 'preset') settings.save({ [key]: remembered.get(group) ?? DEFAULT_PRESET[group] })
    // The seed is the stored area, which can differ from the slot declared at connect when presets were changed live
    // before the first Custom; the restart marker on area.custom flags that until the iPhone reconnects.
    else if (next === 'custom' && !settings.value(customKey).trim())
      settings.save({ [key]: next, [customKey]: formatRect(clamp(presetRect(value, props.frame), props.frame)) })
    else settings.save({ [key]: next })
  }
  return (
    <>
      <Row
        label="Area"
        title={
          disabled
            ? 'Show Map, Map + card, or Turn card to set its part of the frame'
            : 'The part of the frame this view shows in; the iPhone leaves the rest black'
        }
      >
        <Segmented label="Area" value={mode} options={MODES} disabled={disabled} onChange={choose} />
        <Changed keys={[key]} pending={pending} />
      </Row>
      {!disabled && mode === 'preset' && (
        <Row>
          <Segmented
            label="Area preset"
            value={value}
            options={AREA_CHOICES}
            className="rows view-areas"
            columns={3}
            onChange={(preset) => settings.save({ [key]: preset })}
          />
        </Row>
      )}
      {!disabled && mode === 'custom' && props.onCustom?.()}
      {/* A part too narrow for the view's margins is refused here, not on a margin. */}
      {settings.error(key) && (
        <Row>
          <span className="errtext">{settings.error(key)}</span>
        </Row>
      )}
    </>
  )
}

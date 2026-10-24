import type { Choice } from '../shell/controls'

/**
 * An empty 8:3 frame cut at the edges of the part from eighth [from], [eighths] long, that part filled: where a view
 * area lies, and the room it leaves for dials.
 */
export function areaIcon(from: number, eighths: number) {
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
 * The parts of the AltScreen's frame a view can show in, as AreaPreset (AltscreenAreas.kt) names them, laid out as they
 * lie: the full screen between the two fives, the threes left, middle, and right; each with where it starts and how
 * many eighths it takes.
 */
export const AREA_PRESETS = [
  ['left5', 'Left 5/8', 'Five eighths at the left, beside a dial', 0, 5],
  ['full', 'Full screen', 'The whole frame', 0, 8],
  ['right5', 'Right 5/8', 'Five eighths at the right, beside a dial', 3, 5],
  ['left3', 'Left 3/8', 'Three eighths at the left, as a side panel', 0, 3],
  ['center3', 'Center 3/8', 'Three eighths in the middle, between two dials', 2.5, 3],
  ['right3', 'Right 3/8', 'Three eighths at the right, as a side panel', 5, 3],
] as const

/** The parts as choices, each with its picture. */
export const AREA_CHOICES: readonly Choice[] = AREA_PRESETS.map(([key, label, title, from, eighths]) => [
  key,
  label,
  title,
  areaIcon(from, eighths),
])

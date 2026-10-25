// A custom part of the AltScreen's frame, in its pixels, as altscreen.<group>.area.custom holds it: the grid it keeps
// to, moving and resizing it inside the frame, and the presets' parts, as AreaRect.kt and AltscreenAreas.kt have them.
import { AREA_PRESETS } from './areas'

export type Rect = { x: number; y: number; width: number; height: number }
export type Frame = { width: number; height: number }
export type Corner = 'nw' | 'ne' | 'sw' | 'se'

/** The least width and height of a custom part, as MIN_AREA in AreaRect.kt. */
export const MIN_AREA = 64

const down = (value: number, step: number) => Math.floor(value / step) * step
const within = (value: number, low: number, high: number) => Math.min(Math.max(value, low), high)

/** Down to the grid, x, y and the height even and the width a multiple of 8, and at least [MIN_AREA] each way. */
export function snap(r: Rect): Rect {
  return {
    x: down(r.x, 2),
    y: down(r.y, 2),
    width: Math.max(MIN_AREA, down(r.width, 8)),
    height: Math.max(MIN_AREA, down(r.height, 2)),
  }
}

/** Inside [frame]: its size kept if it fits, else shrunk to the frame's; then moved in, and snapped. */
export function clamp(r: Rect, frame: Frame): Rect {
  const width = Math.min(r.width, frame.width)
  const height = Math.min(r.height, frame.height)
  return snap({
    x: within(r.x, 0, frame.width - width),
    y: within(r.y, 0, frame.height - height),
    width,
    height,
  })
}

/** [r] moved by [dx], [dy], stopped at the edges of [frame]. */
export function moved(r: Rect, dx: number, dy: number, frame: Frame): Rect {
  return clamp({ ...r, x: r.x + dx, y: r.y + dy }, frame)
}

/**
 * [r] with [corner] moved by [dx], [dy] and the opposite corner kept: no smaller than [MIN_AREA] and no further than
 * the edges of [frame], each side on the grid. [r] is on the grid, so the kept corner is too.
 */
export function resized(r: Rect, corner: Corner, dx: number, dy: number, frame: Frame): Rect {
  const west = corner === 'nw' || corner === 'sw'
  const north = corner === 'nw' || corner === 'ne'
  const right = r.x + r.width
  const bottom = r.y + r.height
  // The side that moves, up to the frame's edge, then down to the grid, which keeps it at least MIN_AREA (8 × 8).
  const side = (wanted: number, room: number, step: number) => down(Math.max(MIN_AREA, Math.min(wanted, room)), step)
  const width = side(west ? r.width - dx : r.width + dx, west ? right : frame.width - r.x, 8)
  const height = side(north ? r.height - dy : r.height + dy, north ? bottom : frame.height - r.y, 2)
  return { x: west ? right - width : r.x, y: north ? bottom - height : r.y, width, height }
}

/**
 * The part a preset ([key], as altscreen.<group>.area names it) takes of [frame], as AreaPreset.area gives it: its
 * eighths of the width down to a multiple of 8, at the left, in the middle on an even x, or at the right, full height;
 * "full", or a key it does not know, is the whole frame.
 */
export function presetRect(key: string, frame: Frame): Rect {
  const preset = AREA_PRESETS.find(([name]) => name === key)
  if (!preset) return { x: 0, y: 0, width: frame.width, height: frame.height }
  const eighths = preset[4]
  const width = down(Math.floor((frame.width * eighths) / 8), 8)
  const left = key.startsWith('left')
  const x = left ? 0 : key.startsWith('center') ? Math.floor((frame.width - width) / 4) * 2 : frame.width - width
  return { x, y: 0, width, height: frame.height }
}

/** The setting's text: "x, y, width, height". */
export function formatRect(r: Rect): string {
  return `${r.x}, ${r.y}, ${r.width}, ${r.height}`
}

/** Four whole numbers, comma-separated, spaces allowed; null for anything else, as AreaRect.parse. */
export function parseRect(text: string): Rect | null {
  const numbers = text.split(',').map((part) => part.trim())
  if (numbers.length !== 4 || !numbers.every((part) => /^-?\d+$/.test(part))) return null
  const [x, y, width, height] = numbers.map(Number)
  return { x, y, width, height }
}

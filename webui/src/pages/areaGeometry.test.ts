import { describe, expect, it } from 'vitest'
import { clamp, formatRect, moved, parseRect, presetRect, resized, snap } from './areaGeometry'

const FRAME = { width: 1440, height: 540 }

describe('custom area geometry', () => {
  it('snaps x, y and height down to even and the width down to a multiple of 8, as AreaRect does', () => {
    expect(snap({ x: 413, y: 37, width: 619, height: 469 })).toEqual({ x: 412, y: 36, width: 616, height: 468 })
    expect(snap({ x: 0, y: 0, width: 10, height: 3 })).toEqual({ x: 0, y: 0, width: 64, height: 64 })
  })

  it('clamps into the frame as AreaRect.clampedInto does: size kept if it fits, then moved inside', () => {
    expect(clamp({ x: 1200, y: 0, width: 400, height: 300 }, { width: 1280, height: 480 })).toEqual({
      x: 880,
      y: 0,
      width: 400,
      height: 300,
    })
    expect(clamp({ x: -20, y: 100, width: 2000, height: 900 }, FRAME)).toEqual({ x: 0, y: 0, width: 1440, height: 540 })
  })

  it('moves a rectangle and stops it at the edge it is pushed past', () => {
    const rect = { x: 272, y: 0, width: 896, height: 540 }
    expect(moved(rect, 51, 0, FRAME)).toEqual({ x: 322, y: 0, width: 896, height: 540 })
    expect(moved(rect, 1000, 30, FRAME)).toEqual({ x: 544, y: 0, width: 896, height: 540 })
    expect(moved(rect, -1000, -30, FRAME)).toEqual({ x: 0, y: 0, width: 896, height: 540 })
  })

  it('resizes by a corner, the opposite corner kept, and stops at the minimum and at the frame', () => {
    const rect = { x: 272, y: 100, width: 896, height: 300 }
    expect(resized(rect, 'se', -2000, -2000, FRAME)).toEqual({ x: 272, y: 100, width: 64, height: 64 })
    expect(resized(rect, 'nw', 2000, 2000, FRAME)).toEqual({ x: 1104, y: 336, width: 64, height: 64 })
    expect(resized(rect, 'se', 2000, 2000, FRAME)).toEqual({ x: 272, y: 100, width: 1168, height: 440 })
    expect(resized(rect, 'nw', -2000, -2000, FRAME)).toEqual({ x: 0, y: 0, width: 1168, height: 400 })
    expect(resized(rect, 'ne', 13, -21, FRAME)).toEqual({ x: 272, y: 80, width: 904, height: 320 })
    expect(resized(rect, 'sw', 13, 21, FRAME)).toEqual({ x: 288, y: 100, width: 880, height: 320 })
  })

  it('keeps a drag inside a small frame and at least 64 px each way', () => {
    const small = { width: 800, height: 480 }
    const rect = { x: 200, y: 100, width: 400, height: 300 }
    expect(clamp({ x: 1200, y: 0, width: 400, height: 300 }, small)).toEqual({ x: 400, y: 0, width: 400, height: 300 })
    expect(clamp({ x: -10, y: 300, width: 1000, height: 600 }, small)).toEqual({ x: 0, y: 0, width: 800, height: 480 })
    expect(moved(rect, 1000, 1000, small)).toEqual({ x: 400, y: 180, width: 400, height: 300 })
    expect(moved(rect, -1000, -1000, small)).toEqual({ x: 0, y: 0, width: 400, height: 300 })
    expect(resized(rect, 'se', 2000, 2000, small)).toEqual({ x: 200, y: 100, width: 600, height: 380 })
    expect(resized(rect, 'se', -2000, -2000, small)).toEqual({ x: 200, y: 100, width: 64, height: 64 })
    expect(resized(rect, 'nw', 2000, 2000, small)).toEqual({ x: 536, y: 336, width: 64, height: 64 })
    expect(resized({ x: 120, y: 60, width: 400, height: 200 }, 'se', 2000, 2000, { width: 1280, height: 480 })).toEqual({
      x: 120,
      y: 60,
      width: 1160,
      height: 420,
    })
  })

  it('gives the presets the areas AreaPreset.area gives them (AltscreenAreasTest)', () => {
    expect(presetRect('center5', FRAME)).toEqual({ x: 272, y: 0, width: 896, height: 540 })
    expect(presetRect('center3', FRAME)).toEqual({ x: 452, y: 0, width: 536, height: 540 })
    expect(presetRect('right3', FRAME)).toEqual({ x: 904, y: 0, width: 536, height: 540 })
    expect(presetRect('full', FRAME)).toEqual({ x: 0, y: 0, width: 1440, height: 540 })
  })

  it('writes and reads the setting as "x, y, width, height"', () => {
    expect(formatRect({ x: 412, y: 36, width: 616, height: 468 })).toBe('412, 36, 616, 468')
    expect(parseRect(' 412,36 , 616, 468 ')).toEqual({ x: 412, y: 36, width: 616, height: 468 })
    expect(parseRect('')).toBeNull()
    expect(parseRect('1, 2, 3')).toBeNull()
    expect(parseRect('1, 2, 3, x')).toBeNull()
    expect(parseRect('1.5, 2, 3, 4')).toBeNull()
  })
})

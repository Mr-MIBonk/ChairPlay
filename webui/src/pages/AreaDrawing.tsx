import { useLayoutEffect, useRef, useState, type PointerEvent } from 'react'
import { moved, resized, type Corner, type Frame, type Rect } from './areaGeometry'

/** A dial's well, as the state gives it: its centre and radius in frame pixels. */
export type Well = { x: number; y: number; radius: number }

/** The least size of a corner handle on screen, in CSS px. */
const MIN_HANDLE = 10

const CORNERS: readonly (readonly [Corner, string])[] = [
  ['nw', 'Top-left corner'],
  ['ne', 'Top-right corner'],
  ['sw', 'Bottom-left corner'],
  ['se', 'Bottom-right corner'],
]

/** What a drag holds: what it moves, the rectangle it started from, where, and frame pixels per screen pixel. */
interface Drag {
  kind: 'move' | Corner
  start: Rect
  x: number
  y: number
  scale: number
  last: Rect
}

const same = (a: Rect, b: Rect) => a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height

/**
 * The AltScreen's [frame] drawn as wide as the view areas' grid, in its own pixels: the virtual car's [wells] dashed,
 * and [rect] tinted, which drags whole or by its corners. Each step of a drag goes to [onDrag] on the grid and inside
 * the frame, the rectangle let go to [onRelease]; a drag the system takes away ends with [onDrag] null.
 */
export function AreaDrawing(props: {
  frame: Frame
  wells: readonly Well[]
  rect: Rect
  onDrag: (rect: Rect | null) => void
  onRelease: (rect: Rect) => void
}) {
  const { frame, rect } = props
  const drag = useRef<Drag | null>(null)
  const svg = useRef<SVGSVGElement>(null)
  // The drawing's width on screen, inside its border; 0 until laid out (and in a test).
  const [drawn, setDrawn] = useState(0)
  useLayoutEffect(() => {
    const element = svg.current
    if (!element) return
    const measure = () => setDrawn(element.clientWidth)
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  // A handle a ninth of the frame's height, and never more than a third of the area, so that the middle still moves it;
  // but never smaller on screen than MIN_HANDLE, so that a small area's corners can still be caught.
  const handle = Math.max(
    Math.min(frame.height / 9, rect.width / 3, rect.height / 3),
    drawn > 0 ? (MIN_HANDLE * frame.width) / drawn : 0,
  )

  const begin = (kind: Drag['kind']) => (event: PointerEvent<SVGElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    event.stopPropagation()
    // The drawn frame is the drawing inside its border; unlaid out (in a test), a screen pixel counts as a frame pixel.
    const drawn = event.currentTarget.ownerSVGElement?.clientWidth ?? 0
    const scale = drawn > 0 ? frame.width / drawn : 1
    event.currentTarget.setPointerCapture?.(event.pointerId)
    drag.current = { kind, start: rect, x: event.clientX, y: event.clientY, scale, last: rect }
  }

  const follow = (event: PointerEvent<SVGSVGElement>) => {
    const current = drag.current
    if (!current) return
    const dx = Math.round((event.clientX - current.x) * current.scale)
    const dy = Math.round((event.clientY - current.y) * current.scale)
    current.last =
      current.kind === 'move'
        ? moved(current.start, dx, dy, frame)
        : resized(current.start, current.kind, dx, dy, frame)
    props.onDrag(current.last)
  }

  const end = () => {
    const current = drag.current
    if (!current) return
    drag.current = null
    if (same(current.last, current.start)) props.onDrag(null)
    else props.onRelease(current.last)
  }

  const cancel = () => {
    if (!drag.current) return
    drag.current = null
    props.onDrag(null)
  }

  return (
    <svg
      ref={svg}
      className="area-drawing"
      viewBox={`0 0 ${frame.width} ${frame.height}`}
      onPointerMove={follow}
      onPointerUp={end}
      onPointerCancel={cancel}
    >
      {props.wells.map((well, index) => (
        <circle key={index} className="well" cx={well.x} cy={well.y} r={well.radius} />
      ))}
      <rect
        className="area"
        aria-label="Custom area"
        x={rect.x}
        y={rect.y}
        width={rect.width}
        height={rect.height}
        onPointerDown={begin('move')}
      >
        <title>Drag to move the area</title>
      </rect>
      {CORNERS.map(([corner, name]) => (
        <rect
          key={corner}
          className={`handle ${corner}`}
          aria-label={name}
          x={corner.endsWith('w') ? rect.x : rect.x + rect.width - handle}
          y={corner.startsWith('n') ? rect.y : rect.y + rect.height - handle}
          width={handle}
          height={handle}
          onPointerDown={begin(corner)}
        >
          <title>Drag to resize the area</title>
        </rect>
      ))}
    </svg>
  )
}

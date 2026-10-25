import { useState } from 'react'
import type { Backend } from '../api'
import { Row } from '../shell/controls'
import { Field } from '../shell/fields'
import type { Settings } from '../shell/useSettings'
import { DEFAULT_AREA, type AreaGroup } from './AreaChooser'
import { AreaDrawing, type Well } from './AreaDrawing'
import { clamp, formatRect, parseRect, presetRect, snap, type Frame, type Rect } from './areaGeometry'
import { Changed, type Pending } from './parts'

/**
 * A group's custom part of the frame, altscreen.<group>.area.custom: its position and size as numbers, a button that
 * shows it on the AltScreen, and the frame drawn with it to drag. A typed number is saved on the grid as the field is
 * left; a drag is saved once, as it is let go. A rectangle the app refuses leaves its reason, the drawing back on the
 * saved one. While none is saved, the group's default part stands in for it, as the app takes it.
 */
export function CustomArea(props: {
  group: AreaGroup
  /** The view shown, whose part Show draws on the AltScreen. */
  view: string
  frame: Frame
  wells: readonly Well[]
  settings: Settings
  pending: Pending
  backend: Backend
  /** Whether the AltScreen is open to show the part on. */
  showable: boolean
}) {
  const { group, frame, settings } = props
  const key = `altscreen.${group}.area.custom`
  const saved = parseRect(settings.value(key))
  // Snapped and inside the frame, as the app takes it, which can differ from the text once the frame has shrunk.
  const base = clamp(saved ?? presetRect(DEFAULT_AREA[group], frame), frame)
  // The rectangle while it is dragged, and until the app has taken or refused it.
  const [draft, setDraft] = useState<Rect | null>(null)
  const shown = draft ?? base
  const error = settings.error(key)

  const release = async (rect: Rect) => {
    setDraft(rect)
    await settings.save({ [key]: formatRect(rect) })
    setDraft(null)
  }

  const field = (part: keyof Rect, label: string, title: string) => (
    <Field
      label={label}
      title={title}
      className="short c"
      value={String(shown[part])}
      error={error}
      showReason={false}
      onCommit={(text) => {
        const parts = { ...base, [part]: text.trim() }
        const typed = [parts.x, parts.y, parts.width, parts.height].join(', ')
        const rect = parseRect(typed)
        // Anything but a whole number goes as it is, for the app to say what is wrong with it.
        return settings.save({ [key]: rect ? formatRect(snap(rect)) : typed })
      }}
    />
  )

  return (
    <>
      <Row label="Size" title="The area's width and height, in AltScreen pixels">
        {field('width', 'Area width', 'Width')}
        <span className="x times">×</span>
        {field('height', 'Area height', 'Height')}
        <span className="unit">px</span>
        <Changed keys={[key]} pending={props.pending} />
        {props.showable && (
          <button
            type="button"
            className="btn small show-frame"
            disabled={!saved}
            title={saved ? 'Show the area on the AltScreen for a second' : 'Move or resize the area first'}
            onClick={() => void props.backend.showAltscreenFrame(props.view)}
          >
            Show area
          </button>
        )}
      </Row>
      <Row label="Position" title="The area's top-left corner, in AltScreen pixels">
        {field('x', 'Area x', 'Pixels from the left edge')}
        <span className="unit pair">X</span>
        {field('y', 'Area y', 'Pixels from the top edge')}
        <span className="unit">Y</span>
      </Row>
      {error && (
        <Row>
          <span className="errtext">{error}</span>
        </Row>
      )}
      <Row>
        <AreaDrawing
          frame={frame}
          wells={props.wells}
          rect={shown}
          onDrag={setDraft}
          onRelease={(rect) => void release(rect)}
        />
      </Row>
    </>
  )
}

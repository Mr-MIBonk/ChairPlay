// What the settings pages build their rows from, bound to the shared settings: a field and a segmented choice that
// save their key, the note a group's header gives, and the safe-area cross.
import type { ReactNode } from 'react'
import { Field, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'

export function parts(settings: Settings) {
  const { value, error, save } = settings
  return {
    /** A field for [key]; [placeholder] is what applies while it is blank. */
    field: (key: string, label: string, placeholder: string, className = 'n') => (
      <Field
        label={label}
        className={className}
        value={value(key)}
        placeholder={placeholder}
        error={error(key)}
        onCommit={(text) => save({ [key]: text })}
      />
    ),
    /** A segmented choice for [key], [fallback] chosen while it is blank, as the app then takes it. */
    choice: (key: string, label: string, fallback: string, options: readonly Choice[], className?: string) => (
      <Segmented
        label={label}
        value={value(key) || fallback}
        options={options}
        className={className}
        onChange={(chosen) => save({ [key]: chosen })}
      />
    ),
    /** The app's word on when [keys] take effect, for their group's header: the page says what the app says. */
    note: (keys: readonly string[]) => {
      const applies = new Set(keys.map((key) => settings.data?.applies[key]))
      if (applies.size !== 1) return undefined
      if (applies.has('restart')) return 'Applies after restart'
      if (applies.has('reconnect')) return 'Applies at the next connection'
      return undefined
    },
  }
}

/**
 * A window's size: presets ([width, height, cars that use it]) over its width and height fields, [prefix].width and
 * [prefix].height. Blank in the file means [fallback], which the app then takes. A preset saves both numbers; a size
 * being corrected picks none.
 */
export function SizeRows(props: {
  settings: Settings
  prefix: string
  presets: readonly (readonly [width: number, height: number, cars?: string])[]
  fallback: { width: number; height: number }
  /** Beside the fields: the CarPlay page's Actual size. */
  after?: ReactNode
}) {
  const { settings, prefix, fallback } = props
  const { field } = parts(settings)
  const [widthKey, heightKey] = [`${prefix}.width`, `${prefix}.height`]
  const width = settings.value(widthKey) || String(fallback.width)
  const height = settings.value(heightKey) || String(fallback.height)
  const refused = settings.error(widthKey) || settings.error(heightKey)
  return (
    <>
      <Row label="Size">
        <Segmented
          label="Size"
          className="presets"
          value={refused ? '' : `${width}x${height}`}
          options={props.presets.map(([w, h, cars]) => [`${w}x${h}`, `${w}×${h}`, cars])}
          onChange={(preset) => {
            const [presetWidth, presetHeight] = preset.split('x')
            settings.save({ [widthKey]: presetWidth, [heightKey]: presetHeight })
          }}
        />
      </Row>
      <Row>
        {field(widthKey, 'Width', String(fallback.width))}
        <span className="x">×</span>
        {field(heightKey, 'Height', String(fallback.height))}
        <span className="unit">px</span>
        {props.after}
      </Row>
    </>
  )
}

const EDGES = [
  ['top', 'Top', 'c'],
  ['left', 'Left', ''],
  ['right', 'Right', 'l'],
  ['bottom', 'Bottom', 'c'],
] as const

/**
 * Four numbers round a little frame, one for each edge of [prefix] ("display.safe" or "altscreen.safe"): top and
 * bottom centred, the sides against the frame. A refused one turns red, with its reason under the frame.
 */
export function SafeArea({ settings, prefix, label }: { settings: Settings; prefix: string; label: string }) {
  const reason = EDGES.map(([edge]) => settings.error(`${prefix}.${edge}`)).find(Boolean)
  const [top, left, right, bottom] = EDGES.map(([edge, name, align]) => {
    const key = `${prefix}.${edge}`
    return (
      <Field
        key={key}
        label={name}
        title={name}
        className={`n3 ${align}`}
        value={settings.value(key)}
        placeholder="0"
        error={settings.error(key)}
        showReason={false}
        onCommit={(text) => settings.save({ [key]: text })}
      />
    )
  })
  return (
    <Row label={label} className="safe-row">
      <span className="safe">
        <span />
        {top}
        <span />
        {left}
        <span className="frame" />
        {right}
        <span />
        {bottom}
        <span />
      </span>
      {reason && <span className="errtext">{reason}</span>}
    </Row>
  )
}

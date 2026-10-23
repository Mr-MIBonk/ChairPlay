// The settings pages' parts, as desktop-docs/mockups/desktop-ui.html draws them: a property grid of rows under group
// headers, segmented groups, checkboxes, and the messages that say what is wrong.
import { useState, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react'
import { EyeIcon } from './icons'

/** A group's header: its name in the accent font, and a note at its right end. */
export function Group({ title, note }: { title: string; note?: string }) {
  return (
    <h3 className="grp">
      {title}
      {note && <span>{note}</span>}
    </h3>
  )
}

/** One line of the property grid: the label right-aligned in its column, the controls beside it. */
export function Row(props: { label?: string; htmlFor?: string; title?: string; className?: string; children: ReactNode }) {
  return (
    <div className={`row ${props.className ?? ''}`} title={props.title}>
      <label htmlFor={props.htmlFor}>{props.label}</label>
      <div className="ctl">{props.children}</div>
    </div>
  )
}

/**
 * A text field that saves on Enter or when it loses the focus, and only what changed. [onCommit] resolving true says
 * the value was saved; otherwise what was typed stays, red with [error] beside it, rather than jumping back. Escape
 * puts the saved value back, and goes no further (it would otherwise show CarPlay).
 */
export function Field(props: {
  label: string
  value: string
  onCommit: (value: string) => unknown
  error?: string
  placeholder?: string
  className?: string
  type?: 'text' | 'password'
  title?: string
  /** False where the reason goes elsewhere, as under the safe-area frame: the field only turns red. */
  showReason?: boolean
}) {
  const [draft, setDraft] = useState(props.value)
  const [dirty, setDirty] = useState(false)
  // A value saved, or a reason gone, by another control (a preset saving both sizes) replaces what was typed.
  const [seen, setSeen] = useState({ value: props.value, error: props.error })
  if (seen.value !== props.value || (seen.error && !props.error)) {
    setSeen({ value: props.value, error: props.error })
    setDirty(false)
  } else if (seen.error !== props.error) {
    setSeen({ value: props.value, error: props.error })
  }
  const shown = dirty ? draft : props.value

  function commit() {
    // While a reason shows, even the saved value is sent again: saving it is how the reason goes.
    if (shown === props.value && !props.error) return
    Promise.resolve(props.onCommit(shown)).then((saved) => {
      if (saved === true) setDirty(false)
    })
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') commit()
    if (event.key === 'Escape' && dirty) {
      event.preventDefault()
      setDirty(false)
    }
  }

  return (
    <>
      <input
        className={`t ${props.className ?? ''} ${props.error ? 'err' : ''}`}
        type={props.type ?? 'text'}
        aria-label={props.label}
        aria-invalid={props.error ? true : undefined}
        title={props.title}
        value={shown}
        placeholder={props.placeholder}
        autoComplete={props.type === 'password' ? 'new-password' : 'off'}
        spellCheck={false}
        onChange={(event) => {
          setDraft(event.target.value)
          setDirty(true)
        }}
        onKeyDown={onKeyDown}
        onBlur={commit}
      />
      {props.error && props.showReason !== false && <span className="errtext">{props.error}</span>}
    </>
  )
}

/**
 * A password field: empty, with dots for a saved password, and saves what is typed. The eye shows what is typed, and
 * the saved password itself, which it asks the app for ([saved]) only then; hiding it empties the field again.
 */
export function PasswordField(props: {
  label: string
  set: boolean
  onCommit: (value: string) => unknown
  error?: string
  saved?: () => Promise<string | null>
}) {
  const [reveal, setReveal] = useState(false)
  const [shown, setShown] = useState<string | null>(null)
  const eye = reveal ? 'Hide the password' : 'Show the password'
  const toggle = async () => {
    if (reveal) {
      setReveal(false)
      setShown(null)
      return
    }
    if (props.set && props.saved) setShown(await props.saved())
    setReveal(true)
  }
  return (
    <>
      <span className="pw">
        <Field
          label={props.label}
          type={reveal ? 'text' : 'password'}
          // Red by class only: a reason passed to the field would make it send the empty saved value again.
          className={`w200 ${props.set ? 'set' : ''} ${props.error ? 'err' : ''}`}
          value={shown ?? ''}
          placeholder={props.set ? '••••••••••' : 'Not set'}
          onCommit={props.onCommit}
        />
        <button type="button" className="eye" aria-label={eye} title={eye} onClick={() => void toggle()}>
          <EyeIcon />
        </button>
      </span>
      {props.error && <span className="errtext">{props.error}</span>}
    </>
  )
}

/** A drop-down list: the value saved and the text shown for each choice. */
export function Select(props: {
  label: string
  value: string
  options: readonly Choice[]
  onChange: (value: string) => void
  className?: string
}) {
  return (
    <select
      className={`t ${props.className ?? ''}`}
      aria-label={props.label}
      value={props.value}
      onChange={(event) => props.onChange(event.target.value)}
    >
      {props.options.map(([value, text]) => (
        <option key={value} value={value}>
          {text}
        </option>
      ))}
    </select>
  )
}

/** One choice of a segmented group: the value saved, the text shown, and its tooltip. */
export type Choice = readonly [value: string, text: string, title?: string]

/**
 * Choices side by side, the chosen one a lit plate. A disabled group shows none chosen. Two or three choices are equal
 * plates; four or more ("wide") keep to the length of a row's controls, and are as long as four plates where the page
 * has room, and five where it has room for that; six or more ("rows") wrap onto two rows of equal plates. [className]
 * sets the width by hand instead: "compact", or "half" for short figures.
 */
export function Segmented(props: {
  label: string
  value: string
  options: readonly Choice[]
  onChange: (value: string) => void
  disabled?: boolean
  className?: string
}) {
  return (
    <span
      className={`seg ${props.className ?? (props.options.length >= 6 ? 'rows' : props.options.length >= 4 ? 'wide' : '')}`}
      data-items={props.options.length}
      style={props.options.length >= 6 ? ({ '--columns': Math.ceil(props.options.length / 2) } as CSSProperties) : undefined}
      role="radiogroup"
      aria-label={props.label}
    >
      {props.options.map(([value, text, title]) => {
        const chosen = !props.disabled && value === props.value
        return (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={chosen}
            className={chosen ? 'on' : ''}
            title={title}
            disabled={props.disabled}
            onClick={() => {
              if (!chosen) props.onChange(value)
            }}
          >
            {text}
          </button>
        )
      })}
    </span>
  )
}

/**
 * A level from 0 to 100: a track like a segmented group's frame, lit up to the level as a chosen plate is, with a
 * plate to drag, and the level in percent beside it ([idle] in its place while it is not dragged, for a muted level).
 * Each move passes the level on; while it is dragged the slider keeps its own, so the app's answer does not pull it
 * back, and follows [value] again once let go.
 */
export function Slider(props: { label: string; value: number; onChange: (value: number) => void; idle?: string }) {
  const [dragged, setDragged] = useState<number | null>(null)
  const value = dragged ?? props.value
  const letGo = () => setDragged(null)
  const idle = dragged === null ? props.idle : undefined
  return (
    <>
      <input
        type="range"
        className="slider"
        aria-label={props.label}
        min={0}
        max={100}
        step={1}
        value={value}
        style={{ '--level': `${value}%` } as CSSProperties}
        onChange={(event) => {
          const level = Number(event.target.value)
          setDragged(level)
          props.onChange(level)
        }}
        onPointerUp={letGo}
        onKeyUp={letGo}
        onBlur={letGo}
      />
      <span className={`level ${idle ? 'idle' : ''}`}>{idle ?? `${value}%`}</span>
    </>
  )
}

export function Checkbox(props: { label: string; checked: boolean; onChange: (checked: boolean) => void; disabled?: boolean }) {
  return (
    <label className="chk">
      <input
        type="checkbox"
        checked={props.checked}
        disabled={props.disabled}
        onChange={(event) => props.onChange(event.target.checked)}
      />
      {props.label}
    </label>
  )
}

/**
 * What is wrong: the problem in the message's colour, then the cause and what to do, the time at the right for an
 * error; [children] go on the grid below, a field in the wide column and its button beside it, or a line of actions.
 */
export function Message(props: {
  tone: 'bad' | 'warn'
  head: string
  text: ReactNode
  at?: string
  title?: string
  children?: ReactNode
}) {
  return (
    <div className={`alert ${props.tone}`} role="alert" title={props.title}>
      <div className="head">
        {props.head} <span>{props.text}</span>
      </div>
      <span className="when">{props.at}</span>
      {props.children}
    </div>
  )
}

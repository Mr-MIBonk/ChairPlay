import { useId, type ReactNode } from 'react'
import type { Applies } from './api'

export function Card({ title, children, className = '' }: { title?: string; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {title && <h2 className="card-title">{title}</h2>}
      {children}
    </section>
  )
}

/** An iOS-style switch; the whole row is the control. */
export function SwitchRow(props: {
  label: string
  detail?: string
  checked: boolean
  disabled?: boolean
  onChange: (checked: boolean) => void
}) {
  const id = useId()
  return (
    <div className="row">
      <div className="row-text">
        <label htmlFor={id} className="row-label">
          {props.label}
        </label>
        {props.detail && <div className="row-detail">{props.detail}</div>}
      </div>
      <input
        id={id}
        type="checkbox"
        role="switch"
        className="switch"
        checked={props.checked}
        disabled={props.disabled}
        onChange={(event) => props.onChange(event.target.checked)}
      />
    </div>
  )
}

export function FieldError({ message }: { message?: string }) {
  return message ? (
    <div className="field-error" role="alert">
      {message}
    </div>
  ) : null
}

/** What the screen says after a save: the latest moment any of the saved settings takes effect. */
export function appliesText(applies: Applies[]): string {
  if (applies.includes('restart')) return 'Saved. Applies after restart.'
  if (applies.includes('reconnect')) return 'Saved. Applies with the next connection.'
  return 'Saved.'
}

export function Note({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'warn' | 'ok' }) {
  return (
    <div className={`note note-${tone}`} role="status">
      {children}
    </div>
  )
}

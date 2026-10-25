/** The GPS page's own fields: where the car is, which way it faces, and the compass that shows it. */

import { useId, useState, type KeyboardEvent } from 'react'
import type { GpsState } from '../api'
import type { Settings } from '../shell/useSettings'
import { parseCoordinates } from './cities'

/** What is wrong with a start point as typed, or null when it can be saved. */
function startProblem(latitude: string, longitude: string): string | null {
  if (!latitude) return 'Enter the latitude too'
  if (!longitude) return 'Enter the longitude too'
  const [lat, lon] = [Number(latitude), Number(longitude)]
  if (!Number.isFinite(lat) || Math.abs(lat) > 90) return 'A latitude is from -90 to 90'
  if (!Number.isFinite(lon) || Math.abs(lon) > 180) return 'A longitude is from -180 to 180'
  return null
}

/** Where the car is now, as the fields show it: from the app while it runs, else the saved point. */
export function placeOf(car: GpsState | null, saved: string) {
  if (car) return car.position ? { latitude: car.position[0].toFixed(5), longitude: car.position[1].toFixed(5) } : null
  return parseCoordinates(saved)
}

/**
 * The car's latitude and longitude, in that order, as map apps give them: where it is now, following it as it drives,
 * and where it goes when they are changed (gps.start, with the heading shown). Both are saved together when either
 * field is left or Enter is pressed; one alone is no place, so the page says what is missing instead. A pair or a map
 * link pasted into either fills both. Escape puts the car's place back.
 */
export function PositionFields({
  settings,
  car,
  heading,
}: {
  settings: Settings
  car: GpsState | null
  heading: string
}) {
  const saved = settings.value('gps.start')
  const place = placeOf(car, saved)
  const [draft, setDraft] = useState<{ latitude: string; longitude: string } | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  // A point saved by another control (a city) replaces what was typed.
  const [seen, setSeen] = useState(saved)
  if (seen !== saved) {
    setSeen(saved)
    setDraft(null)
    setProblem(null)
  }
  const shown = draft ?? { latitude: place?.latitude ?? '', longitude: place?.longitude ?? '' }
  const error = problem ?? settings.error('gps.start')

  function change(field: 'latitude' | 'longitude', text: string) {
    // A pair or a link has a separator a single number lacks.
    const pasted = /[,\s/]/.test(text.trim()) ? parseCoordinates(text) : null
    setDraft(pasted ?? { ...shown, [field]: text })
  }

  function commit() {
    if (!draft) return
    const [latitude, longitude] = [draft.latitude.trim(), draft.longitude.trim()]
    if (!latitude && !longitude) {
      void settings.save({ 'gps.start': '' })
      return
    }
    const wrong = startProblem(latitude, longitude)
    setProblem(wrong)
    if (wrong) return
    void settings.save({ 'gps.start': `${latitude}, ${longitude}`, 'gps.heading': heading }).then((ok) => {
      if (ok) setDraft(null)
    })
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') commit()
    if (event.key === 'Escape' && draft) {
      event.preventDefault()
      setDraft(null)
      setProblem(null)
    }
  }

  const id = useId()
  // Each field named after it, as a unit is, since a placeholder says nothing once the field is filled.
  const input = (field: 'latitude' | 'longitude') => (
    <>
      <input
        id={`${id}-${field}`}
        className={`t choice-wide ${error ? 'err' : ''}`}
        aria-invalid={error ? true : undefined}
        value={shown[field]}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => change(field, event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={commit}
      />
      <label className="unit" htmlFor={`${id}-${field}`}>
        {field}
      </label>
    </>
  )
  return (
    <>
      {input('latitude')}
      {input('longitude')}
      {error && <span className="errtext">{error}</span>}
    </>
  )
}

/**
 * The car's heading in degrees: which way it faces now, following it as it turns, and the way it turns to when changed
 * (gps.heading). Typing holds the field still while the car moves on; Escape puts the car's heading back.
 */
export function HeadingField({ settings, shown, id }: { settings: Settings; shown: string; id: string }) {
  const [draft, setDraft] = useState<string | null>(null)
  const error = settings.error('gps.heading')

  function commit() {
    if (draft === null) return
    void settings.save({ 'gps.heading': draft.trim() }).then((ok) => {
      if (ok) setDraft(null)
    })
  }

  return (
    <>
      <input
        id={id}
        className={`t short c ${error ? 'err' : ''}`}
        aria-invalid={error ? true : undefined}
        value={draft ?? shown}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') commit()
          if (event.key === 'Escape' && draft !== null) {
            event.preventDefault()
            setDraft(null)
          }
        }}
        onBlur={commit}
      />
      <span className="unit">°</span>
      {error && <span className="errtext">{error}</span>}
    </>
  )
}

/**
 * A wire compass: the four quarters marked, and the car's arrow turned to [heading], short enough to keep a pixel or
 * two clear of the marks.
 */
export function Compass({ heading }: { heading: number }) {
  return (
    <svg
      viewBox="-16 -16 32 32"
      width="26"
      height="26"
      role="img"
      aria-label={`Heading ${heading}°`}
      style={{ flex: 'none' }}
    >
      <circle r="14.5" fill="none" stroke="currentColor" strokeWidth="1" opacity="0.4" />
      {[45, 135, 225, 315].map((angle) => (
        <line
          key={angle}
          y1="-14.5"
          y2="-12.5"
          transform={`rotate(${angle})`}
          stroke="currentColor"
          strokeWidth="1"
          opacity="0.4"
        />
      ))}
      {[0, 90, 180, 270].map((angle) => (
        <line
          data-quarter
          key={angle}
          y1="-14.5"
          y2="-11"
          transform={`rotate(${angle})`}
          stroke="currentColor"
          strokeWidth="1.2"
          opacity="0.7"
        />
      ))}
      <path
        data-arrow
        d="M0 -8.5L4.5 6.3L0 3.6L-4.5 6.3Z"
        transform={`rotate(${heading})`}
        fill="none"
        stroke="var(--accent)"
        strokeWidth="1.3"
        strokeLinejoin="round"
      />
    </svg>
  )
}

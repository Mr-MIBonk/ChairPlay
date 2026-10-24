import { useId, useState, type KeyboardEvent } from 'react'
import type { AppState, GpsState } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { CITIES, cityAt, parseCoordinates } from './cities'
import { Changed, parts, pendingOf } from './parts'

/** What the iPhone learns as it connects: whether there is a GPS, and what it sends besides the position. */
const LINK_KEYS = ['gps.enabled', 'gps.wheel.speed', 'gps.satellites']

/** gps.rate: fixes a second on the tunnel; the iPhone asks for one every 250 ms. */
const RATES: readonly Choice[] = [
  ['1', '1', "DiPlay's own rate"],
  ['4', '4', 'As the iPhone asks'],
  ['10', '10'],
]

/** gps.nmea: the sentences the fixes go in. */
const SENTENCES: readonly Choice[] = [
  ['classic', 'Classic', "DiPlay's $GPGGA and $GPRMC"],
  ['modern', 'Modern', "A current receiver's $GNGGA, $GNRMC, $GNGSA, and $GNVTG"],
]

/**
 * What the iPhone takes, for the GPS group's note; and how the car goes and what drives it, for the Car group's (where
 * it is and which way it faces are in the fields).
 */
function status(car: GpsState, driveKey: string | null | undefined) {
  const updates = `${car.sent} ${car.sent === 1 ? 'update' : 'updates'} sent`
  return {
    iphone: car.listening ? `iPhone listening: ${(car.sentences ?? []).join(', ')}; ${updates}` : 'iPhone not listening',
    going: [
      `${car.speed} km/h`,
      `${car.rpm} rpm`,
      car.gear,
      car.keyboard ? 'Drive mode on' : `Drive mode off${driveKey ? ` (${keyLabel(driveKey)})` : ''}`,
      car.gamepad ? 'Gamepad connected' : 'No gamepad',
    ].join(' · '),
  }
}

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
function placeOf(car: GpsState | null, saved: string) {
  if (car) return car.position ? { latitude: car.position[0].toFixed(5), longitude: car.position[1].toFixed(5) } : null
  return parseCoordinates(saved)
}

/**
 * The car's latitude and longitude, in that order, as map apps give them: where it is now, following it as it drives,
 * and where it goes when they are changed (gps.start, with the heading shown). Both are saved together when either
 * field is left or Enter is pressed; one alone is no place, so the page says what is missing instead. A pair or a map
 * link pasted into either fills both. Escape puts the car's place back.
 */
function PositionFields({ settings, car, heading }: { settings: Settings; car: GpsState | null; heading: string }) {
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
  // Each field named before it, as a placeholder says nothing once the field is filled.
  const input = (field: 'latitude' | 'longitude', label: string) => (
    <>
      <label className="x" htmlFor={`${id}-${field}`}>
        {label}
      </label>
      <input
        id={`${id}-${field}`}
        className={`t w96 ${error ? 'err' : ''}`}
        aria-invalid={error ? true : undefined}
        value={shown[field]}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => change(field, event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={commit}
      />
    </>
  )
  return (
    <>
      {input('latitude', 'Latitude')}
      {input('longitude', 'Longitude')}
      {error && <span className="errtext">{error}</span>}
    </>
  )
}

/**
 * The car's heading in degrees: which way it faces now, following it as it turns, and the way it turns to when changed
 * (gps.heading). Typing holds the field still while the car moves on; Escape puts the car's heading back.
 */
function HeadingField({ settings, shown, id }: { settings: Settings; shown: string; id: string }) {
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
function Compass({ heading }: { heading: number }) {
  return (
    <svg viewBox="-16 -16 32 32" width="26" height="26" role="img" aria-label={`Heading ${heading}°`} style={{ flex: 'none' }}>
      <circle r="14.5" fill="none" stroke="currentColor" strokeWidth="1" opacity="0.4" />
      {[45, 135, 225, 315].map((angle) => (
        <line key={angle} y1="-14.5" y2="-12.5" transform={`rotate(${angle})`} stroke="currentColor" strokeWidth="1" opacity="0.4" />
      ))}
      {[0, 90, 180, 270].map((angle) => (
        <line data-quarter key={angle} y1="-14.5" y2="-11" transform={`rotate(${angle})`} stroke="currentColor" strokeWidth="1.2" opacity="0.7" />
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

/**
 * The spoofed GPS: whether the iPhone navigates by the car ChairPlay drives, and what it is told besides the position;
 * where the car is and which way it faces, following it as it drives, and a city to put it in; the car itself and its
 * gauges; and how it goes and what the iPhone takes.
 */
export function GpsPage({ state, settings }: { state: AppState; settings: Settings }) {
  const pending = pendingOf(state)
  const { field, note } = parts(settings, pending)
  const { value, save } = settings
  const off = value('gps.enabled') !== 'true'
  const driveKey = state.controls?.keys.drive
  const now = state.gps && status(state.gps, driveKey)
  // Where the car is and which way it faces now, while the app runs; else as saved.
  const heading = state.gps ? String(state.gps.heading) : value('gps.heading') || '0'
  const place = placeOf(state.gps, value('gps.start'))
  const ids = useId()
  /** A switch for [key]; [on] is what a blank value means, as the app reads it. */
  const check = (key: string, label: string, title?: string, on = false, experimental = false) => (
    <Row title={title}>
      <Checkbox
        label={label}
        checked={on ? value(key) !== 'false' : value(key) === 'true'}
        onChange={(checked) => save({ [key]: String(checked) })}
      />
      {experimental && <span className="exp">Experimental</span>}
      <Changed keys={[key]} pending={pending} />
    </Row>
  )
  return (
    <>
      <Group title="GPS" note={[now?.iphone, note(LINK_KEYS)].filter(Boolean).join(' · ') || undefined} />
      {check(
        'gps.enabled',
        "Spoof the iPhone's GPS",
        "Send the iPhone a made-up GPS signal in place of the car's: the position of a car ChairPlay drives, with a " +
          `gamepad or with the arrows in drive mode${driveKey ? ` (${keyLabel(driveKey)})` : ''}`,
        false,
        true,
      )}
      {/* Off, the GPS has nothing to set: the rest waits for it. */}
      <div className={off ? 'held' : undefined} inert={off} title={off ? 'Turn on the GPS to set it' : undefined}>
        {check(
          'gps.wheel.speed',
          'Send the vehicle speed',
          "The car's speed ten times a second, as a car's wheel sensors give it, so the iPhone moves on smoothly between position updates",
          true,
        )}
        {check('gps.satellites', 'Send satellites in view', 'A clear sky of ten satellites; try it if the iPhone keeps to its own GPS', false, true)}
        {/* Experiments in what the iPhone takes, from the next fix: how often the position goes, and in which sentences. */}
        <Row label="Fixes" title="How often the car's position goes to the iPhone while it listens, and in which NMEA sentences">
          <Segmented
            label="Fixes"
            className="compact"
            value={value('gps.rate') || '4'}
            options={RATES}
            onChange={(rate) => save({ 'gps.rate': rate })}
          />
          <span className="unit">a second</span>
          <span className="x">Sentences</span>
          <Segmented
            label="Sentences"
            className="compact"
            value={value('gps.nmea') || 'classic'}
            options={SENTENCES}
            onChange={(nmea) => save({ 'gps.nmea': nmea })}
          />
        </Row>
        <Group title="Car" note={now?.going} />
        <Row
          label="Position"
          title="Where the car is now, following it as it drives; change it to move the car there, or paste a map link"
        >
          <PositionFields settings={settings} car={state.gps} heading={heading} />
        </Row>
        <Row label="Heading" htmlFor={`${ids}-heading`} title="Which way the car faces now: 0° is north, 90° east; change it to turn the car">
          <HeadingField settings={settings} shown={heading} id={`${ids}-heading`} />
          <Compass heading={Number(heading) || 0} />
        </Row>
        <Row label="City presets" title="Put the car on a busy road in one of these cities, facing along it">
          <Segmented
            label="City presets"
            className="rows cities"
            value={(place && cityAt(`${place.latitude}, ${place.longitude}`)?.code) ?? ''}
            options={CITIES.map((city) => [city.code, city.code, `${city.name}: ${city.road}`])}
            onChange={(code) => {
              const city = CITIES.find((each) => each.code === code)
              if (city) void save({ 'gps.start': `${city.latitude}, ${city.longitude}`, 'gps.heading': String(city.heading) })
            }}
          />
        </Row>
        <Row title="The car's power and mass, which set how it pulls and how fast it goes">
          {/* Each named before it and its unit after it, as the position's fields */}
          <span className="x">Power</span>
          {field('gps.car.power.hp', 'Power', '150')}
          <span className="unit">hp</span>
          <span className="x">Mass</span>
          {field('gps.car.mass.kg', 'Mass', '1300')}
          <span className="unit">kg</span>
        </Row>
        <Row>
          <span title="Where the car last came to a stop, or where ChairPlay closed, rather than where the fields last put it">
            <Checkbox
              label="Start where the car last stopped"
              checked={value('gps.resume') !== 'false'}
              onChange={(checked) => save({ 'gps.resume': String(checked) })}
            />
          </span>
          <span title="The speedometer and tachometer, while the car is driven and for five seconds after it stops">
            <Checkbox
              label="Show gauges on the AltScreen"
              checked={value('gps.gauges') !== 'false'}
              onChange={(checked) => save({ 'gps.gauges': String(checked) })}
            />
          </span>
        </Row>
      </div>
    </>
  )
}

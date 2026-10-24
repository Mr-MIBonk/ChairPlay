import { useId } from 'react'
import type { AppState, Backend, GpsState } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row, Segmented, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { CITIES, cityAt } from './cities'
import { Compass, HeadingField, placeOf, PositionFields } from './gpsFields'
import { Changed, parts, pendingOf } from './parts'
import { WindowsLocationButton } from './WindowsLocationButton'

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
 * What the iPhone takes, for the iPhone row under the fixes; and how the car goes and what drives it, for the Car
 * group's note (where it is and which way it faces are in the fields).
 */
function status(car: GpsState, driveKey: string | null | undefined) {
  const updates = `${car.sent} ${car.sent === 1 ? 'update' : 'updates'} sent`
  return {
    iphone: car.listening ? `Listening: ${(car.sentences ?? []).join(', ')}; ${updates}` : 'Not listening',
    going: [
      `${car.speed} km/h`,
      `${car.rpm} rpm`,
      car.gear,
      car.keyboard ? 'Drive mode on' : `Drive mode off${driveKey ? ` (${keyLabel(driveKey)})` : ''}`,
      car.gamepad ? 'Gamepad connected' : 'No gamepad',
    ].join(' · '),
  }
}

/**
 * The spoofed GPS: whether the iPhone navigates by the car ChairPlay drives, and what it is told besides the position;
 * where the car is and which way it faces, following it as it drives, and a city to put it in; the car itself and its
 * gauges; and how it goes and what the iPhone takes.
 */
export function GpsPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
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
      <Group title="GPS" note={note(LINK_KEYS)} />
      {check('gps.enabled', "Spoof the iPhone's GPS", undefined, false, true)}
      <Row className="hint-row">
        <span className="hint">
          The iPhone gets a made-up GPS signal in place of the car's: the position of a car ChairPlay drives, with a
          gamepad or with the arrows in drive mode{driveKey ? ` (${keyLabel(driveKey)})` : ''}
        </span>
      </Row>
      {/* Off, the GPS has nothing to set: the rest waits for it. */}
      <div className={off ? 'held' : undefined} inert={off} title={off ? 'Turn on the GPS to set it' : undefined}>
        {check(
          'gps.wheel.speed',
          'Send the vehicle speed',
          "The car's speed ten times a second, as a car's wheel sensors give it, so the iPhone moves on smoothly between position updates",
          true,
        )}
        {check(
          'gps.satellites',
          'Send satellites in view',
          'A clear sky of ten satellites; try it if the iPhone keeps to its own GPS',
          false,
          true,
        )}
        {/* Experiments in what the iPhone takes, from the next fix: how often the position goes, and in which sentences. */}
        <Row label="Fixes" title="How often the car's position goes to the iPhone while it listens">
          <Segmented
            label="Fixes"
            className="compact"
            value={value('gps.rate') || '4'}
            options={RATES}
            onChange={(rate) => save({ 'gps.rate': rate })}
          />
          <span className="unit">a second</span>
        </Row>
        <Row label="Sentences" title="The NMEA sentences the position goes to the iPhone in">
          <Segmented
            label="Sentences"
            className="compact"
            value={value('gps.nmea') || 'classic'}
            options={SENTENCES}
            onChange={(nmea) => save({ 'gps.nmea': nmea })}
          />
        </Row>
        {now && <Row label="iPhone">{now.iphone}</Row>}
        {/* Where the car is, apart from the car itself, whose own settings grow. */}
        <Group title="Position" />
        <Row
          label="Coordinates"
          title="Where the car is now, following it as it drives; change it to move the car there, or paste a map link"
        >
          <PositionFields settings={settings} car={state.gps} heading={heading} />
        </Row>
        <Row
          label="Heading"
          htmlFor={`${ids}-heading`}
          title="Which way the car faces now: 0° is north, 90° east; change it to turn the car"
        >
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
              if (city)
                void save({ 'gps.start': `${city.latitude}, ${city.longitude}`, 'gps.heading': String(city.heading) })
            }}
          />
        </Row>
        <Row>
          <WindowsLocationButton backend={backend} settings={settings} heading={heading} />
        </Row>
        {check(
          'gps.resume',
          'Start where the car last stopped',
          'Where the car last came to a stop, or where ChairPlay closed, rather than where the fields last put it',
          true,
        )}
        <Group title="Car" note={now?.going} />
        <Row label="Power and mass" title="The car's power and mass, which set how it pulls and how fast it goes">
          {field('gps.car.power.hp', 'Power', '150')}
          <span className="unit">hp</span>
          {field('gps.car.mass.kg', 'Mass', '1300')}
          <span className="unit">kg</span>
        </Row>
        {check(
          'gps.gauges',
          'Show gauges on the AltScreen',
          'The speedometer and tachometer, while the car is driven and for five seconds after it stops',
          true,
        )}
      </div>
    </>
  )
}

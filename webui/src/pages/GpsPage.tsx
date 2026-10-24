import type { AppState, GpsState } from '../api'
import { keyLabel } from '../ControlsScreen'
import { Checkbox, Group, Row } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { Changed, parts, pendingOf } from './parts'

/** What the iPhone learns as it connects: whether there is a GPS, and what it sends besides the position. */
const LINK_KEYS = ['gps.enabled', 'gps.wheel.speed', 'gps.satellites']

/** The car's state in a few words a row, for the Now group, and what drives it for the group's note. */
function status(car: GpsState, driveKey: string | null | undefined) {
  const updates = `${car.sent} ${car.sent === 1 ? 'update' : 'updates'} sent`
  const position = car.position ? `${car.position[0].toFixed(5)}, ${car.position[1].toFixed(5)}` : 'No start point'
  return {
    iphone: car.listening ? `Listening: ${(car.sentences ?? []).join(', ')}; ${updates}` : null,
    driving: `${position} · ${car.heading}° · ${car.speed} km/h · ${car.rpm} rpm · ${car.gear}`,
    controls: [
      car.keyboard ? 'Drive mode on' : `Drive mode off${driveKey ? ` (${keyLabel(driveKey)})` : ''}`,
      car.gamepad ? 'Gamepad connected' : 'No gamepad',
    ].join(' · '),
  }
}

/**
 * The spoofed GPS: whether the iPhone navigates by the car ChairPlay drives, and what it is told besides the position;
 * where the car starts; the car itself and its gauges; and what the car and the iPhone are doing now.
 */
export function GpsPage({ state, settings }: { state: AppState; settings: Settings }) {
  const pending = pendingOf(state)
  const { field, note } = parts(settings, pending)
  const { value, save } = settings
  const off = value('gps.enabled') !== 'true'
  const driveKey = state.controls?.keys.drive
  const now = state.gps && status(state.gps, driveKey)
  /** A switch for [key]; [on] is what a blank value means, as the app reads it. */
  const check = (key: string, label: string, title?: string, on = false) => (
    <Row title={title}>
      <Checkbox
        label={label}
        checked={on ? value(key) !== 'false' : value(key) === 'true'}
        onChange={(checked) => save({ [key]: String(checked) })}
      />
      <Changed keys={[key]} pending={pending} />
    </Row>
  )
  return (
    <>
      <Group title="GPS" note={note(LINK_KEYS)} />
      {check(
        'gps.enabled',
        "Send the car's position to the iPhone",
        `The iPhone navigates by a car ChairPlay drives: with a gamepad, or with the arrows in drive mode${driveKey ? ` (${keyLabel(driveKey)})` : ''}`,
      )}
      {/* Off, the GPS has nothing to set: the rest waits for it. */}
      <div className={off ? 'held' : undefined} inert={off} title={off ? 'Turn on the GPS to set it' : undefined}>
        <Row title="The wheel speed: the car's speed ten times a second, so the iPhone moves on smoothly between position updates. Satellites in view: a clear sky of ten; try it if the iPhone keeps to its own GPS">
          <Checkbox
            label="Send the wheel speed"
            checked={value('gps.wheel.speed') !== 'false'}
            onChange={(checked) => save({ 'gps.wheel.speed': String(checked) })}
          />
          <Checkbox
            label="Send satellites in view"
            checked={value('gps.satellites') === 'true'}
            onChange={(checked) => save({ 'gps.satellites': String(checked) })}
          />
          <span className="exp">Experimental</span>
          <Changed keys={['gps.wheel.speed', 'gps.satellites']} pending={pending} />
        </Row>
        <Group title="Car" />
        <Row
          label="Start point"
          title="Where the car starts, a latitude and longitude or a link a map app shares, and which way it faces: 0° is north, 90° east"
        >
          {field('gps.start', 'Start point', 'Latitude, longitude', 'w280')}
          {field('gps.heading', 'Heading', '0', 'short c')}
          <span className="unit">°</span>
        </Row>
        {check('gps.resume', 'Start where the car last stopped', 'Where ChairPlay was closed, rather than at the start point', true)}
        <Row label="Power and mass" title="The car's power and mass, which set how it pulls and how fast it goes">
          {field('gps.car.power.hp', 'Power', '150')}
          <span className="unit">hp</span>
          {field('gps.car.mass.kg', 'Mass', '1300')}
          <span className="unit">kg</span>
        </Row>
        {check(
          'gps.gauges',
          'Show the speedometer and tachometer on the AltScreen',
          'While the car is driven, and for five seconds after it stops',
          true,
        )}
        {now && (
          <>
            <Group title="Now" note={now.controls} />
            <Row label="iPhone">{now.iphone ?? <span className="hint">Not listening</span>}</Row>
            <Row label="Driving">{now.driving}</Row>
          </>
        )}
      </div>
    </>
  )
}

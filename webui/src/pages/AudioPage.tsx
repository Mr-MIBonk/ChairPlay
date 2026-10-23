import type { AppState } from '../api'
import { Checkbox, Group, Row, Select, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { Changed, parts, pendingOf } from './parts'

/**
 * A device list: the Windows default first, then the devices Java Sound lists. A saved device the list lacks (one
 * unplugged, or a part of a name written in the file) stays as chosen, marked as not found.
 */
function deviceChoices(devices: readonly string[], saved: string): Choice[] {
  const choices: Choice[] = [['default', 'Windows default'], ...devices.map((name): Choice => [name, name])]
  if (saved && saved !== 'default' && !devices.includes(saved)) choices.push([saved, `${saved} (not found)`])
  return choices
}

/** Where CarPlay's sound goes and where the microphone for calls and Siri comes from. */
export function AudioPage({ state, settings }: { state: AppState; settings: Settings }) {
  const { value, save, data } = settings
  const pending = pendingOf(state)
  const { note } = parts(settings, pending)
  const devices = data?.audio ?? { outputs: [], inputs: [] }
  const device = (key: string, label: string, names: readonly string[]) => (
    <Row label={label}>
      <Select
        label={label}
        className="w280"
        value={value(key) || 'default'}
        options={deviceChoices(names, value(key))}
        onChange={(chosen) => save({ [key]: chosen })}
      />
      <Changed keys={[key]} pending={pending} />
      {settings.error(key) && <span className="errtext">{settings.error(key)}</span>}
    </Row>
  )
  return (
    <>
      <Group title="Devices" note={note(['audio.output', 'audio.input', 'audio.echo.cancellation'])} />
      {device('audio.output', 'Output', devices.outputs)}
      {device('audio.input', 'Microphone', devices.inputs)}
      <Row>
        <Checkbox
          label="Echo cancellation for calls through speakers"
          checked={value('audio.echo.cancellation') === 'true'}
          onChange={(checked) => save({ 'audio.echo.cancellation': String(checked) })}
        />
        <span className="exp">Experimental</span>
        <Changed keys={['audio.echo.cancellation']} pending={pending} />
      </Row>
    </>
  )
}

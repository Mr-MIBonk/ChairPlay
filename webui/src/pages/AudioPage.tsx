import type { AppState, Backend } from '../api'
import { Checkbox, Group, Row, Select, Slider, type Choice } from '../shell/controls'
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

/**
 * How loud CarPlay plays, where its sound goes, where the microphone for calls and Siri comes from, how the music fades
 * in and out, and how low navigation prompts put it.
 */
export function AudioPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const { value, save, data } = settings
  const pending = pendingOf(state)
  const { field, note } = parts(settings, pending)
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
  const volume = state.controls?.volume
  return (
    <>
      <Group title="Devices" note={note(['audio.output', 'audio.input', 'audio.echo.cancellation'])} />
      {device('audio.output', 'Output', devices.outputs)}
      {volume && (
        <Row
          label="Volume"
          title="ChairPlay's own volume, as the volume keys and the controls window set it; Windows' own stays as it is"
        >
          <Slider
            label="Volume"
            value={volume.level}
            idle={volume.muted ? 'Muted' : undefined}
            onChange={(level) => backend.volume(level)}
          />
        </Row>
      )}
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
      <Group title="Music fade" note="From the next stream" />
      <Row
        label="Buffering pause"
        title="The iPhone's first second or two of music come in bursts: how long the music stays silent while they buffer"
      >
        {field('audio.start.silence.ms', 'Buffering pause', '1200')}
        <span className="unit">ms</span>
      </Row>
      <Row label="Fade" title="How long the music takes to rise after the pause, and to fade out as CarPlay disconnects or ChairPlay closes">
        {field('audio.start.fade.ms', 'Fade', '800')}
        <span className="unit">ms</span>
      </Row>
      <Group title="Navigation prompts" note="From the next prompt" />
      <Row label="Music at" title="How loud the music stays under a prompt, in percent of its volume: 30 is about -10 dB">
        <Slider
          label="Music at"
          step={5}
          value={Number(value('audio.duck.percent') || '30')}
          onChange={(level) => save({ 'audio.duck.percent': String(level) })}
        />
        <Changed keys={['audio.duck.percent']} pending={pending} />
      </Row>
      <Row title="Use the level the iPhone sends with a prompt; Music at applies when it sends none">
        <Checkbox
          label="Prefer the iPhone's level"
          checked={value('audio.duck.iphone') !== 'false'}
          onChange={(checked) => save({ 'audio.duck.iphone': String(checked) })}
        />
        <Changed keys={['audio.duck.iphone']} pending={pending} />
      </Row>
    </>
  )
}

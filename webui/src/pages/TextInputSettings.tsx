import type { AppState } from '../api'
import { Group, Row, Switch } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { Changed, pendingOf } from './parts'

export function TextInputSettings({ state, settings }: { state: AppState; settings: Settings }) {
  return (
    <>
      <Group title="Text input" note="Reconnect to apply" />
      <Row title="Use your keyboard when CarPlay requests text input. Letter shortcuts pause while typing. Text follows the iPhone's keyboard layout.">
        <Switch
          label="Type in CarPlay"
          checked={settings.value('keyboard.text.input') !== 'false'}
          onChange={(checked) => void settings.save({ 'keyboard.text.input': String(checked) })}
        />
        <Changed keys={['keyboard.text.input']} pending={pendingOf(state)} />
        {settings.error('keyboard.text.input') && <span className="errtext">{settings.error('keyboard.text.input')}</span>}
      </Row>
    </>
  )
}

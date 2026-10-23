import type { AppState, Backend } from '../api'
import { Checkbox, Field, Group, Later, PasswordField, Row, Select, type Choice } from '../shell/controls'
import type { Settings } from '../shell/useSettings'
import { ErrorMessage, SetupMessages } from './messages'

/** wifi.security as DesktopConfig names it, and as the page shows it. */
export const SECURITY_CHOICES: readonly Choice[] = [
  ['WPA_WPA2', 'WPA/WPA2'],
  ['WPA3_TRANSITION', 'WPA2/WPA3'],
  ['WPA3_ONLY', 'WPA3 only'],
  ['WEP', 'WEP'],
  ['NONE', 'Open'],
]

/** What is wrong, if anything; then the Wi-Fi the iPhone joins, and how the iPhone is called and shown. */
export function ConnectionPage({ state, backend, settings }: { state: AppState; backend: Backend; settings: Settings }) {
  const { value, error, save } = settings
  const commit = (key: string) => (text: string) => save({ [key]: text })
  // The page says what the app says: a mark beside each setting it applies only after a restart.
  const later = (key: string) => settings.data?.applies[key] === 'restart' && <Later />
  const field = (key: string, label: string, placeholder: string, className = 'w200') => (
    <Field label={label} className={className} value={value(key)} placeholder={placeholder} error={error(key)} onCommit={commit(key)} />
  )
  return (
    <>
      <ErrorMessage state={state} backend={backend} />
      <SetupMessages state={state} backend={backend} />
      <Group title="Wi-Fi" note="Applies at the next connection" />
      <Row label="Network">
        {field('wifi.ssid', 'Network', 'Network name')}
        <Select
          label="Security"
          className="w120"
          value={value('wifi.security') || 'WPA_WPA2'}
          options={SECURITY_CHOICES}
          onChange={commit('wifi.security')}
        />
        {error('wifi.security') && <span className="errtext">{error('wifi.security')}</span>}
      </Row>
      <Row label="Password">
        <PasswordField
          label="Password"
          set={state.wifi.passphraseSet}
          error={error('wifi.passphrase')}
          onCommit={commit('wifi.passphrase')}
        />
      </Row>
      <Group title="iPhone" />
      <Row label="Name on iPhone" title="The name the iPhone shows for this car in CarPlay settings and in its Wi-Fi list">
        {field('device.name', 'Name on iPhone', 'ChairPlay')}
        {later('device.name')}
      </Row>
      <Row label="Bluetooth address" title="Only needed when more than one iPhone is paired">
        {field('iphone.address', 'Bluetooth address', 'Any paired iPhone')}
        {later('iphone.address')}
      </Row>
      <Row label="Time to join Wi-Fi" title="How long a known iPhone has to join the Wi-Fi before ChairPlay tries again">
        {field('iphone.join.seconds', 'Time to join Wi-Fi', '30', 'n')}
        <span className="unit">s</span>
        {later('iphone.join.seconds')}
      </Row>
      <Row>
        <Checkbox
          label="Connect when ChairPlay starts"
          checked={value('ui.autoconnect') !== 'false'}
          onChange={(checked) => save({ 'ui.autoconnect': String(checked) })}
        />
        {later('ui.autoconnect')}
      </Row>
      <Row>
        <Checkbox
          label="Show CarPlay as soon as the iPhone connects"
          checked={value('ui.autoshow') !== 'false'}
          onChange={(checked) => save({ 'ui.autoshow': String(checked) })}
        />
        {later('ui.autoshow')}
      </Row>
    </>
  )
}

import type { AppState } from './api'

export type Tone = 'ok' | 'busy' | 'warn' | 'idle'

export interface Status {
  title: string
  detail: string
  tone: Tone
}

/** The start screen's headline for where the connection stands. */
export function describe(state: Pick<AppState, 'phase' | 'knownIphone'>): Status {
  switch (state.phase) {
    case 'setup':
      return { title: 'Wi-Fi network needed', detail: 'Enter the Wi-Fi network this PC and the iPhone share.', tone: 'warn' }
    case 'idle':
      return { title: 'Not connected', detail: 'Press Connect when the iPhone is nearby.', tone: 'idle' }
    case 'starting':
      return { title: 'Starting', detail: 'Preparing the Wi-Fi network and the CarPlay service.', tone: 'busy' }
    case 'calling':
      return {
        title: 'Waiting for the iPhone',
        detail: 'Calling the paired iPhone over Bluetooth. Keep its Bluetooth and Wi-Fi on.',
        tone: 'busy',
      }
    case 'joining':
      return {
        title: 'Connecting',
        detail: state.knownIphone
          ? 'The iPhone answered and is joining the Wi-Fi network.'
          : 'The iPhone answered. Accept CarPlay on the iPhone if it asks; it then joins the Wi-Fi network.',
        tone: 'busy',
      }
    case 'active':
      return { title: 'Connected', detail: 'CarPlay is running.', tone: 'ok' }
    case 'retrying':
      return { title: 'Trying again', detail: 'The last attempt failed; the next one starts in a few seconds.', tone: 'warn' }
  }
}

/** A hint for an error the bring-up reported, or null when there is none to give. */
export function errorHint(message: string, setup: Pick<AppState['setup'], 'mfiFolder'>): string | null {
  const text = message.toLowerCase()
  if (text.includes('mfi') || text.includes('identity.pk8') || text.includes('certificate.p7b')) {
    return `Put the MFi identity files (identity.pk8 and certificate.p7b) into ${setup.mfiFolder}.`
  }
  if (text.includes('rfcomm') || text.includes('bluetooth') || text.includes('paired')) {
    return 'Check that the iPhone is paired in Windows Bluetooth settings, nearby, and has Bluetooth on.'
  }
  if (text.includes('wi-fi') || text.includes('wlan') || text.includes('network') || text.includes('ssid')) {
    return 'Check the Wi-Fi network below, and that this PC is connected to it.'
  }
  return null
}

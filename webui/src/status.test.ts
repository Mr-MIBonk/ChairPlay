import { describe as group, expect, it } from 'vitest'
import { describe, errorHint, errorKind } from './status'

group('describe', () => {
  it('names each phase of the connection', () => {
    expect(describe({ phase: 'setup', knownIphone: false }).title).toBe('Wi-Fi network needed')
    expect(describe({ phase: 'idle', knownIphone: false }).title).toBe('Not connected')
    expect(describe({ phase: 'calling', knownIphone: false }).title).toBe('Waiting for the iPhone')
    expect(describe({ phase: 'active', knownIphone: false })).toEqual({ title: 'Connected', detail: 'CarPlay is running.', tone: 'ok' })
    expect(describe({ phase: 'retrying', knownIphone: false }).tone).toBe('warn')
  })

  it('asks a new iPhone to accept CarPlay, and not a known one', () => {
    expect(describe({ phase: 'joining', knownIphone: false }).detail).toContain('Accept CarPlay')
    expect(describe({ phase: 'joining', knownIphone: true }).detail).not.toContain('Accept CarPlay')
  })
})

group('errorKind', () => {
  it('tells what a bring-up error is about', () => {
    expect(errorKind('Offline MFi directory is missing or invalid')).toBe('mfi')
    expect(errorKind('RFCOMM connect failed: 10060')).toBe('bluetooth')
    expect(errorKind('no paired iPhone found')).toBe('bluetooth')
    expect(errorKind('Wi-Fi adapter has no usable address')).toBe('wifi')
    expect(errorKind('Connection reset')).toBe('other')
  })

  // The iPhone answered over Bluetooth and then did not come onto the network: a Wi-Fi problem, most often the password.
  it('takes a join that timed out for a Wi-Fi problem, though it names Bluetooth', () => {
    expect(errorKind('Bluetooth control ended TIMEOUT before the iPhone joined over Wi-Fi')).toBe('join')
  })
})

group('errorHint', () => {
  const setup = { mfiFolder: 'C:\\ChairPlay\\offline-mfi' }

  it('points missing MFi files to their folder', () => {
    expect(errorHint('Offline MFi directory is missing or invalid', setup)).toContain('C:\\ChairPlay\\offline-mfi')
  })

  it('points Bluetooth and Wi-Fi failures to their settings', () => {
    expect(errorHint('RFCOMM connect failed: 10060', setup)).toContain('Bluetooth')
    expect(errorHint('no Wi-Fi network is set: enter it on the start screen (wifi.ssid)', setup)).toContain('Wi-Fi')
  })

  it('stays quiet when it knows nothing better', () => {
    expect(errorHint('Connection reset', setup)).toBeNull()
  })
})

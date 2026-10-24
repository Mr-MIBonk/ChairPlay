import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { FakeBackend } from '../fakeBackend'
import { useSettings } from './useSettings'

afterEach(cleanup)

async function loaded(backend: FakeBackend) {
  const hook = renderHook(() => useSettings(backend))
  await waitFor(() => expect(hook.result.current.data).not.toBeNull())
  return hook
}

describe('useSettings', () => {
  it('reads the settings, blank for one the app does not have', async () => {
    const { result } = await loaded(new FakeBackend())

    expect(result.current.value('wifi.ssid')).toBe('Home')
    expect(result.current.value('device.name')).toBe('')
  })

  it('takes a saved value as the new one', async () => {
    const backend = new FakeBackend()
    const { result } = await loaded(backend)

    let saved = false
    await act(async () => {
      saved = await result.current.save({ 'wifi.ssid': 'Car' })
    })

    expect(saved).toBe(true)
    expect(backend.saved).toEqual([{ 'wifi.ssid': 'Car' }])
    expect(result.current.value('wifi.ssid')).toBe('Car')
  })

  it('keeps the saved value and gives the reason when the app refuses one', async () => {
    const backend = new FakeBackend()
    const { result } = await loaded(backend)
    backend.nextOutcome = { ok: false, errors: { 'wifi.ssid': 'The name is at most 32 bytes' } }

    await act(async () => {
      await result.current.save({ 'wifi.ssid': 'x'.repeat(40) })
    })

    expect(result.current.value('wifi.ssid')).toBe('Home')
    expect(result.current.error('wifi.ssid')).toBe('The name is at most 32 bytes')

    await act(async () => {
      await result.current.save({ 'wifi.ssid': 'Car' })
    })
    expect(result.current.error('wifi.ssid')).toBeUndefined()
  })

  // The password never comes back from the app, so it is not kept on the page either.
  it('does not keep a saved password', async () => {
    const { result } = await loaded(new FakeBackend())

    await act(async () => {
      await result.current.save({ 'wifi.passphrase': 'secret123' })
    })

    expect(result.current.value('wifi.passphrase')).toBe('')
  })

  it('gives the reason under each key when saving fails outright', async () => {
    const backend = new FakeBackend()
    const { result } = await loaded(backend)
    backend.save = async () => {
      throw new Error('saving failed (500)')
    }

    await act(async () => {
      await result.current.save({ 'device.name': 'Car' })
    })

    expect(result.current.error('device.name')).toBe('saving failed (500)')
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { Api } from './api'

afterEach(() => vi.unstubAllGlobals())

/** Stubs fetch to answer [status] and records each request. */
function answer(status: number) {
  const requests: { url: string; init: RequestInit }[] = []
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    requests.push({ url, init })
    return new Response('{}', { status })
  })
  return requests
}

describe('Api', () => {
  it('asks the app to show or hide a window', async () => {
    const requests = answer(200)

    expect(await new Api('key').window('controls', false)).toBe(true)

    expect(requests).toHaveLength(1)
    expect(requests[0].url).toBe('/api/window')
    expect(requests[0].init.method).toBe('POST')
    expect(JSON.parse(String(requests[0].init.body))).toEqual({ name: 'controls', visible: false })
  })

  it('tells when a window is not available', async () => {
    answer(404)

    expect(await new Api('key').window('altscreen', true)).toBe(false)
  })

  it('asks the app to disconnect', async () => {
    const requests = answer(200)

    await new Api('key').disconnect()

    expect(requests[0].url).toBe('/api/disconnect')
    expect(requests[0].init.method).toBe('POST')
  })

  it('asks the app to bring the main window to the actual size', async () => {
    const requests = answer(200)

    expect(await new Api('key').actualSize()).toBe(true)

    expect(requests[0].url).toBe('/api/window-size')
    expect(JSON.parse(String(requests[0].init.body))).toEqual({ actual: true })
  })

  // A link to a Windows page names the place; the app knows what to start for it.
  it('asks the app to open a Windows page', async () => {
    const requests = answer(200)

    expect(await new Api('key').open('bluetooth')).toBe(true)

    expect(requests[0].url).toBe('/api/open')
    expect(requests[0].init.method).toBe('POST')
    expect(JSON.parse(String(requests[0].init.body))).toEqual({ place: 'bluetooth' })
  })

  it('tells when a Windows page did not open', async () => {
    answer(404)

    expect(await new Api('key').open('firewall-rules')).toBe(false)
  })
})

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { Settings } from '../shell/useSettings'
import { CityPresets } from './CityPresets'

afterEach(cleanup)

/** Settings with [values], recording what is saved. */
function settingsWith(values: Record<string, string>) {
  const saved: Record<string, string>[] = []
  const settings: Settings = {
    data: null,
    loadError: null,
    value: (key) => values[key] ?? '',
    error: () => undefined,
    save: async (changes) => {
      saved.push(changes)
      return true
    },
  }
  return { settings, saved }
}

describe('City presets', () => {
  // A city puts the car on one of its busy roads, facing along it; the city it stands in is the one chosen.
  it('puts the car in a city from its presets', () => {
    const { settings, saved } = settingsWith({})
    render(<CityPresets settings={settings} place={{ latitude: '48.87604', longitude: '2.28792' }} />)

    expect(screen.getByRole('radio', { name: 'PAR' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'NYC' }))

    expect(saved).toEqual([{ 'gps.start': '40.74389, -74.00841', 'gps.heading': '7' }])
  })
})

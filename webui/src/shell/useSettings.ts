import { useEffect, useState } from 'react'
import type { Backend, SettingsData } from '../api'

/** The settings the pages show and change, shared by all of them so a page switch does not read them again. */
export interface Settings {
  data: SettingsData | null
  loadError: string | null
  /** The saved value; blank for a setting not in the file, which then has its default. */
  value(key: string): string
  /** Why the app refused the last value sent for [key]. */
  error(key: string): string | undefined
  /** Saves [changes] together; true when the app took them. */
  save(changes: Record<string, string>): Promise<boolean>
}

export function useSettings(backend: Backend): Settings {
  const [data, setData] = useState<SettingsData | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})

  useEffect(() => {
    backend.settings().then(setData, (error) => setLoadError(error instanceof Error ? error.message : String(error)))
  }, [backend])

  async function save(changes: Record<string, string>) {
    const keys = Object.keys(changes)
    const clearing = (current: Record<string, string>) =>
      Object.fromEntries(Object.entries(current).filter(([key]) => !keys.includes(key)))
    let refused: Record<string, string>
    try {
      const outcome = await backend.save(changes)
      if (outcome.ok) {
        // The password never comes back from the app, so the page keeps it no longer than the field does.
        const kept = Object.fromEntries(Object.entries(changes).filter(([key]) => key !== 'wifi.passphrase'))
        setData((current) => current && { ...current, values: { ...current.values, ...kept } })
        setErrors(clearing)
        return true
      }
      refused = outcome.errors
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      refused = Object.fromEntries(keys.map((key) => [key, message]))
    }
    setErrors((current) => ({ ...clearing(current), ...refused }))
    return false
  }

  return {
    data,
    loadError,
    value: (key) => data?.values[key] ?? '',
    error: (key) => errors[key],
    save,
  }
}

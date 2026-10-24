import { useState } from 'react'
import type { Backend } from '../api'
import type { Settings } from '../shell/useSettings'

/**
 * Puts the GPS car where Windows locates this PC, facing [heading] as it does now, as typed coordinates do. Without a
 * place it says why: location off or not let for desktop apps, with a way to its settings, or no fix yet.
 */
export function WindowsLocationButton({
  backend,
  settings,
  heading,
}: {
  backend: Backend
  settings: Settings
  heading: string
}) {
  const [asking, setAsking] = useState(false)
  const [problem, setProblem] = useState<'denied' | 'unknown' | null>(null)

  async function locate() {
    setAsking(true)
    setProblem(null)
    try {
      const found = await backend.windowsLocation()
      if ('error' in found) setProblem(found.error)
      else void settings.save({ 'gps.start': `${found.latitude}, ${found.longitude}`, 'gps.heading': heading })
    } finally {
      setAsking(false)
    }
  }

  return (
    <>
      <button
        type="button"
        className="btn small"
        title="Put the car where Windows locates this PC"
        disabled={asking}
        onClick={() => void locate()}
      >
        Use Windows location
      </button>
      {problem === 'denied' && (
        <span className="errtext">
          Windows does not share this PC's location. Turn it on for desktop apps in{' '}
          <button type="button" className="link" onClick={() => void backend.open('location')}>
            Location settings
          </button>
        </span>
      )}
      {problem === 'unknown' && <span className="errtext">Windows found no position. Try again in a moment.</span>}
    </>
  )
}

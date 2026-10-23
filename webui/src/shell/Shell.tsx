import { useEffect, useId, useState } from 'react'
import type { AppState, Backend } from '../api'
import { AboutPage } from '../pages/AboutPage'
import { AltScreenPage } from '../pages/AltScreenPage'
import { AudioPage } from '../pages/AudioPage'
import { CarPlayPage } from '../pages/CarPlayPage'
import { ConnectionPage } from '../pages/ConnectionPage'
import { Message } from './controls'
import { SECTIONS, sectionsOf, type Section } from './sections'
import { Toolbar } from './Toolbar'
import { useSettings } from './useSettings'

export type { Section } from './sections'

/**
 * The main window's page whenever CarPlay does not cover it: the toolbar, the section list, and the chosen
 * section's settings. Escape shows CarPlay when it can, unless a page used the key itself.
 */
export function Shell({ state, backend }: { state: AppState; backend: Backend }) {
  const [section, setSection] = useState<Section>('connection')
  const ids = useId()
  const showable = state.canShowCarPlay && state.embedded
  const restart = new Set(sectionsOf(state.restartPending))
  const settings = useSettings(backend)

  useEffect(() => {
    if (!showable) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) backend.show('carplay')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [backend, showable])

  return (
    <div className="shell">
      <Toolbar state={state} backend={backend} />
      <div className="body">
        <nav className="nav" role="tablist" aria-orientation="vertical" aria-label="Settings">
          {SECTIONS.map(({ id, title, icon }) => (
            <button
              key={id}
              type="button"
              role="tab"
              id={`${ids}-tab-${id}`}
              aria-controls={`${ids}-page`}
              aria-selected={section === id}
              className={section === id ? 'sel' : ''}
              onClick={() => setSection(id)}
            >
              {icon}
              {title}
              {restart.has(id) && (
                <span className="badge" title="Applies after restart">
                  ⟳
                </span>
              )}
            </button>
          ))}
        </nav>
        <div className="page" role="tabpanel" id={`${ids}-page`} aria-labelledby={`${ids}-tab-${section}`}>
          <div className="page-inner">
            {settings.loadError && <Message tone="bad" head="Couldn't read the settings." text={settings.loadError} />}
            {settings.data && section === 'connection' && <ConnectionPage state={state} backend={backend} settings={settings} />}
            {settings.data && section === 'carplay' && <CarPlayPage state={state} backend={backend} settings={settings} />}
            {settings.data && section === 'altscreen' && <AltScreenPage settings={settings} />}
            {settings.data && section === 'audio' && <AudioPage settings={settings} />}
            {section === 'about' && <AboutPage />}
          </div>
        </div>
      </div>
    </div>
  )
}

import type { ReactNode } from 'react'
import { AboutIcon, AltScreenLineIcon, AudioIcon, CarPlayLineIcon, KeyboardIcon, WifiIcon } from './icons'

/** The settings' pages, in the section list's order; the first opens. */
export const SECTIONS = [
  { id: 'connection', title: 'Connection', icon: <WifiIcon /> },
  { id: 'carplay', title: 'CarPlay', icon: <CarPlayLineIcon /> },
  { id: 'altscreen', title: 'AltScreen', icon: <AltScreenLineIcon /> },
  { id: 'keyboard', title: 'Keyboard', icon: <KeyboardIcon /> },
  { id: 'audio', title: 'Audio', icon: <AudioIcon /> },
  { id: 'about', title: 'About', icon: <AboutIcon /> },
] as const satisfies readonly { id: string; title: string; icon: ReactNode }[]

export type Section = (typeof SECTIONS)[number]['id']

/** The page a setting of settings.properties or controls.properties is on. */
export function sectionOf(key: string): Section {
  if (key.startsWith('display.') || key.startsWith('window.')) return 'carplay'
  if (key.startsWith('altscreen.')) return 'altscreen'
  if (key.startsWith('audio.')) return 'audio'
  if (key.startsWith('key.')) return 'keyboard'
  return 'connection'
}

/** The pages with a setting in [keys], in the section list's order. */
export function sectionsOf(keys: readonly string[]): Section[] {
  const sections = new Set(keys.map(sectionOf))
  return SECTIONS.map(({ id }) => id).filter((id) => sections.has(id))
}

import { useEffect } from 'react'
import type { Backend, KeyPhase } from '../api'
import { bindingName, isModifier } from '../pages/keyNames'

/** Whether [target] takes what is typed: a field, a list or editable text. */
function typesHere(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

/**
 * A page has the keyboard while it shows, so the window's keys would never reach the app: they are handed on, as
 * pressed on the window ([Backend.key]). [everything] over the controls window, which has nothing to type in or move
 * about; over the settings the app takes only its own keys. A key the page used (preventDefault) is the page's, and
 * so is a character typed in a field.
 */
export function useKeysToApp(backend: Backend, everything: boolean) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || isModifier(event.code)) return
      if (typesHere(event.target) && event.key.length === 1 && !event.ctrlKey && !event.altKey) return
      const name = bindingName(event)
      if (!name) return
      const phase: KeyPhase = event.type === 'keyup' ? 'release' : event.repeat ? 'repeat' : 'press'
      void backend.key(name, phase, everything)
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('keyup', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('keyup', onKey)
    }
  }, [backend, everything])
}

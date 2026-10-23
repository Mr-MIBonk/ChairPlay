import { afterEach, describe, expect, it } from 'vitest'
import { applyAccent } from './accent'

afterEach(() => document.documentElement.style.removeProperty('--accent'))

describe('applyAccent', () => {
  // ui.accent colours both pages: every shade in the stylesheet is made from --accent.
  it('puts the accent colour where the stylesheet reads it', () => {
    applyAccent('#30d158')

    expect(document.documentElement.style.getPropertyValue('--accent')).toBe('#30d158')
  })

  it('keeps the stylesheet own colour for anything that is not #rrggbb', () => {
    applyAccent('#30d158')
    applyAccent('url(evil)')
    applyAccent(undefined)

    expect(document.documentElement.style.getPropertyValue('--accent')).toBe('#30d158')
  })
})

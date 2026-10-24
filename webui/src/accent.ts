/**
 * Colours the page with ui.accent: every shade of the accent in the stylesheets (styles/base.css) is made from --accent, so setting it on
 * the root recolours both pages. Anything but #rrggbb leaves the stylesheet's own colour.
 */
export function applyAccent(accent: string | undefined) {
  if (accent && /^#[0-9a-fA-F]{6}$/.test(accent)) document.documentElement.style.setProperty('--accent', accent)
}

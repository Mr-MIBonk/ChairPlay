// The controls window's icons, drawn on a 16-pixel grid in the button's text colour.
import type { ReactNode } from 'react'

function Icon({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true">
      {children}
    </svg>
  )
}

const line = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' } as const

export const icons = {
  up: <Icon><path d="M4 10l4-4 4 4" {...line} /></Icon>,
  down: <Icon><path d="M4 6l4 4 4-4" {...line} /></Icon>,
  left: <Icon><path d="M10 4L6 8l4 4" {...line} /></Icon>,
  right: <Icon><path d="M6 4l4 4-4 4" {...line} /></Icon>,
  // A turn: a short arc with its head, not a full circle; the wings turn it along the bezel
  turnBack: <Icon><path d="M13 11Q11.5 4.5 4 5.6M6.4 3L3.6 5.7l3.3 2.1" {...line} strokeWidth={1.5} /></Icon>,
  turnForward: <Icon><path d="M3 11Q4.5 4.5 12 5.6M9.6 3l2.8 2.7-3.3 2.1" {...line} strokeWidth={1.5} /></Icon>,
  back: <Icon><path d="M6 4L2 8l4 4M2 8h9a3 3 0 0 1 0 6H9" {...line} strokeWidth={1.5} /></Icon>,
  home: <Icon><path d="M2.5 7.5L8 3l5.5 4.5V13h-4V9.5h-3V13h-4z" {...line} strokeWidth={1.4} /></Icon>,
  siri: (
    <Icon>
      <rect x="6" y="2" width="4" height="8" rx="2" {...line} strokeWidth={1.4} />
      <path d="M3.5 8a4.5 4.5 0 0 0 9 0M8 12.5V14" {...line} strokeWidth={1.4} />
    </Icon>
  ),
  previous: <Icon><path d="M3 3v10M13 3L5 8l8 5z" fill="currentColor" stroke="currentColor" strokeWidth={1.2} strokeLinejoin="round" /></Icon>,
  playPause: (
    <Icon>
      <path d="M3 3l6 5-6 5z" fill="currentColor" />
      <path d="M11 3v10M14 3v10" stroke="currentColor" strokeWidth={1.6} />
    </Icon>
  ),
  next: <Icon><path d="M13 3v10M3 3l8 5-8 5z" fill="currentColor" stroke="currentColor" strokeWidth={1.2} strokeLinejoin="round" /></Icon>,
  volumeDown: (
    <Icon>
      <path d="M1.5 6h2.6L7.5 3v10L4.1 10H1.5z" fill="currentColor" />
      <path d="M10 8h4.5" {...line} />
    </Icon>
  ),
  mute: (
    <Icon>
      <path d="M1.5 6h2.6L7.5 3v10L4.1 10H1.5z" fill="currentColor" />
      <path d="M10 5.8l4.4 4.4M14.4 5.8L10 10.2" {...line} />
    </Icon>
  ),
  volumeUp: (
    <Icon>
      <path d="M1.5 6h2.6L7.5 3v10L4.1 10H1.5z" fill="currentColor" />
      <path d="M10 8h4.5M12.25 5.75v4.5" {...line} />
    </Icon>
  ),
  answer: <Icon><path d="M4 2.5l2 3-1.3 1.3a7 7 0 0 0 4.5 4.5L10.5 10l3 2-1 2.2C7 14 2 9 1.8 3.5z" fill="currentColor" /></Icon>,
  end: <Icon><path d="M1.5 9.5C5 6 11 6 14.5 9.5l-1.8 2-2.4-1.2V8.6a8 8 0 0 0-4.6 0v1.7l-2.4 1.2z" fill="currentColor" /></Icon>,
  zoomIn: <Icon><path d="M3 8h10M8 3v10" {...line} strokeWidth={1.8} /></Icon>,
  zoomOut: <Icon><path d="M3 8h10" {...line} strokeWidth={1.8} /></Icon>,
}

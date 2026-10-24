import icon from '@icons/chairplay-256.png'
import type { ReactNode } from 'react'
import type { AppState, Backend, BuildCommit, WindowsPlace } from '../api'
import { Group, Row } from '../shell/controls'
import { CopyButton } from './messages'

function Commit({ commit }: { commit: BuildCommit | null }) {
  if (!commit) return <span className="hint">Not recorded</span>
  return (
    <>
      <span className="mono">{commit.commit}</span> · {commit.date}
    </>
  )
}

/** Which app and build this is, whose work it is built on, and where to get help. */
export function AboutPage({
  state,
  backend,
  onShowGuide,
}: {
  state: AppState
  backend: Backend
  onShowGuide: () => void
}) {
  // The links open in the browser, or the notices in Notepad; they are buttons that read as links.
  const link = (place: WindowsPlace, text: string, title?: string): ReactNode => (
    <button type="button" className="link" title={title} onClick={() => backend.open(place)}>
      {text}
    </button>
  )
  return (
    <div className="about">
      {/* The icon in the labels' column, against it as a label is; the name and line in the values' */}
      <div className="about-head">
        <img src={icon} alt="" />
        <span className="about-name">ChairPlay</span>
        <span className="hint">Wireless CarPlay receiver for Windows</span>
      </div>
      <Group title="Version" />
      <Row label="Build">
        <Commit commit={state.about.app} />
      </Row>
      <Row label="DiPlay core">
        <Commit commit={state.about.core} />
      </Row>
      <Group title="Credits" />
      <Row label="Author">{link('author', 'Oleksiy Strelets', "Open the author's GitHub profile")}</Row>
      <Row label="CarPlay core">
        {link('diplay', 'DiPlay')} by shihabal3amri, on {link('xcertplay', 'xcertplay')} by shilapi
      </Row>
      <Row label="HUD maneuvers">LuKa, {link('rgi', 'mib2q-carplay-rgi')}</Row>
      <Row label="License">GPL-3.0 · {link('notices', 'Third-party notices')}</Row>
      <Group title="Support" />
      <Row label="Get started">
        <button type="button" className="btn small" title="Go through the setup steps again" onClick={onShowGuide}>
          Show again
        </button>
      </Row>
      <Row label="Report a problem">
        {link('issues', 'New issue on GitHub', 'Open a new issue on GitHub, with this build filled in')}
        <span className="copy-details">
          <CopyButton
            text={state.about.details}
            label="Copy details"
            title="Copy the build, the DiPlay core, and the Windows version"
          />
        </span>
      </Row>
      <Row label="Community">
        {link('discussions', 'Discussions', 'Ask a question or share an idea')} ·{' '}
        {link('pull-requests', 'Pull requests', 'How to send a change')}
      </Row>
      <Row label="Source">
        {link('source', 'github.com/NumberOneBot/ChairPlay')} ·{' '}
        {link('releases', 'Releases', 'Every version, with what changed')}
      </Row>
      <Row className="hint-row">
        <span className="hint">
          CarPlay, iPhone, AirPlay, and Siri are trademarks of Apple Inc. ChairPlay is not affiliated with Apple.
        </span>
      </Row>
    </div>
  )
}

import icon from '@icons/chairplay-256.png'
import { Group, Row } from '../shell/controls'

/** Which app this is and whose work it is built on. */
export function AboutPage() {
  return (
    <div className="about">
      {/* The icon in the labels' column, against it as a label is; the name and line in the values' */}
      <div className="about-head">
        <img src={icon} alt="" />
        <span className="about-name">ChairPlay</span>
        <span className="hint">Wireless CarPlay receiver for Windows</span>
      </div>
      <Group title="Credits" />
      <Row label="CarPlay core">DiPlay by shihabal3amri</Row>
      <Row label="HUD maneuvers">LuKa, mib2q-carplay-rgi</Row>
      <Row label="License">GPL-3.0</Row>
      <div className="hint trademarks">
        CarPlay, iPhone, AirPlay, and Siri are trademarks of Apple Inc. ChairPlay is not affiliated with Apple.
      </div>
    </div>
  )
}

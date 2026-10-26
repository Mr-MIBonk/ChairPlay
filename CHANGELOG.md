# Changelog

User-visible changes and release tooling updates for ChairPlay.

## 1.0.1 — Unreleased

### Added

- Experimental **Send satellites in view** on the GPS page. GSV reports now use the virtual car's position and UTC
  time to predict visible GPS satellites from a CelesTrak Yuma almanac, replacing the fixed satellite pattern.
- Background almanac download and refresh, with a local cache for offline use. Missing or expired data suppresses
  satellite reports without interrupting position or wheel-speed updates. Car coordinates are not sent to CelesTrak.

### Changed

- Refreshed the settings and controls interface with iOS-inspired switches, rounded fields and buttons, updated
  sliders and selection highlights, and Reddit Sans. Moved **Use Windows location** above the GPS coordinates.
- Get started omits the MFi step when both files are already present and adjusts the total and numbering. The step
  returns if either file disappears, and Connect remains unavailable until the required checks pass.
- HUD distances, calls, and the waiting message retain Roboto Flex Wide SemiBold, now with **Inter Bold** as the
  fallback for missing characters. Other HUD text keeps Inter; gauge fonts are unchanged.
- Lengthened the virtual car's sixth gear for a top speed of about 268 km/h with the default car, raised its limiter
  to 280 km/h, and extended the speedometer to 300 km/h.
- Reworked the README around installation, features, and controls; added GPS satellite documentation.

### Notes

- Satellite reports are experimental and off by default. Enable them and reconnect so the iPhone can request GSV.
  Their geometry is calculated and their signal levels are simulated; increased spoofing range is not established.
- CarPlay transport remains wireless. Experimental USB GPS development tools are not part of this release.

[Release notes](docs/RELEASE-NOTES-1.0.1.md) · [Changes since v1.0.0](https://github.com/NumberOneBot/ChairPlay/compare/v1.0.0...main)

## 1.0.0 — 2026-10-26

First public release:

- Wireless Apple CarPlay on Windows 10 and 11, with mouse and keyboard controls.
- Music, calls, and Siri through selectable PC audio devices.
- Get started checks for MFi files, firewall access, Bluetooth pairing, and Wi-Fi configuration.
- Experimental AltScreen with a map, turn card, and HUD with animated 3D maneuvers.
- Experimental GPS spoofing with a virtual car controlled by keyboard or gamepad.
- Per-user Windows installer that preserves settings, pairing, and MFi files on update.

[Published release](https://github.com/NumberOneBot/ChairPlay/releases/tag/v1.0.0)

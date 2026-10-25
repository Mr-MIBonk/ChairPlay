# ChairPlay

**Wireless Apple CarPlay on a Windows PC.** Your iPhone connects to the PC the way it connects to a car: over
Bluetooth first, then over Wi-Fi. CarPlay opens in a window, and you drive it with the mouse and the keyboard,
hear it through the PC's speakers and talk to Siri through its microphone.

![ChairPlay on Windows: the AltScreen instrument cluster over a map, the CarPlay home screen in the ChairPlay window, and the on-screen car controls](docs/images/chairplay.png)

Useful for building and testing CarPlay apps and navigation without a car, for demos, or just CarPlay on a desk.

ChairPlay is built on [DiPlay](https://github.com/shihabal3amri/DiPlay) by shihabal3amri, the Android CarPlay
receiver for head units, itself based on [xcertplay](https://github.com/shilapi/xcertplay) by shilapi. It compiles
DiPlay's portable CarPlay core (AirPlay, iAP2, MFi authentication) from the `upstream/` submodule and wraps it in
everything Windows needs. It is a separate project, not affiliated with or endorsed by their authors, or by Apple.

## Before you start

You will need:

- **An iPhone** with CarPlay (any reasonably recent one).
- **A Windows 10 or 11 PC** with its own **Bluetooth** and **Wi-Fi**. The PC has to join Wi-Fi itself — a PC wired to
  the router by cable is not enough.
- **The same Wi-Fi network** for the PC and the iPhone, one that lets the devices on it talk to each other. Most home
  networks do; many guest networks deliberately do not.
- **An Apple MFi identity** — a small certificate-and-key pair that tells the iPhone it may connect to an accessory.
  This is the one piece ChairPlay cannot provide for you: Apple does not hand these out freely, so you supply your
  own. It is the only step that is not point-and-click — [the setup guide](docs/SETUP.md) shows where the files go.

## Your first connection

1. **Install.** Download the latest `ChairPlay-…-setup.exe` from the
   [Releases page](https://github.com/NumberOneBot/ChairPlay/releases) and run it. It installs just for you, needs no
   administrator rights, and adds ChairPlay to the Start menu.
2. **Pair your iPhone** with the PC once, in Windows Settings → Bluetooth & devices — the same as pairing headphones.
3. **Add your MFi files** to the `offline-mfi` folder next to the app (the setup guide points to it).
4. **Open ChairPlay.** The first screen, *Get started*, checks everything for you — the MFi files, the firewall (it
   adds the rule with your permission), your paired iPhone, and your Wi-Fi network — and ticks each item off as it
   becomes ready.
5. **Unlock your iPhone and tap *Allow*** when it asks. ChairPlay brings up CarPlay on its own.

After that, every run is the same, only quicker: open ChairPlay with your iPhone unlocked and nearby, and CarPlay
comes up by itself.

## What works

- **Wireless CarPlay** with an iPhone on the same Wi-Fi network as the PC, started over Windows Bluetooth.
- **Get started** before the first connection: four steps the app checks itself — the MFi files, the firewall
  (opened with your consent), the paired iPhone, and the Wi-Fi network (filled in from the one the PC is on) — each
  ticking off once it is done.
- **Settings** whenever CarPlay is not covering the window: a toolbar (the CarPlay, AltScreen and controls windows,
  the connection status, Connect, Reconnect, Disconnect) and pages for the connection, the CarPlay screen, the
  AltScreen, the keyboard, audio, and about. Each change saves in place and says when it applies; anything wrong shows
  above the settings, with what to do.
- **Video** in a resizable window, 1280×720 at 60 fps by default, H.264 decoded in software.
- **Touch** with the mouse: taps, drags, scrolling lists and the map.
- **Car controls on the keyboard**: arrows tilt CarPlay's knob, Tab turns it, Enter selects, Esc goes back, plus
  media keys, call keys and Siri. Every binding can be changed on the Keyboard page, and applies at once.
- **Audio**: music (AAC-LC 48 kHz stereo), navigation prompts and Siri, on the device you choose.
- **Microphone** for Siri and calls (Opus), on the device you choose.
- **Your own "car"**: the name the iPhone shows, the icon and label of the car button on the CarPlay home screen
  (tapping it opens the settings).
- **The CarPlay screen as you want it**: resolution (it sets @2x vs @3x and how much fits), frame rate, light or
  dark theme following Windows, driving side, dock position, safe area, corner style.
- **AltScreen** (experimental): the car's instrument cluster as a second CarPlay screen in its own window,
  1440x540 by default, with the map, the turn card or both, switched on the fly.
- **HUD** in the AltScreen window (`altscreen.content=hud`): the next maneuver, its distance, the arrival and the
  song on black, drawn by the app rather than the iPhone. The animated 3D maneuvers (turns, roundabouts and their
  roads, the arrow filling toward the turn, the destination flag) are LuKa's maneuver renderer from the
  [MHI2Q CarPlay project](https://github.com/luka-dev/mib2q-carplay-rgi), ported to run inside the window.
- **On-screen car controls** (`window.controls=true`): a window of buttons for the knob, back, home, Siri, media,
  volume, calls and the AltScreen's zoom and modes, held as long as the mouse holds them; Alt shows their keys.
- **Volume of its own**: Ctrl+Up, Ctrl+Down and Ctrl+M turn ChairPlay up, down or mute it, leaving Windows' volume
  alone.
- **Graceful reconnection**: close the window and start again, and the iPhone reconnects by itself.

Tested on Windows 11 with an iPhone 15 (iOS 18.6).

## Requirements

- Windows 10 or 11, 64-bit.
- **Bluetooth**: a built-in or USB adapter with Bluetooth Classic (BR/EDR), on Windows' own Microsoft Bluetooth
  stack: the app opens the iPhone's iAP2 channel over RFCOMM through Winsock. Bluetooth LE-only adapters and
  third-party stacks (old Broadcom WIDCOMM, Toshiba) do not work. The iPhone must be paired in Windows Settings.
- **Wi-Fi**: the PC must be on Wi-Fi through its own adapter, on the same network as the iPhone; a PC wired to the
  router by Ethernet is not enough yet. The network must be WPA2-Personal or open (a WPA2/WPA3 mixed router works as
  WPA2; WPA3-only does not) and must let its clients reach each other and pass Bonjour multicast — no client or AP
  isolation, which guest networks usually have. Tested on 5 GHz.
- **Windows Firewall** must let the iPhone in (Get started adds the rule with your consent, or gives the commands).
- **Microsoft Edge WebView2** for the settings and Get started: part of Windows 11 and of an updated Windows 10.
  Without it they open in the default browser instead.
- **An Apple MFi accessory identity** (certificate and private key). The iPhone authenticates every CarPlay
  accessory; this project does not and cannot ship one. You supply your own in `offline-mfi\`.
- JDK 17 to build. The build downloads the rest itself (zig, Node.js for the screens, the WebView2 SDK), each
  pinned and checked.

## Install

Download `ChairPlay-X.Y.Z-setup.exe` from the [Releases page](https://github.com/NumberOneBot/ChairPlay/releases) and
run it. It installs for your account only, without administrator rights, into `%LOCALAPPDATA%\Programs\ChairPlay`, and
adds ChairPlay to the Start menu. A newer installer updates that folder in place and keeps your settings, keys,
pairings and logs; uninstalling leaves them too. Then follow steps 1–4 below, with that folder for `dist\ChairPlay`.

## Quick start

To build it yourself:

```
git clone --recursive https://github.com/NumberOneBot/ChairPlay.git
cd ChairPlay
gradlew deploy
```

`deploy` builds a self-contained app (with its own Java runtime) into `dist\ChairPlay` and keeps your
settings and keys there on later builds. To install it elsewhere, pass `-Pchairplay.home=<folder>`, or put
`chairplay.home=<folder>` in `%USERPROFILE%\.gradle\gradle.properties` once. Then:

1. Pair the iPhone in Windows Settings > Bluetooth & devices.
2. Put your MFi identity files in `dist\ChairPlay\offline-mfi\`.
3. Start `ChairPlay.exe`. Get started checks each step on its own: the MFi files, the firewall rule (Add the rule
   adds it with your consent), the paired iPhone, and the Wi-Fi network and its password (or fill them into
   `dist\ChairPlay\settings.properties`, which `deploy` wrote with every setting at its default).
4. Once every step is done, press Connect with the iPhone unlocked, and accept CarPlay on it; CarPlay then covers
   the window.

[docs/SETUP.md](docs/SETUP.md) has every step in detail and the reference for all settings.

## Controls

| Key | CarPlay |
| --- | --- |
| Mouse | touch |
| Arrows | tilt the knob: focus moves in that direction |
| Tab / Shift+Tab | turn the knob one step forward / back |
| Mouse wheel | turn the knob, one step per notch |
| Enter, Space | select |
| Backspace, Esc | back |
| Home | home screen |
| F3 / F4 | answer / end a call |
| F7 / F8 / F9 | previous track / play-pause / next track |
| S | Siri |
| Ctrl+Up / Ctrl+Down / Ctrl+M | turn ChairPlay's own volume up / down / mute, leaving Windows' volume alone |
| Ctrl+R | end the session gracefully and connect again |
| F10 | drive the GPS car with the arrows (steering and pedals instead of the knob); needs GPS spoofing on |
| Shift+F10 | switch the GPS car's gauges between large and small; needs GPS spoofing on |
| F11 | AltScreen: map, turn card, map with turn card |
| Shift+F11 | AltScreen: move the ETA and turn card (auto, left, right, top, off) |
| Mouse wheel over the AltScreen, + / − (either keyboard) | zoom the AltScreen map |
| F12 | screenshots of the CarPlay screen and the AltScreen, at the iPhone's resolution, into `screenshots` |
| The car button on the CarPlay home screen | the settings; the CarPlay button or Esc returns to CarPlay |

While the settings cover the window, the keys and the mouse are theirs, not CarPlay's.

Rebind any of them on the Keyboard page, which saves them into `controls.properties` and applies them at once; Esc
always goes back and Enter always selects.

## Configuration

Everything lives next to the exe, in plain text:

- `settings.properties`: Wi-Fi, the iPhone, connecting at start and showing CarPlay at once, the car's name and
  icon, sound devices and call echo cancellation, and the CarPlay screen (size, frame rate, physical size, theme,
  driving side, dock, safe area, corners) and window size. The settings edit these in place, keeping the file's
  comments and other lines.
- `controls.properties`: key bindings and mouse-wheel behaviour, which the Keyboard page changes the same way.
- `window.properties`: where you left the windows and their sizes, and whether Get started is done, written by the
  app.

A wrong value stops the start with a message that names the setting. The app's log, `logs\chairplay-<date-time>.log`
in the app folder, has the details, and on a normal start what the iPhone was told.

## Good to know

- Quit by closing the window. If the app is killed mid-session (Task Manager), the iPhone may ignore new sessions
  until restarted.
- Wireless only, over a Wi-Fi network shared by the PC and the iPhone (no PC hotspot, no USB).
- Echo cancellation for calls is experimental and off by default (`audio.echo.cancellation`); headphones are the
  safe choice.
- Over Wi-Fi the iPhone sends music only as AAC; lossless formats are refused.
- The iPhone's own volume buttons do not change CarPlay's volume; Windows' volume does, as a car's knob would.

## Building on DiPlay

DiPlay is a git submodule pinned to the version this build is tested with. To move to a newer DiPlay:
`git -C upstream fetch`, `git -C upstream checkout <commit>`, run `gradlew test`, and commit the new pointer.
[docs/SETUP.md](docs/SETUP.md) describes the mirror script that keeps the build working without DiPlay's
repository, and the git hooks that keep keys out of commits.

## Licence and credits

GPL-3.0, as DiPlay and xcertplay; see [LICENSE](LICENSE). The CarPlay protocol work is DiPlay's, and that of
[xcertplay](https://github.com/shilapi/xcertplay) by shilapi, which DiPlay is based on. The HUD's maneuver renderer and
its destination flag are LuKa's, from [mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi) (GPL-3.0
or later); [native/maneuver/README.md](native/maneuver/README.md) lists what was taken and what was changed. The
bundled libraries' notices are in `THIRD_PARTY_NOTICES` and travel with the app.

CarPlay, iPhone, AirPlay and Siri are trademarks of Apple Inc. This project is not affiliated with Apple and does
not include any Apple MFi identity.

## Contributing

Ideas, bug reports and fixes are welcome: open an issue or a pull request, or write to me at
[alex.strelets@gmail.com](mailto:alex.strelets@gmail.com). To report a connection problem, set Log level to Info on
the Experiments page, reproduce it, and send the log (`logs\chairplay-<date-time>.log` in the app folder; a release
logs only warnings). The app keeps the Wi-Fi password out of it, but look it over before sending.

## Support the project

ChairPlay is a hobby project, built and tested on real hardware in my spare time. If it saves you a trip to
the car or helps your CarPlay work, you can buy me a coffee:

<a href="https://www.buymeacoffee.com/alexstrelets"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="217"></a>

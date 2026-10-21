# DiPlay Desktop

**Wireless Apple CarPlay on a Windows PC.** Your iPhone connects to the PC the way it connects to a car: over
Bluetooth first, then over Wi-Fi. CarPlay opens in a window, and you drive it with the mouse and the keyboard,
hear it through the PC's speakers and talk to Siri through its microphone.

![CarPlay home screen in the DiPlayDesktop window, with the Windows car button](docs/images/carplay-window.png)

Useful for developing and testing CarPlay apps and navigation without sitting in a car, for demos, or simply for
CarPlay on a desk.

DiPlay Desktop is built on [DiPlay](https://github.com/shihabal3amri/DiPlay), the Android CarPlay receiver for head
units by shihabal3amri. It compiles DiPlay's portable CarPlay core (AirPlay, iAP2, MFi authentication) from the
`upstream/` submodule and adds everything Windows needs around it. It is a separate project, not affiliated with or
endorsed by DiPlay's authors or by Apple.

## What works

- **Wireless CarPlay** with an iPhone on the same Wi-Fi network as the PC, started over Windows Bluetooth.
- **Video** in a resizable window, 1280×720 at 60 fps by default, H.264 decoded in software.
- **Touch** with the mouse: taps, drags, scrolling lists and the map.
- **Car controls on the keyboard**: arrows tilt CarPlay's knob, Tab turns it, Enter selects, Esc goes back, plus
  media keys, call keys and Siri. Every binding can be changed.
- **Audio**: music (AAC-LC 48 kHz stereo), navigation prompts and Siri, on the device you choose.
- **Microphone** for Siri and calls (Opus), on the device you choose.
- **Your own "car"**: the name the iPhone shows, the icon and label of the car button on the CarPlay home screen
  (a Windows window by default; tapping it minimizes CarPlay).
- **The CarPlay screen as you want it**: resolution, frame rate, physical size or pixel density (which decides how
  large iOS draws its interface), light or dark theme following Windows, driving side, dock position, safe area,
  corner style.
- **Graceful reconnection**: close the window and start again, and the iPhone reconnects by itself.

Tested on Windows 11 with an iPhone 15 (iOS 18.6).

## Requirements

- Windows 10 or 11, 64-bit.
- **Bluetooth**: a built-in or USB adapter with Bluetooth Classic (BR/EDR), on Windows' own Microsoft Bluetooth
  stack: the app opens the iPhone's iAP2 channel over RFCOMM through Winsock. Bluetooth LE-only adapters and
  third-party stacks (old Broadcom WIDCOMM, Toshiba) do not work. The iPhone must be paired in Windows Settings.
- **Wi-Fi**: the PC itself must be on Wi-Fi, through its own Wi-Fi adapter, on the same network as the iPhone; a PC
  wired to the same router by Ethernet is not enough yet. The network must be WPA2-Personal or open (a WPA2/WPA3
  mixed router works as WPA2; WPA3-only does not), and must let its clients reach each other and pass multicast
  for Bonjour: no client or AP isolation, which guest networks usually have. Tested on 5 GHz.
- **Windows Firewall** must let the iPhone in (the app prints the commands if a rule is missing).
- **An Apple MFi accessory identity** (certificate and private key). The iPhone authenticates every CarPlay
  accessory; this project does not and cannot ship one. You supply your own in `offline-mfi\`.
- JDK 17 to build.

## Quick start

```
git clone --recursive https://github.com/NumberOneBot/DiPlayDesktop.git
cd DiPlayDesktop
gradlew deploy
```

`deploy` builds a self-contained app (with its own Java runtime) into `dist\DiPlayDesktop` and keeps your
settings and keys there on later builds. To install it elsewhere, pass `-Pdiplay.home=<folder>`, or put
`diplay.home=<folder>` in `%USERPROFILE%\.gradle\gradle.properties` once. Then:

1. Pair the iPhone in Windows Settings > Bluetooth & devices.
2. Put your MFi identity files in `dist\DiPlayDesktop\offline-mfi\`.
3. Fill in your Wi-Fi name and password in `dist\DiPlayDesktop\settings.properties`, which `deploy` wrote with
   every setting at its default.
4. Allow DiPlayDesktop through Windows Firewall (the app prints the exact commands if a rule is missing).
5. Start `DiPlayDesktop.exe` with the iPhone unlocked, and accept CarPlay on the iPhone.

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
| F7 / F8 / F9 | previous track / play-pause / next track |
| F3 / F4 | answer / end a call |
| S | Siri |

Rebind any of them in `controls.properties`, which the app writes on its first start with every action listed.

## Configuration

Everything lives next to the exe, in plain text:

- `settings.properties`: Wi-Fi, the iPhone, the car's name and icon, sound devices, and the CarPlay screen
  (size, frame rate, physical size or ppi, theme, driving side, dock, safe area, corners) and window size.
- `controls.properties`: key bindings and mouse-wheel behaviour.
- `carplay-icon.png`: the car button's icon; replace it with any square image.

A wrong value stops the start with the setting named, and the console prints what the iPhone was told.

## Good to know

- Quit by closing the window. If the app is killed mid-session (Task Manager), the iPhone may ignore new sessions
  until it is restarted.
- Wireless only, and only over a Wi-Fi network shared by the PC and the iPhone (no PC hotspot, no USB).
- No echo cancellation yet: use headphones for calls.
- Over Wi-Fi the iPhone sends music only as AAC; lossless formats are refused.
- The iPhone's own volume buttons do not change CarPlay's volume; Windows' volume does, as a car's knob would.

## Roadmap

- A macOS (Apple Silicon) version, built from source.
- Replaying recorded GPS tracks into the iPhone, so its navigation apps follow a route from the desk.
- Echo cancellation for calls without headphones.

## Building on DiPlay

DiPlay is a git submodule pinned to the version this build is tested with. To move to a newer DiPlay:
`git -C upstream fetch`, `git -C upstream checkout <commit>`, run `gradlew test`, and commit the new pointer.

## Licence and credits

GPL-3.0, as DiPlay; see [LICENSE](LICENSE). The CarPlay protocol work is DiPlay's; the bundled libraries' notices
are in `THIRD_PARTY_NOTICES` and travel with the app.

CarPlay, iPhone, AirPlay and Siri are trademarks of Apple Inc. This project is not affiliated with Apple and does
not include any Apple MFi identity.

## Contributing

Ideas, bug reports and fixes are welcome. Open an issue or a pull request, or write to me at
[alex.strelets@gmail.com](mailto:alex.strelets@gmail.com) with what you would like to see. A log from the console
helps with anything that does not connect; the app leaves the Wi-Fi password out of it, but look it over before
sending.

## Support the project

DiPlay Desktop is a hobby project, built and tested on real hardware in my spare time. If it saves you a trip to
the car or helps your CarPlay work, you can buy me a coffee:

<a href="https://www.buymeacoffee.com/alexstrelets"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="217"></a>

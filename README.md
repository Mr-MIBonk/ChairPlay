# DiPlay Desktop

A Windows port of DiPlay: it receives wireless CarPlay from an iPhone and shows the screen in a window on the PC,
with touch, audio and microphone. The iPhone is reached over Bluetooth (iAP2) and then over Wi-Fi (AirPlay), so the
PC and the iPhone must be on the same Wi-Fi network.

## Build

Needs JDK 17 (the only version this build is tested with). From the repository root:

```
gradlew -p desktop jpackageImage
```

The result is `desktop\build\jpackage\DiPlayDesktop\DiPlayDesktop.exe`, a self-contained folder with its own runtime;
no installer or WiX is needed. The folder also carries the licence notices (`LICENSE`, `LICENSE-concentus`, `THIRD_PARTY_NOTICES`,
`licenses\`). Only the `windows-x86_64` native libraries are bundled.

The app keeps its settings, keys and pairings in the folder of its exe. To run it from a folder of its own that
rebuilds never wipe, deploy it there:

```
gradlew -p desktop deploy
```

This builds the image and syncs it into `Documents\DiPlayDesktopApp` (another folder with
`-Pdiplay.home=<path>`), replacing the program and keeping `settings.properties`, `identity.properties`,
`pairings\`, `offline-mfi\`, `controls.properties` and `carplay-icon.png`. On its first start in a folder without them, the app copies them from
`%APPDATA%\DiPlayDesktop`, where earlier builds kept them, so the iPhone still knows the PC; the old folder stays.

## Set up

The files below live in the app folder, next to `DiPlayDesktop.exe`.

1. **Pair the iPhone** in Windows Settings > Bluetooth & devices. `DiPlayDesktop.exe devices` lists the radio and the
   paired devices (it connects to nothing).
2. **MFi identity files.** The wireless handshake needs an Apple MFi identity (certificate and key files). This
   project does not ship them and the app image never contains them. Put your own files in `offline-mfi\`.
3. **Settings.** The first start creates `settings.properties`. Fill in:
   - `wifi.ssid` (required) and `wifi.passphrase`: the Wi-Fi network shared by the PC and the iPhone;
   - `wifi.security`: `NONE`, `WEP`, `WPA_WPA2` (default), `WPA3_TRANSITION` or `WPA3_ONLY`;
   - `iphone.address`: the iPhone's Bluetooth address, only needed when several phones are paired;
   - `device.name`: the name the iPhone shows for the PC, in its CarPlay settings and on the network (default
     DiPlayDesktop, at most 63 bytes); the window title stays DiPlayDesktop;
   - `car.label` and `car.icon`: the label and the image of the car button on the CarPlay home screen (default
     `Windows` and `carplay-icon.png`, a Windows window written into the app folder on the first start). Replace the
     image with any square PNG, JPEG, BMP or GIF; it is sent at 192 pixels at most;
   - `display.width` and `display.height`: the CarPlay screen size in pixels, positive even numbers
     (default 1280 x 720), and `display.fps`, 30 to 60 (default 60);
   - the screen's physical size, from which iOS chooses how large to draw its interface: either
     `display.physical.width.mm` and `display.physical.height.mm`, or a pixel density `display.ppi` that the app
     turns into millimetres. Millimetres win over `display.ppi`; a width alone takes the height from the aspect ratio.
     Unset, the screen is 200 mm wide, as on DiPlay's head units;
   - `window.width` and `window.height`: the size of the window on the PC (default: the display size). The picture
     is scaled into the window, so this changes only how big it looks, not what the iPhone draws.

   - `display.theme`: `windows` (default: follows the Windows dark mode, also while running), `light`, `dark`, or
     `phone` (the iPhone decides by itself);
   - `display.right.hand.drive` (`true` puts the dock and controls on the right), `display.dock` (`auto`, `driver` or
     `bottom`), `display.safe.top`/`bottom`/`left`/`right` (pixels CarPlay keeps its controls out of) with
     `display.safe.draw.outside`, and `display.corners` (`rounded` or `square`).

   The settings apply at the next start; the console prints what the iPhone was told, for example
   `display 1280x720 px, 200x113 mm (163 ppi), 60 fps; window 1280x720`.
4. **Firewall.** Windows Firewall drops the iPhone's inbound connections. From an administrator Command Prompt, add
   these rules once (the paths are those of the packaged exe; adjust them if you move the folder). The app does not
   change the firewall itself and prints the same commands at start when no rule named `DiPlayDesktop CarPlay inbound`
   exists:

   ```
   netsh advfirewall firewall add rule name="DiPlayDesktop CarPlay inbound" dir=in action=allow program="<folder>\DiPlayDesktop.exe" protocol=TCP localport=7000-7010,49152-65535
   netsh advfirewall firewall add rule name="DiPlayDesktop CarPlay inbound" dir=in action=allow program="<folder>\DiPlayDesktop.exe" protocol=UDP localport=49152-65535
   netsh advfirewall firewall add rule name="DiPlayDesktop CarPlay inbound" dir=in action=allow program="<folder>\DiPlayDesktop.exe" protocol=UDP localport=5353
   ```

   Replace `<folder>` with the full path of `DiPlayDesktop`, e.g. `C:\Apps\DiPlayDesktop`.

   If Windows asks at the first start whether DiPlayDesktop may use the network, answering Cancel creates a rule that
   blocks it, and a block rule wins over the ones above. At start the app names every enabled inbound block rule for
   its exe; delete those, or set them to Allow, in Windows Defender Firewall > Advanced settings > Inbound Rules. The
   check uses PowerShell's firewall cmdlets and finds rules whose program path is written out in full, not through an
   environment variable such as `%ProgramFiles%`.

## Run

```
DiPlayDesktop.exe
```

With no arguments the launcher runs `run`. Passing any argument replaces that default, so `DiPlayDesktop.exe devices`
and `DiPlayDesktop.exe probe-bt [iphone-address]` run those commands instead. The app is a console program: it logs
to the console window, and the CarPlay screen opens in its own window, titled DiPlayDesktop.

The iPhone lists the PC as **DiPlayDesktop** (or `device.name`). The CarPlay home screen shows a Windows icon (`car.icon`) where a car shows its maker's;
tapping it minimizes the CarPlay window and leaves CarPlay running.

Besides touch with the mouse, the keyboard and the mouse wheel work as a car's controls. These are the defaults;
`controls.properties` in the app folder, written on the first start with every action documented, rebinds them
(`key.siri = Ctrl+S, F10`; empty turns an action off) and sets the wheel (`wheel.action = knob` or `none`,
`wheel.invert`, `wheel.notches.per.step`) and `key.repeat.every`. A mistake in it stops the start and names the
setting; delete the file to get the defaults back.

| Key | CarPlay |
| --- | --- |
| Arrows | tilt the knob: focus moves in that direction |
| Tab / Shift+Tab | turn the knob one step forward / back (held: a step every third repeat) |
| Mouse wheel | turn the knob, one step per notch (down is forward) |
| Enter, Space | knob select |
| Backspace, Esc | back |
| Home | home |
| F7 / F8 / F9 | previous track / play-pause / next track |
| F3 / F4 | answer / end a call |
| S | Siri |

Quit by closing the window (or Ctrl+C in the console). Do not end the process from Task Manager: see below.

## Troubleshooting

- **The iPhone accepts the Bluetooth handshake but never connects over Wi-Fi.** The log ends at
  `iap2 tx=0x4301 carplay-start-session` and, five minutes later, `Bluetooth control ended TIMED_OUT before the iPhone
  joined over Wi-Fi`. This happened after the app had been killed in the middle of a session, or after `probe-bt`:
  the iPhone then ignored every new start request. Restarting the iPhone cleared it. Closing the window normally
  does not cause it.

## Known limits

- Wireless only, and only when the PC and the iPhone share a Wi-Fi network (no PC hotspot, no wired mode).
- No echo cancellation: use headphones for calls.
- H.264 video only (no HEVC), decoded in software.

# Building and setting up DiPlay Desktop

The README gives the short version; this page has every step and every setting.

## Get the source

DiPlay is a git submodule, so clone with it:

```
git clone --recursive <this repository>
```

In a clone made without `--recursive`, run `git submodule update --init`. The submodule is pinned to the DiPlay
version this build is tested with. To move to a newer DiPlay: `git -C upstream fetch`, `git -C upstream checkout
<commit or tag>`, build and run the tests, then commit the new `upstream` pointer.

## Build

Needs JDK 17 (the only version this build is tested with). From the repository root:

```
gradlew jpackageImage
```

The result is `build\jpackage\DiPlayDesktop\DiPlayDesktop.exe`, a self-contained folder with its own runtime;
no installer or WiX is needed. The folder also carries the licence notices (`LICENSE`, `LICENSE-concentus`, `THIRD_PARTY_NOTICES`,
`licenses\`). Only the `windows-x86_64` native libraries are bundled.

The app keeps its settings, keys and pairings in the folder of its exe. To run it from a folder of its own that
rebuilds never wipe, deploy it there:

```
gradlew deploy
```

This builds the image and syncs it into `dist\DiPlayDesktop` in the project, replacing the program and keeping
`settings.properties`, `controls.properties`, `carplay-icon.png`, `identity.properties`, `pairings\` and
`offline-mfi\`. It is outside `build\`, which `gradlew clean` wipes, and git ignores it.

To install somewhere else, give the folder as the `diplay.home` property: once with `gradlew deploy
-Pdiplay.home=C:\Apps\DiPlayDesktop`, or for good in `%USERPROFILE%\.gradle\gradle.properties`:

```
diplay.home=C:/Apps/DiPlayDesktop
```

`gradlew launchApp` starts the app from the same folder in its own console window, and `gradlew listDevices`
lists the Bluetooth and sound devices with it.

## In VS Code

The repository recommends the Gradle for Java extension, whose side panel lists every Gradle task (`test`,
`jpackageImage`, `deploy`, ...). Terminal > Run Task adds the rest: Deploy and run, Run DiPlayDesktop (in its
own console window), List devices and Stop Gradle daemon. Stop the app by closing its window or with Ctrl+C in its
console, never with Terminate Task, which kills it hard.

## Set up

The files below live in the app folder, next to `DiPlayDesktop.exe`.

1. **Pair the iPhone** in Windows Settings > Bluetooth & devices. `DiPlayDesktop.exe devices` lists the radio and the
   paired devices (it connects to nothing).
2. **MFi identity files.** The wireless handshake needs an Apple MFi identity (certificate and key files). This
   project does not ship them and the app image never contains them. Put your own files in `offline-mfi\`.
3. **Settings.** `deploy` (or the first start, for a folder filled by hand) writes `settings.properties` with
   every setting at its default and a comment on each, together with `controls.properties` and `carplay-icon.png`;
   files already there are kept, and a settings file from an older version gets the settings it lacks added at
   its end. Fill in:
   - `wifi.ssid` (required) and `wifi.passphrase`: the Wi-Fi network shared by the PC and the iPhone;
   - `wifi.security`: `NONE`, `WEP`, `WPA_WPA2` (default), `WPA3_TRANSITION` or `WPA3_ONLY`;
   - `iphone.address`: the iPhone's Bluetooth address, only needed when several phones are paired;
   - `device.name`: the name the iPhone shows for the PC, in its CarPlay settings and on the network (default
     DiPlayDesktop, at most 63 bytes); the window title stays DiPlayDesktop;
   - `car.label` and `car.icon`: the label and the image of the car button on the CarPlay home screen (default
     `Windows` and `carplay-icon.png`, a Windows window written into the app folder on the first start). Replace the
     image with any square PNG, JPEG, BMP or GIF; it is sent at 192 pixels at most;
   - `audio.output` and `audio.input`: the sound device CarPlay plays on and the microphone, `default` (the Windows
     default, as before) or a part of a device name; `DiPlayDesktop.exe devices` lists the names;
   - `display.width` and `display.height`: the CarPlay screen size in pixels, positive even numbers
     (default 1280 x 720), and `display.fps`, 30 to 60 (default 60);
   - the screen's physical size, from which iOS chooses how large to draw its interface: either
     `display.physical.width.mm` and `display.physical.height.mm`, or a pixel density `display.ppi` that the app
     turns into millimetres. Millimetres win over `display.ppi`; a width alone takes the height from the aspect ratio.
     Unset, the screen is 200 mm wide, as on DiPlay's head units;
   - `window.width` and `window.height`: the size of the window on the PC (default: the display size). The picture
     is scaled into the window, so this changes only how big it looks, not what the iPhone draws;
   - `display.theme`: `windows` (default: follows the Windows dark mode, also while running), `light`, `dark`, or
     `phone` (the iPhone decides by itself);
   - `display.right.hand.drive` (`true` puts the dock and controls on the right), `display.dock` (`auto`, `driver` or
     `bottom`), `display.safe.top`/`bottom`/`left`/`right` (pixels CarPlay keeps its controls out of) with
     `display.safe.draw.outside`, and `display.corners` (`square`, the default, fills the window to its corners; `rounded` keeps CarPlay's rounded corners).

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

## Running

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
(`key.siri=Ctrl+S, F10`; empty turns an action off) and sets the wheel (`wheel.action=knob` or `none`,
`wheel.invert`, `wheel.notches.per.step`) and `key.repeat.every`. A mistake in it stops the start and names the
setting; delete the file to get the defaults back.

## Keyboard bindings

See the table in the README for the defaults. `controls.properties` lists every action with its keys and a
description; each line is `action = keys`, keys separated by commas, with `Shift+`, `Ctrl+` and `Alt+` modifiers,
key names after their place on a US keyboard (so letters work in any layout) or a raw GLFW code such as `#290`.

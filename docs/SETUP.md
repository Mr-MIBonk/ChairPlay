# Building and setting up ChairPlay

The README gives the short version; this page has every step and every setting.

## Get the source

DiPlay is a git submodule, so clone with it:

```
git clone --recursive <this repository>
```

In a clone made without `--recursive`, run `git submodule update --init`. The submodule is pinned to the DiPlay
version this build is tested with. To move to a newer DiPlay: `git -C upstream fetch`, `git -C upstream checkout
<commit or tag>`, build and run the tests, then commit the new `upstream` pointer.

`scripts\mirror-upstream.cmd <mirror-url>` copies DiPlay into a repository you control (create it empty and
private first) and points the submodule at it, so the build survives DiPlay's repository going away. With
`--local` only your clone uses the mirror and `.gitmodules` keeps DiPlay's URL for everyone else. Once the
submodule uses a mirror, run the script again before each `git -C upstream fetch`.

Turn on the repository's git hooks once per clone: `git config core.hooksPath .githooks`. They refuse a commit
that would add keys, the MFi identity, pairings or the app's settings, and a push of history that is not this
project's (DiPlay's tags, for example). Git runs hooks with its own bash, whatever shell the commit comes from.
`scripts\test-repo-tools.cmd` tests the hooks and the mirror script.

## Build

Needs JDK 17 (the only version this build is tested with). From the repository root:

```
gradlew jpackageImage
```

The result is `build\jpackage\ChairPlay\ChairPlay.exe`, a self-contained folder with its own runtime;
no installer or WiX is needed. The folder also carries the licence notices (`LICENSE`, `LICENSE-concentus`,
`LICENSE-speexdsp`, `THIRD_PARTY_NOTICES`, `licenses\`). Only the `windows-x86_64` native libraries are bundled.

The call echo canceller is DiPlay's copy of SpeexDSP, which is C. The build compiles it into `speex_echo.dll`
(next to `ChairPlay.exe`) with the C compiler of [zig](https://ziglang.org/), so no Visual Studio or MinGW is
needed. The first build downloads zig 0.16.0 (about 100 MB, checked against its SHA-256) into
`%USERPROFILE%\.gradle\caches\chairplay\zig` and reuses it after that; it needs internet access once.

The start and settings screens are a web page (React and TypeScript in `webui\`, built by Vite) that the app shows
in WebView2 inside its window. The build needs no Node.js on the machine: it downloads Node.js 24 (checked against
its SHA-256) into `%USERPROFILE%\.gradle\caches\chairplay\node`, installs the page's packages from
`webui\package-lock.json` (npm checks each against its integrity hash, and runs no install scripts), type-checks
and builds the page into the jar, and runs its Vitest tests with `gradlew test` (report in
`build\test-results\webui`). The web view host, `chairplay_webview.dll`, is the
[webview](https://github.com/webview/webview) library with a few calls of our own (`src\native\webview`), built by
zig against the headers of Microsoft's WebView2 SDK; both are downloaded once, pinned and checked, and the SDK's
`WebView2Loader.dll` ships next to `ChairPlay.exe`.

The app keeps its settings, keys and pairings in the folder of its exe. To run it from a folder of its own that
rebuilds never wipe, deploy it there:

```
gradlew deploy
```

This builds the image and syncs it into `dist\ChairPlay` in the project, replacing the program and keeping
`settings.properties`, `controls.properties`, `carplay-icon.png`, `window.properties`, `identity.properties`,
`pairings\`, `offline-mfi\`, `screenshots\` and `logs\`. It is outside `build\`, which `gradlew clean` wipes, and git ignores it.

To install somewhere else, give the folder as the `chairplay.home` property: once with `gradlew deploy
-Pchairplay.home=C:\Apps\ChairPlay`, or for good in `%USERPROFILE%\.gradle\gradle.properties`:

```
chairplay.home=C:/Apps/ChairPlay
```

`gradlew launchApp` starts the app from the same folder in its own console window, and `gradlew listDevices`
lists the Bluetooth and sound devices with it.

## In VS Code

The repository recommends the Gradle for Java extension, whose side panel lists every Gradle task (`test`,
`jpackageImage`, `deploy`, ...). Terminal > Run Task adds the rest: Deploy and run, Run ChairPlay (in its
own console window), List devices and Stop Gradle daemon. Stop the app by closing its window or with Ctrl+C in its
console, never with Terminate Task, which kills it hard.

## Set up

The files below live in the app folder, next to `ChairPlay.exe`.

1. **Pair the iPhone** in Windows Settings > Bluetooth & devices. `ChairPlay.exe devices` lists the radio and the
   paired devices (it connects to nothing).
2. **MFi identity files.** The wireless handshake needs an Apple MFi identity (certificate and key files). This
   project does not ship them and the app image never contains them. Put your own files in `offline-mfi\`.
3. **Settings.** `deploy` (or the first start, for a folder filled by hand) writes `settings.properties` with
   every setting at its default and a comment on each, together with `controls.properties` and `carplay-icon.png`;
   files already there are kept, and a settings file from an older version gets the settings it lacks added at
   its end. The start screen sets the Wi-Fi network and the settings screen a few others (see
   [The start and settings screens](#the-start-and-settings-screens)); the rest are edited here:
   - `wifi.ssid` and `wifi.passphrase`: the Wi-Fi network shared by the PC and the iPhone. Nothing connects
     without it; while it is blank the start screen asks for it;
   - `wifi.security`: `NONE`, `WEP`, `WPA_WPA2` (default), `WPA3_TRANSITION` or `WPA3_ONLY`;
   - `ui.autoconnect` (default `true`): connect to the iPhone as the app starts; `false` waits for Connect on the
     start screen;
   - `ui.autoshow` (default `true`): show CarPlay over the start screen as soon as the iPhone connects; `false`
     stays on the start screen, which then offers Show CarPlay;
   - `iphone.address`: the iPhone's Bluetooth address, only needed when several phones are paired;
   - `iphone.join.seconds` (default 30): how long an iPhone that paired before has to join the Wi-Fi after the
     Bluetooth start before the app starts over and gives it the network again. It joins within a second or two,
     so this only matters when it lost the Wi-Fi meanwhile; a new iPhone always has five minutes, to accept CarPlay.
     Ctrl+R (`key.reconnect`) starts over at once, ending a running session as closing the window does;
   - `device.name`: the name the iPhone shows for the PC, in its CarPlay settings and on the network (default
     ChairPlay, at most 63 bytes); the window title stays ChairPlay;
   - `car.label` and `car.icon`: the label and the image of the car button on the CarPlay home screen (default
     `Windows` and `carplay-icon.png`, a Windows window written into the app folder on the first start). Replace the
     image with any square PNG, JPEG, BMP or GIF; it is sent at 192 pixels at most;
   - `audio.output` and `audio.input`: the sound device CarPlay plays on and the microphone, `default` (the Windows
     default, as before) or a part of a device name; `ChairPlay.exe devices` lists the names;
   - `audio.echo.cancellation` (experimental, default `false`): `true` runs DiPlay's SpeexDSP echo canceller on
     calls, so the other side does not hear itself when CarPlay plays through speakers. It takes out of the
     microphone what the call stream played, as DiPlay does on head units. Siri and headphones do not need it;
     a canceller that fails stops for the rest of the call and logs why;
   - `log.carplay.data` (default `false`): `true` writes every message the iPhone sends about the route, the song,
     calls and its battery to a file in `logs\` (see [The CarPlay data log](#the-carplay-data-log));
   - `display.width` and `display.height`: the CarPlay screen size in pixels, positive even numbers
     (default 1280 x 720), and `display.fps`, 30 to 60 (default 60). The size alone decides how iOS draws:
     at @3x when both sides are at least about 1280 x 720, at @2x below that (the switch lies between 1200 x 676
     and 1280 x 720; 960 x 720 and 1280 x 540 are still @2x). Above the switch more pixels give room for more,
     not sharper: at 1920 x 1080 the home screen holds 5 x 3 icons of the same size instead of 4 x 2. Tested on
     iOS 18.6;
   - the screen's physical size: either `display.physical.width.mm` and `display.physical.height.mm`, or a pixel
     density `display.ppi` that the app turns into millimetres. Millimetres win over `display.ppi`; a width alone
     takes the height from the aspect ratio. Unset, the screen is 200 mm wide, as on DiPlay's head units. On iOS
     18.6 the size itself changes nothing (100 and 400 mm wide draw the same picture), but iOS takes the ratio of
     width to height in millimetres for the shape of a pixel: when it differs from the ratio in pixels, iOS lays
     out for that shape and the picture comes out squeezed or stretched in the window. Leave them blank unless you
     are testing; they are kept for later iOS versions, where the physical size may matter;
   - `window.width` and `window.height`: the size of the window on the PC (default: the display size). The picture
     is scaled into the window, so this changes only how big it looks, not what the iPhone draws. The app also
     remembers where you leave the window, its size and whether it is maximized, in `window.properties`, and
     opens it there the next time; changing these two settings (or the display size they follow) replaces the
     remembered size. A window left on a monitor that is no longer connected opens where Windows puts it. Delete
     `window.properties` to forget the place;
   - `window.controls` (default `false`): `true` opens a window of on-screen car controls beside CarPlay's: the knob
     (select in the middle, tilts around it, turns above its corners), back, home, Siri, the media keys, the call
     keys and the AltScreen's zoom. A button held with the mouse holds its control until the release, as a held
     key does, so CarPlay tells a long press. The window keeps its place and size under `controls.` in
     `window.properties`, and F12 saves it as `controls-<time>.png`;
   - `window.keep.aspect` (default `true`): the windows resize only with the shape of their picture, the
     main window's that of `display.width` x `display.height` and the AltScreen's that of its size; a maximized
     window still fills the screen. `false` lets them take any shape;
   - `altscreen.enabled` (experimental, default `false`): `true` declares a second CarPlay screen, the car's
     instrument cluster, and shows it in a window of its own (place and size remembered under `altscreen.` in
     `window.properties`). The iPhone learns of it when it connects, so a change applies at the next connection.
     `altscreen.width` and `altscreen.height` set its size in pixels (default 1440 x 542, Audi's Virtual Cockpit
     panel), and `altscreen.content` what it starts with: `map`, `card` (the turn card) or `map-card`. F11
     (`key.altscreen.content`) switches between them without reconnecting, and the mouse wheel over the window
     or + and − on either keyboard (`key.altscreen.zoom.in`/`out`) zoom the map, as a car's zoom control does. `altscreen.cards` places the navigation app's ETA and turn
     card: `auto` (default, CarPlay decides; with a wide cluster it keeps a column at the left free), `left`,
     `right`, `top`, or `off` (the map alone); Shift+F11 (`key.altscreen.cards`) moves them on the fly. The
     AltScreen opens on the content and layout F11 and Shift+F11 left it on (kept in `window.properties`), unless
     these two settings changed since. `altscreen.safe.top`/`bottom`/`left`/`right` (default 0) are
     pixels at each edge the iPhone keeps its map attribution and car marker out of, for an overlay of our own;
     all zero uses the whole panel, since nothing covers a window on a PC;
   - `display.theme`: `windows` (default: follows the Windows dark mode, also while running), `light`, `dark`, or
     `phone` (the iPhone decides by itself);
   - `display.right.hand.drive` (`true` puts the dock and controls on the right), `display.dock` (`auto`, `driver` or
     `bottom`), `display.safe.top`/`bottom`/`left`/`right` (pixels CarPlay keeps its controls out of) with
     `display.safe.draw.outside`, and `display.corners` (`square`, the default, fills the window to its corners; `rounded` keeps CarPlay's rounded corners).

   The settings apply at the next start (the Wi-Fi network with the next connection, `ui.autoshow` at once); the
   console prints what the iPhone was told, for example
   `display 1280x720 px, 200x113 mm (163 ppi), 60 fps; window 1280x720`.
4. **Firewall.** Windows Firewall drops the iPhone's inbound connections. From an administrator Command Prompt, add
   these rules once (the paths are those of the packaged exe; adjust them if you move the folder). The app does not
   change the firewall itself and prints the same commands at start when no rule named `ChairPlay CarPlay inbound`
   exists:

   ```
   netsh advfirewall firewall add rule name="ChairPlay CarPlay inbound" dir=in action=allow program="<folder>\ChairPlay.exe" protocol=TCP localport=7000-7010,49152-65535
   netsh advfirewall firewall add rule name="ChairPlay CarPlay inbound" dir=in action=allow program="<folder>\ChairPlay.exe" protocol=UDP localport=49152-65535
   netsh advfirewall firewall add rule name="ChairPlay CarPlay inbound" dir=in action=allow program="<folder>\ChairPlay.exe" protocol=UDP localport=5353
   ```

   Replace `<folder>` with the full path of `ChairPlay`, e.g. `C:\Apps\ChairPlay`.

   If Windows asks at the first start whether ChairPlay may use the network, answering Cancel creates a rule that
   blocks it, and a block rule wins over the ones above. At start the app names every enabled inbound block rule for
   its exe; delete those, or set them to Allow, in Windows Defender Firewall > Advanced settings > Inbound Rules. The
   check uses PowerShell's firewall cmdlets and finds rules whose program path is written out in full, not through an
   environment variable such as `%ProgramFiles%`.

## Running

```
ChairPlay.exe
```

With no arguments the launcher runs `run`. Passing any argument replaces that default, so `ChairPlay.exe devices`
and `ChairPlay.exe probe-bt [iphone-address]` run those commands instead. The app is a console program: it logs
to the console window, and the CarPlay screen opens in its own window, titled ChairPlay.

The iPhone lists the PC as **ChairPlay** (or `device.name`). The CarPlay home screen shows a Windows icon (`car.icon`) where a car shows its maker's;
tapping it opens the settings screen over CarPlay and leaves CarPlay running.

Besides touch with the mouse, the keyboard and the mouse wheel work as a car's controls. These are the defaults;
`controls.properties` in the app folder, written on the first start with every action documented, rebinds them
(`key.siri=Ctrl+S, F10`; empty turns an action off) and sets the wheel (`wheel.action=knob` or `none`,
`wheel.invert`, `wheel.notches.per.step`) and `key.repeat.every`. A mistake in it stops the start and names the
setting; delete the file to get the defaults back.

F12 (`key.screenshot`) saves the CarPlay screen, and the AltScreen when it is on, as PNGs at the size the
iPhone draws them, not the windows', in `screenshots` in the app folder; the console prints the file names. For unattended runs, the environment variable
`CHAIRPLAY_SCREENSHOT_AFTER=<seconds>` saves one screenshot that many seconds after the first picture. It saves
CarPlay's picture only, never the start or settings screen.

## The start and settings screens

Whenever CarPlay is not on screen, the window shows the **start screen** instead of black:

- where the connection stands: *Wi-Fi network needed*, *Not connected* (with `ui.autoconnect=false`, until
  Connect), *Starting*, *Waiting for the iPhone* (calling it over Bluetooth), *Connecting* (the iPhone answered
  and joins the Wi-Fi; a new one asks to accept CarPlay first), *Connected*, or *Trying again*, with the last
  error, its time and a hint for the usual causes (missing MFi files, Bluetooth, Wi-Fi). The buttons are Connect,
  Reconnect now (what Ctrl+R does) and, with a session, Show CarPlay;
- the **Wi-Fi network**: name, password and security. The stored password is never shown or sent to the page,
  which only learns whether one is set; typing a new one replaces it, leaving the field empty keeps it. With
  `ui.autoconnect` the first network saved starts the connection; a change during a session applies with the
  next connection, which Reconnect now starts at once;
- the setup steps, with what the app found: the MFi files in `offline-mfi\`, the firewall rule (with the netsh
  commands and a Copy button when it is missing), pairing in Windows and accepting CarPlay on the iPhone.

With `ui.autoshow` CarPlay covers the start screen as soon as the session is active; otherwise the start screen
stays and offers Show CarPlay. When the session ends, the start screen comes back.

The **settings screen** opens from the start screen's Settings button, and over CarPlay from the car button on the
CarPlay home screen; its back button (or Esc) returns to CarPlay when there is a session, else to the start screen.
It has the two switches, the AltScreen (on or off, and its size) and the main window's size. Each change is
written into `settings.properties` in place, keeping the comments and every other line, after the same checks the
app makes when it starts, and the screen says when it applies: `ui.autoshow` at once, the Wi-Fi network with the
next connection, the others when ChairPlay starts again (listed at the top until then).

While a screen covers the window, the keyboard and the mouse are the screen's; while CarPlay shows, the screen is
hidden and every key works as before.

The page runs in Microsoft Edge WebView2, which Windows 11 and an updated Windows 10 carry. Without it (or without
`chairplay_webview.dll`) the app opens the page in the default browser, says so in the console, and the car
button minimizes the window as before. WebView2 keeps its profile (cache) in `%LOCALAPPDATA%\ChairPlay\WebView2`.

The page talks to the app over HTTP on `127.0.0.1` only, on a free port, with a random key in its address; the
console prints the whole address at start (`UI at http://127.0.0.1:<port>/?token=<key>`), and any browser on the
PC can open it, for example to look at the screens from a second monitor. Every API call carries the key, and a
request naming another host than `127.0.0.1` or `localhost` is refused.

### Changing the screens

The page's sources are in `webui\src`. To work on them with Vite's dev server, which reloads the page on every save:

1. Start the app with a fixed port for its HTTP server, for example from a Command Prompt in the app folder:
   `set CHAIRPLAY_UI_PORT=47800` and then `ChairPlay.exe`. Without an iPhone at hand, put `wifi.ssid=TestNet` and
   `iphone.address=02:00:00:00:00:01` in a test folder's `settings.properties` (`gradlew deploy
   -Pchairplay.home=<test folder>`), so it connects to nothing.
2. In `webui\`, start the dev server with the build's own Node.js (run `gradlew npmCi` once first):

   ```
   set PATH=%USERPROFILE%\.gradle\caches\chairplay\node\node-v24.22.1-win-x64;%PATH%
   npm run dev
   ```

   Its proxy sends `/api` to `127.0.0.1:%CHAIRPLAY_UI_PORT%` (47800 when unset).
3. Open `http://localhost:5173/?token=<the key from the app's console>` in a browser.

`npm test` (or `gradlew webuiTest`) runs the page's tests, and `gradlew webuiBuild` builds it as the app ships it.
`CHAIRPLAY_UI_DEBUG=1` turns on the developer tools (F12) and the context menu inside the app's window.

## The CarPlay data log

Besides the picture and the sound, the iPhone tells the car about the drive and the phone over iAP2: route guidance
(the next maneuver, its distance, the road, the arrival time, the remaining time and distance), now playing (title,
artist, album, position, playback state) and its artwork, calls, the phone's network and its battery. The app
collects them for screens to come, and asks for more of the song (album artist, genre, track and disc, queue,
shuffle and repeat), of calls (the number's label, FaceTime, conferences) and of the network (carrier,
registration, airplane mode, voicemail) than DiPlay does. Lane guidance and the phone's lists (recent calls,
favourites) would need DiPlay's identification to declare them, so they do not arrive. With `log.carplay.data=true` it also writes each of these messages, as it
arrives, to `logs\carplay-data-<yyyy-MM-dd-HHmmss>.jsonl` in the app folder: one file per start, made with the first
message, and one JSON object per line, for example

```
{"time":"2026-10-22T14:03:07.412+02:00","link":"wifi","id":"0x5201","name":"RouteGuidanceUpdate","fields":{"routeGuidanceState":"ROUTE_SET","currentRoadName":"Main Street","distanceToNextManeuver":150,"currentManeuverList":[3,4]}}
```

`link` says which iAP2 link carried it (`bluetooth` while CarPlay starts, `wifi` once it runs over Wi-Fi). A
parameter the app does not know yet is listed under `unknown` as `"<id>:<hex>"`, and a message it cannot decode
is written as `hex`. Only these data messages are written: the Wi-Fi passphrase, the MFi certificate and its
challenges never reach the file. The file grows by a line or two a second while music plays, and holds the
names and numbers of callers; delete it when it is no longer needed.

## Keyboard bindings

See the table in the README for the defaults. `controls.properties` lists every action with its keys and a
description; each line is `action = keys`, keys separated by commas, with `Shift+`, `Ctrl+` and `Alt+` modifiers,
key names after their place on a US keyboard (so letters work in any layout) or a raw GLFW code such as `#290`.

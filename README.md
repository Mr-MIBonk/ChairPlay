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

## Set up

1. **Pair the iPhone** in Windows Settings > Bluetooth & devices. `DiPlayDesktop.exe devices` lists the radio and the
   paired devices (it connects to nothing).
2. **MFi identity files.** The wireless handshake needs an Apple MFi identity (certificate and key files). This
   project does not ship them and the app image never contains them. Put your own files in
   `%APPDATA%\DiPlayDesktop\offline-mfi\`.
3. **Settings.** The first start creates `%APPDATA%\DiPlayDesktop\settings.properties`. Fill in:
   - `wifi.ssid` (required) and `wifi.passphrase`: the Wi-Fi network shared by the PC and the iPhone;
   - `wifi.security`: `NONE`, `WEP`, `WPA_WPA2` (default), `WPA3_TRANSITION` or `WPA3_ONLY`;
   - `iphone.address`: the iPhone's Bluetooth address, only needed when several phones are paired;
   - `display.width` and `display.height`: the CarPlay screen size in pixels, positive even numbers
     (default 1280 x 720).
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
to the console window, and the CarPlay screen opens in its own window.

## Known limits

- Wireless only, and only when the PC and the iPhone share a Wi-Fi network (no PC hotspot, no wired mode).
- No echo cancellation: use headphones for calls.
- H.264 video only (no HEVC), decoded in software.

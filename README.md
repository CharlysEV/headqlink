<p align="center">
  <img src="docs/icon.png" width="128" alt="HeadQLink icon" />
</p>

# HeadQLink

Android app that brings **Android Auto** and a custom side panel with extra features to the
**Leapmotor C10** screen, using the car's built-in mirroring connection (SSPLink over WiFi Direct).
The phone can stay locked with the screen off. No root required.

Personal and experimental project. Fork of [Open Headunit](https://github.com/andreknieriem/open-headunit)
(original README at [README_OPEN_HEADUNIT.md](README_OPEN_HEADUNIT.md)).

## Features

- ✅ **Phone screen off** — once connected, the phone can be locked with the screen off; projection keeps running.
- ✅ **Multitouch** — all touch points from the car's screen are forwarded to Android Auto (up to 3 fingers for pinch-to-zoom in Maps, etc.).
- ✅ **Steering wheel controls via Bluetooth** — media keys (play/pause, next, previous) work through the car's existing HFP/AVRCP Bluetooth connection; no extra pairing needed.
- ✅ **Extended interface** — an optional side panel with extra screens (route planner, efficiency, radio, photos, videos, games and more). **Only intended for use while the vehicle is stationary.**
- 🚧 **Removing the need for accessibility settings.** Currently, with Android Auto 17.4+, the app requires enabling AA's developer mode (a one-time step). Investigating alternative launch paths to eliminate this requirement.
- 🚧 **Performance.** Targeting 30/60 fps with no substantial frame loss.

## How it works (two modes depending on Android Auto version)

| | Android Auto < 17.4 | Android Auto ≥ 17.4 |
|---|---|---|
| **Launch method** | Direct: the app starts AA's wireless setup activity itself | Developer Head Unit Server on port 5277 |
| **Accessibility service** | Not needed | Required (to start/stop the server) |
| **AA developer mode** | Not needed | Required (one-time setup) |
| **Complexity** | Simple and automatic | Requires initial configuration |

With **AA < 17.4**, HeadQLink launches Android Auto's wireless projection directly — no developer mode, no accessibility service, no exposed network port. This is the ideal path.

With **AA ≥ 17.4**, Google removed the direct launch path. The only viable route is AA's built-in developer head unit server, which requires enabling developer mode once and granting HeadQLink the accessibility permission so it can start and stop the server automatically. The server listens on all network interfaces while active, so HeadQLink shuts it down as soon as the session ends.

## Requirements

- A Leapmotor C10 with the mirroring app on its screen.
- An Android phone with Android Auto installed. Tested on Android 16 with Snapdragon 8 Gen 3;
  other devices may work but are untested.
- For **AA ≥ 17.4**: AA developer mode enabled and the HeadQLink accessibility service active.
- To build from source: JDK 17 and the Android SDK (compileSdk 36). Dependencies (AndroidX, Media3,
  Glide, protobuf…) are downloaded by Gradle.
- Internet for features that use it: OpenStreetMap (Nominatim, Overpass), OSRM, Open-Meteo and
  radio-browser.info. The app does not send telemetry.

## Installation

1. Download the APK from the latest **[Release](../../releases)** and install it (Android will ask
   to allow installation from the browser or file manager).
2. Open HeadQLink and follow the setup wizard: permissions and, for AA ≥ 17.4, accessibility service
   and Android Auto developer mode.
3. On the car's screen, open the mirroring app and tap **Connect** on the phone (or enable automatic
   connection via Bluetooth).

To build it yourself: `./gradlew assembleGithubDebug` (signs with your key if `key.properties` exists;
without it, the APK uses the default debug key).

## License

[GNU AGPL-3.0](LICENSE). This project incorporates code from
[Open Headunit](https://github.com/andreknieriem/open-headunit) (whose original copyright notices
are preserved, including [Michael Reid's](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)), but **HeadQLink
is an independent project with no relationship to Open Headunit or its authors.** It is not
endorsed, supported or affiliated with them in any way.

This project is not affiliated with, endorsed by or sponsored by Leapmotor, Google, Neusoft, Open
Headunit or any other company or project. Leapmotor, C10, Android Auto and QDLink are trademarks of
their respective owners and are mentioned here only to describe what the app works with.

## Disclaimer: AS IS, NO WARRANTIES

**THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,** including
but not limited to the warranties of merchantability, fitness for a particular purpose, operation,
safety and non-infringement. It is experimental, relies on an undocumented protocol and may fail,
stop working or behave unexpectedly at any time.

**THE AUTHORS AND CONTRIBUTORS ASSUME NO LIABILITY** for any damages, direct or indirect, of any
kind, arising from the use, misuse or inability to use this software, including but not limited to:
accidents, injuries, damage to the vehicle, phone or third parties, data loss, fines or penalties,
loss of vehicle or phone warranty, and violation of third-party terms of service.

By installing or using this software **you accept that you do so at your own risk**. The driver is
solely responsible for complying with traffic laws and driving safely: **do not use the app or watch
videos, games or other content while driving.**

Sections 15 (no warranty) and 16 (limitation of liability) of the [AGPL-3.0 license](LICENSE) also
apply.

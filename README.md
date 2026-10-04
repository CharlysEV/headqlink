# HeadQLink

App Android que lleva **Auto** (Android Auto) y un panel propio con más funciones a la pantalla del
**Leapmotor C10**, usando la conexión QDLink del coche (SSPLink sobre WiFi Direct). El móvil puede ir
bloqueado y con la pantalla apagada. Sin root.

Proyecto personal y experimental. Es un fork de [Open Headunit](https://github.com/andreknieriem/open-headunit)
(README original en [README_OPEN_HEADUNIT.md](README_OPEN_HEADUNIT.md)).

## Key features

- **Phone screen off** — once connected, the phone can be locked with the screen off; projection keeps running.
- **Multitouch support** — all touch points from the car's screen are forwarded to Android Auto (up to 3 fingers for pinch-to-zoom in Maps, etc.).
- **Steering wheel controls via Bluetooth** — media keys (play/pause, next, previous) work through the car's existing HFP/AVRCP Bluetooth connection; no extra pairing needed.
- **Extended interface** — an optional side panel with extra screens (route planner, efficiency, radio, photos, videos, games and more). **Only intended for use while the vehicle is stationary.**
- **Work in progress: removing the need for accessibility settings on the phone.** Currently, with Android Auto 17.4+, the app requires enabling AA's developer mode (a one-time step). We are actively investigating alternative launch paths that would eliminate this requirement.

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
3. En el coche, abre QDLink y pulsa **Conectar** en el móvil (o activa la conexión automática por
   Bluetooth).

Para compilarlo tú: `./gradlew assembleGithubRelease` (firma con tu clave si existe `key.properties`;
sin ella, el APK sale sin firmar).

## Licencia

[GNU AGPL-3.0](LICENSE). This project incorporates code from
[Open Headunit](https://github.com/andreknieriem/open-headunit) (whose original copyright notices
are preserved, including [Michael Reid's](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)), but **HeadQLink
is an independent project with no relationship to Open Headunit or its authors.** It is not
endorsed, supported or affiliated with them in any way.

Este proyecto no está afiliado, respaldado ni patrocinado por Leapmotor, Google, Neusoft, Open
Headunit ni ninguna otra empresa o proyecto. Leapmotor, C10, Android Auto y QDLink son marcas de
sus titulares y aquí solo se nombran para describir con qué funciona la app.

## Aviso legal: TAL CUAL, SIN GARANTÍAS

**ESTE SOFTWARE SE PROPORCIONA «TAL CUAL» (AS IS), SIN GARANTÍA DE NINGÚN TIPO, NI EXPRESA NI
IMPLÍCITA,** incluidas, entre otras, las de comerciabilidad, idoneidad para un fin concreto,
funcionamiento, seguridad y no infracción. Es experimental, se basa en un protocolo no documentado y
puede fallar, dejar de funcionar o comportarse de forma inesperada en cualquier momento.

**LOS AUTORES Y COLABORADORES NO ASUMEN NINGUNA RESPONSABILIDAD** por daños, directos o indirectos,
de ningún tipo, derivados del uso, del uso incorrecto o de la imposibilidad de usar este software,
incluidos, entre otros: accidentes, lesiones, daños al vehículo, al móvil o a terceros, pérdida de
datos, multas o sanciones, pérdida de la garantía del vehículo o del móvil, e incumplimiento de las
condiciones de uso de terceros.

Al instalar o usar este software **aceptas que lo haces bajo tu exclusiva responsabilidad**. El
conductor es el único responsable de cumplir las normas de tráfico y de conducir con seguridad:
**no manipules la app ni mires vídeo, juegos u otro contenido mientras conduces.**

Se aplican además las cláusulas 15 (sin garantía) y 16 (limitación de responsabilidad) de la
[licencia AGPL-3.0](LICENSE).

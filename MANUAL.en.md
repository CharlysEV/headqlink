# HeadQLink · User manual

**Languages:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · **English** · [Italiano](MANUAL.it.md)

Driver's manual for HeadQLink version **0.2** (fork [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink)).
Button and menu names are shown in "quotes", exactly as the app shows them in English.

> [!WARNING]
> Do all the setup **with the car parked**. Don't handle the phone while driving, and don't watch videos, TV, web pages
> or games on the car's screen unless the car is stopped.

**Quick summary**

1. Install the APK from [GitHub Releases](https://github.com/CharlysEV/headqlink/releases).
2. Open HeadQLink and follow the 3-step wizard until the "Requirements check" says "All set".
3. In the car: turn on the phone's hotspot, open the mirroring app on the car's screen and tap "Connect".
4. When Android Auto appears, lock the phone and put it away.

## Contents

1. [What it is and what you need](#1-what-it-is-and-what-you-need)
2. [Installation](#2-installation)
3. [First start](#3-first-start)
4. [Using it in the car](#4-using-it-in-the-car)
5. [Useful settings](#5-useful-settings)
6. [Heat and battery](#6-heat-and-battery)
7. [Troubleshooting](#7-troubleshooting)
8. [Exporting the log to ask for help](#8-exporting-the-log-to-ask-for-help)
9. [Security, privacy and legal notice](#9-security-privacy-and-legal-notice)

---

## 1. What it is and what you need

HeadQLink brings **Android Auto** to the **Leapmotor C10** screen through the mirroring connection the car already has
(the one used by its QDLink/SSPLink app). No dongle or cable is needed: the phone and the car talk over Wi-Fi, and the
phone can stay **locked with the screen off**. No root required.

There are two modes:

| Mode | What you see in the car |
|---|---|
| "Auto" (recommended) | Android Auto in full screen: maps, music and messages. It is the lightest on the phone and the link. |
| "Auto extended" | The same, plus its own panel on the left with more car and trip information (route, driving, trips, gauges, efficiency) and more features (photos, videos, web, TV, radio, games). |

**What you need**

| | |
|---|---|
| Car | Leapmotor C10 with its mirroring (phone projection) app on the screen. |
| Phone | Android with **Android Auto** installed. **Android 10 or later** is recommended; choosing the app language needs Android 13. |
| Tested on | Samsung Galaxy **S25 Ultra** with **Android 16** and Android Auto 17.7. Other phones may work but haven't been tested. |
| Android Auto 17.4 or later | You need to turn on Android Auto's **developer mode** once and HeadQLink's **accessibility** service. The app guides you (section 3). Older versions don't need either. |
| Internet (optional) | Only for "Auto extended" features such as routes, weather or radio. |

> [!NOTE]
> **Testing status.** HeadQLink is a personal, experimental project. On a real trip with a C10, an S25 Ultra and the
> "Phone hotspot" connection, Android Auto ran in two sessions of about 16 minutes each. The phone got too hot in that
> test, so this version adds the "Car" picture profile, turning the phone screen off and heat adaptation (section 6).
> The Wi-Fi Direct connection with the QDAuto engine hasn't been tested in the car yet.

---

## 2. Installation

HeadQLink isn't on Google Play: you install it from an APK file.

### Install

1. On the phone, open [github.com/CharlysEV/headqlink/releases](https://github.com/CharlysEV/headqlink/releases).
2. In the latest release, expand **Assets** and tap the `.apk` file (something like `com.headqlink.app_0.2….apk`).
3. Open the downloaded file (from the notification, or from your file manager's Downloads folder).
4. Android will say it can't install unknown apps from that source. Tap **Settings**, turn on **Allow from this
   source** and go back.
5. Tap **Install**.

<!-- screenshot: Android "install unknown apps" prompt -->

> [!TIP]
> **Play Protect warning.** Because the app doesn't come from Google Play, Play Protect may warn that it's unknown or
> block it. If you trust this project, tap **More details › Install anyway**.
>
> **Samsung:** if **Auto Blocker** is on (Settings › Security and privacy), it won't let you install APKs. Turn it off
> to install and turn it back on afterwards if you like.

### Update

1. Download the new APK from the same Releases page.
2. Install it over the one you have: your settings are kept.
3. Open HeadQLink and look at the "Requirements check": **Android often turns accessibility off when an app is
   updated**, and you'll need to turn it back on.

> [!IMPORTANT]
> You can only update with an APK **signed with the same key**, that is, from this fork's Releases. If Android says the
> app wasn't installed or conflicts with another one, you have a version with a different signature (for example the
> original HeadQLink by ryazrm, which uses the same app ID, or one you built yourself). Uninstall it first; you'll lose
> its settings.

### Uninstall

1. If you won't use it again: in Android Auto's settings you can leave developer mode (⋮ menu).
2. Android Settings › Apps › HeadQLink › **Uninstall**.
3. Exported logs stay in Downloads/HeadQLink: delete them by hand if you don't want them.

---

## 3. First start

The first time you open HeadQLink, a 3-step wizard appears. Do it with the car parked; ideally inside the car with the
mirroring app open on its screen.

### Step 1 of 3: welcome

The "Your phone, on the C10's screen" screen, with what you need "Before you start". Tap "Start".

### Step 2 of 3: mode and connection

<!-- screenshot: wizard step 2, mode and connection -->

**"What do you want to see in the car?"** Choose "Auto extended" or "Auto" (table in section 1).

> [!NOTE]
> **Privacy in "Auto extended".** Its panels use the phone's location, if you allow it, and open internet services:
> OpenStreetMap and OSRM for routes and chargers, Open-Meteo for weather and wind, and radio-browser.info for radio. To
> work these out, those services receive your position or your destination. If you don't want that, choose "Auto" or
> don't grant the "Photos, videos and location" permission.

**"Connection to the car"**

| Option | How it works | Recommendation |
|---|---|---|
| "Phone hotspot" | The car joins your phone's hotspot. | **Recommended with "Auto"**: it's the one tested on the C10, also with the screen off. |
| "Wi-Fi Direct" | The car creates the network and the phone joins it. The phone's hotspot must be off. | The original HeadQLink's connection. Not yet tested in the car with the QDAuto engine. |
| "USB cable" | The phone connects with a cable to the car's USB data port, without Wi-Fi. | **Recommended with "Auto extended"**: picture at 60 fps without radio dropouts, and the phone charges. It only works if the car switches the phone to accessory mode, as the C10 does. See [USB cable connection](#usb-cable-connection). |

> [!TIP]
> The "Recommended" tag follows the mode: "Phone hotspot" with "Auto" and "USB cable" with "Auto extended". Pick the
> one you want before tapping "Continue".

Tap "Continue". You can change the mode and the connection at any time with the "Change" button on the main screen.

### Step 3 of 3: "Set up Auto extended" (or "Set up Auto")

This is the **requirements check**: a list of everything your setup needs. It's the same list you'll find later in the
⚙ menu › "Requirements check".

<!-- screenshot: Requirements check screen -->

**How to read it**

- At the top it says "All set" (green) or "N things missing": red if something required is missing, amber if only
  something recommended is.
- Each row has an icon (✓ done, ! missing, ✕ error, ? unknown, … checking, i tip), its importance ("Required",
  "Recommended", "Optional" or "Tip") and a **button that takes you to the exact setting**.
- It refreshes by itself when you come back to HeadQLink.

**Row by row.** Only the rows that matter for your mode and connection are shown.

| Row | What it means | What to tap |
|---|---|---|
| "Android Auto" | Must be installed and enabled. Shows its version. | "Install" (Play Store), or "App info" if it's disabled. |
| "HeadQLink accessibility" (Required with Android Auto 17.4 or later; Optional with the manual start) | HeadQLink uses it to start Android Auto without you seeing it and to receive the car's touches. In Android Settings it's called **"HeadQLink touch"**. | "Turn on" and switch on "HeadQLink touch". See the note below. |
| "No accessibility?" (Tip, only when accessibility is missing) | You can start the Android Auto server yourself and leave it off. | "Manual start" (see [Without accessibility](#without-accessibility-manual-start-of-the-android-auto-server)). |
| "Android Auto developer mode" (Required with 17.4 or later) | Android Auto only accepts a "car screen" inside the phone through its developer mode. You turn it on once. | "Open AA" and follow the guide below. "Check" verifies it (accessibility must be on). |
| "Android Auto server" (Info, only with the manual start) | What is known **without connecting to it** (a test connection would block it): "In use by HeadQLink", "Not answering" (the attempts with the car fail) or, if unknown, how to start it. It never stops you connecting: if it doesn't answer, HeadQLink tells you and retries. | "Open AA" (⋮ › "Stop head unit server" if shown and ⋮ › "Start head unit server") or "Automatic mode". |
| "Notifications" (Recommended) | To see the connection status and warnings. | "Allow" (or "Open" if you blocked them). |
| "No battery restrictions" (Recommended) | So that Android doesn't close HeadQLink with the screen off or block the automatic connection. | "Allow" and accept Android's prompt. |
| "Samsung: never sleeping apps" (Tip, Samsung only) | Samsung closes background apps on its own. The app can't check this. | "Open" and choose "Unrestricted". Also: Settings › Battery › Background usage limits › Never sleeping apps › add HeadQLink. |
| "… power manager" (Tip, other brands) | Xiaomi, Honor, Oppo and others have their own battery saver. | "Open" and allow auto-start or background activity. |
| "Phone hotspot" (Required with "Phone hotspot") | The hotspot must be on. When it's fine it says "Hotspot on (…)". | "Open" and turn it on. |
| "5 GHz band" (Tip) | The app can't read this. 5 GHz works better, because on 2.4 GHz the radio is shared with the car's Bluetooth. | "Open": pick 5 GHz and turn off the hotspot's auto-off. |
| "Nearby Wi-Fi devices" or "Location" (Required with "Wi-Fi Direct") | Permission to join the car's Wi-Fi Direct network ("Location" on Android 12 or earlier). | "Allow" (or "Open" if you denied it). |
| "Wi-Fi on" (Required with "Wi-Fi Direct") | Wi-Fi Direct needs Wi-Fi on, even if you're not connected to any network. | "Open". |
| "Hotspot off" (Required with "Wi-Fi Direct") | Wi-Fi Direct doesn't work with the hotspot on. | "Open" and turn it off. |
| "QDLink" (Recommended) | The official QDLink app is installed on the phone. If it's open, it takes port 18463 and the car can't find HeadQLink. | "App info" › "Force stop" before connecting. |
| "QDLink" or "Port 18463" in red ("Busy") | QDLink (or another app) is open right now. | "Force stop". |
| "Bluetooth (nearby devices)" (Required if you turn on the automatic connection) | To recognise the car's Bluetooth and connect by itself. | "Allow". |
| "Display over other apps" (Optional) | To open Android Auto with the phone in the background. | "Allow". |
| "Photos, videos and location" (Optional, "Auto extended") | For the gallery and the driving panels in the car. | "Allow". |
| "Location all the time" (Recommended, "Auto extended") | So the car data keeps going with the phone locked (see [What you see and how to use it](#what-you-see-and-how-to-use-it)). | "Open": explains why and opens Android's page; choose "Allow all the time". If it doesn't have the location yet, it asks for it first. |

> [!IMPORTANT]
> **"Restricted setting" when turning accessibility on.** On Android 13 or later, Android doesn't let apps installed
> from an APK turn on accessibility straight away. If "Restricted setting" appears when you switch on "HeadQLink touch":
>
> 1. Tap the row's second button, "App info" (or go to Settings › Apps › HeadQLink).
> 2. Tap the ⋮ menu (top right) › **"Allow restricted settings"** and confirm. This option appears after you've tried
>    to turn the service on once.
> 3. Go back, tap "Turn on" and switch on "HeadQLink touch".
>
> If the row says "On but not running (Android stopped it)", turn it off and on again.
>
> Rather skip all this? See [Without accessibility](#without-accessibility-manual-start-of-the-android-auto-server).

**How to turn on Android Auto developer mode** (the same guide the app shows):

1. Tap "Open AA". If it isn't shown, tap "Check" first.
2. Scroll to the bottom of the Android Auto settings.
3. Tap **"Version" 10 times** and accept the warning.
4. Come back to HeadQLink: it checks by itself.

<!-- screenshot: Android Auto settings with "Version" at the bottom -->

With the "Phone hotspot" connection, the list is followed by "How to connect the car to the hotspot" (section 4).

Tap **"Finish"**. If something required is missing, the "Still missing" prompt tells you what: you can go "Back" or
"Finish anyway" and complete it later.

> [!NOTE]
> When you finish the wizard, and every time you open HeadQLink once it's set up, **the app starts connecting by
> itself** (just like tapping "Connect") and waits for the car for up to 5 minutes (or "Wait for the car", if longer).
> With Android Auto 17.4 or later you'll briefly see a "Starting Auto…" overlay: don't touch anything. If you're not in
> the car, tap "Disconnect".

### Without accessibility (manual start of the Android Auto server)

If you don't want to (or can't) turn accessibility on, choose **"Picture settings" › "Advanced ▾" › "Android Auto server
start" › "Manual (no accessibility)"**, or tap "Manual start" on the "No accessibility?" row of the "Requirements check".
With "Auto" and "Auto extended" HeadQLink then doesn't use it at all: accessibility becomes "Optional" ("Only for the
automatic mode") and an "Android Auto server" row appears.

> [!IMPORTANT]
> **The Android Auto server keeps answering while it's on, as long as connections are closed in an orderly way.**
> HeadQLink always closes its own that way (Disconnect, end of the trip, picture profile change), so **you don't need to
> restart it between trips**. What does block it is a connection **cut halfway** (something that connects and leaves
> without a word, or a session that drops abruptly): from then on it accepts connections but answers nobody until you
> stop it and start it again. That's why HeadQLink **doesn't check it before connecting** (a check would be exactly
> that): the first real attempt with the car tells.

1. Start the server yourself: "Open AA" › ⋮ (top right) › **"Start head unit server"**. If that option isn't there,
   turn on developer mode first (10 taps on "Version"). If the menu says "Stop head unit server", it's already on:
   leave it. It may go off when the phone restarts (or Android Auto updates): then start it again.
2. Tap "Connect" (or let it start by itself over Bluetooth or the cable). When the car connects, HeadQLink launches
   Android Auto and waits for it to **answer** (6 s at most). With the server on it also works **with the phone
   locked**: no unlocking and no automation needed.
3. If it doesn't answer (off, or blocked by a connection cut halfway), you'll see the **"Start (or restart) the Android
   Auto server"** notification and the "Auto" row (and the widget) says "Waiting for the Android Auto server". Tap it:
   Android Auto › ⋮ › "Stop head unit server" (if shown) and ⋮ › "Start head unit server", and come back. HeadQLink
   retries by itself every 5 s while the car is connected; as soon as Android Auto answers, the notification goes away
   by itself.
4. Everything else works as with the automatic mode: if the car leaves, 30 s of live video and then Android Auto paused;
   when "Wait for the car" runs out (or on "Disconnect") HeadQLink closes Android Auto in an orderly way. The one thing
   it doesn't do is switch the server off (it can't without accessibility): **it stays on, and the next trip uses it
   again without a restart**, even with the phone locked, as long as it's still on.

What changes, and why automatic is still the **recommended** mode:

- You have to start it by hand whenever it's off (after a phone restart, for example) and, if it ever gets blocked,
  stop it and start it again.
- **It stays on** until you stop it, and it listens on the whole network: on a **public Wi-Fi**, any device on that
  network could try to connect to it (and, by connecting and leaving without a word, leave it blocked). Stop it when
  you're not going to use it.
- The "App" mode (a specific app in the car) still needs accessibility for the touches.

> [!TIP]
> **Installing with Obtainium usually avoids the "restricted settings" step.** Obtainium installs through Android's
> session installer and, on Android 13 and 14, accessibility can then usually be turned on straight away. On Android 15
> or later it isn't guaranteed.

### Android Auto versions

Since 17.4, HeadQLink starts Android Auto through its developer "head unit server", and every new Android Auto version
can change it (the texts of its ⋮ menu or how it handles the connection). Versions it has been tested with:

| Android Auto | Status |
|---|---|
| 17.7.x | **Tested** with HeadQLink (October 2026) |
| 17.8, 17.9 and later | **Not tested yet.** Other projects (Open Headunit) report problems: with 17.8 the connection drops after 1-2 s; with 17.9 the automatic server start stops working |

The "Checklist" shows it in the "Android Auto 17.x" row (tested / not tested yet), and the log records it at start-up
(`androidAuto=…`) and for every session (`aa_version` column of `sessions.csv`).

**Recommended: turn off Android Auto auto-update.** Play Store › search "Android Auto" › ⋮ (top right) › untick "Enable
auto update". That way a new version won't leave the car without a picture overnight; update by hand once it has been
tested.

If a new version stops working:

1. **"HeadQLink can't find the server button"**: Android Auto has changed its menu. Tap "Open AA" and start it by hand
   (⋮ › "Start head unit server", or whatever it is called now), or tap "Manual start" so you don't depend on
   accessibility (see [Without accessibility](#without-accessibility-manual-start-of-the-android-auto-server)).
2. **"Android Auto connects and drops after a few seconds"** (two drops in a row within 10 s): clear Android Auto's
   cache (Settings › Apps › Android Auto › Storage › **Clear cache**; no need to clear its data) and stop and start its
   server (⋮). HeadQLink retries by itself, at most every 10 s.
3. If it persists, **export the log** (section 8) and send it: it contains the Android Auto version and, once, what
   HeadQLink saw in its menu (the menu texts, no personal data), which is what's needed to adapt it.

> [!WARNING]
> **Going back to an older Android Auto is not "Uninstall updates".** On many phones the factory copy of Android Auto is
> just a stub (1.x) that's useless for HeadQLink: you have to install by hand the APK of the tested version (17.7.x)
> that matches your phone (architecture and Android version) and then turn off auto-update, or the Play Store will
> upgrade it again.

---

## 4. Using it in the car

### The main screen

<!-- screenshot: HeadQLink main screen -->

- At the top, the mode and connection (for example "Auto extended · Phone hotspot") with the "Change" button, and the ⚙
  menu.
- The "Connect when the car's Bluetooth is detected" switch.
- The "Status" card. At the top, the "Live" panel: two big numbers, the **fps** (frames per second) and the **Mbps**
  (Mbit/s) of the video reaching the car, each with a bar that fills up against what your picture profile delivers
  (30 fps with "Car"). The dot next to "Live" blinks while video is flowing; with no video, the numbers show "—".
  Below it, one row for each part of the connection:

| Row | What it tells you |
|---|---|
| "Car" | "Disconnected", "Searching…", "Found, connecting…", "Connected" (with the car screen size) or "Reconnecting… (Android Auto on hold)". |
| "Network" | "Hotspot on (…)", "Hotspot off: turn it on", "Wi-Fi Direct: looking for the car" or "Wi-Fi Direct: in the car's group (…)". If the hotspot is off, tapping the row opens its settings. |
| "Picture" | The video being sent to the car. |
| "Auto" | Android Auto's status and, if it doesn't start, why (for example "Doesn't start: unlock the phone"). |
| "Requirements" | "All set" or "N things missing". Tap it to open the "Requirements check". |

- A help text and the big "Connect" / "Disconnect" button.

### Step by step with "Phone hotspot" (recommended with "Auto")

1. **Turn on the phone's hotspot**, preferably on 5 GHz and with no auto-off.
2. **First time only:** in the car, go to Settings › Wi-Fi and pick your phone's hotspot. After that the car joins by
   itself.
3. **Open the mirroring app** on the car's screen.
4. **Open HeadQLink** with the phone unlocked. It starts connecting by itself; if it was already open and disconnected,
   tap "Connect". With Android Auto 17.4 or later you'll briefly see "Starting Auto…".
5. The "Car" row changes to "Found, connecting…" and then to "Connected". **Android Auto appears on the car's screen.**
6. **Lock the phone** and leave it somewhere cool. The picture keeps reaching the car with the screen off.

> [!CAUTION]
> While the hotspot is on, **the car may use your mobile data.**

**With "Wi-Fi Direct":** leave the hotspot off and Wi-Fi on, open the mirroring app in the car and then HeadQLink. The
"Network" row goes from "Wi-Fi Direct: looking for the car" to "Wi-Fi Direct: in the car's group (…)".

> [!IMPORTANT]
> If QDLink is installed on the phone, **force stop it before connecting**. If it's open, it takes the port HeadQLink
> needs and the car can't find HeadQLink.

### USB cable connection

> **Tested on the C10 (2026-10-06): 5 minutes at 40 fps with no drops**, and the phone charges. If the car doesn't answer within a few seconds, **unplug and plug the cable back in**: the car only talks during the first seconds after plugging in.

Like the car's original app, HeadQLink can carry the picture **over a cable** instead of Wi-Fi: the car switches the
phone to "accessory mode" and the usual session goes over the cable. The C10 does it (the original app says only some
models do), and after many trips it's the recommended connection for "Auto extended".

1. Use a **data cable** (not a charge-only one) and plug it into the car's **USB data port** (the one for music or
   Android Auto/CarPlay, not a charge-only port).
2. Open the mirroring app on the car's screen.
3. If the car switches the phone to accessory mode, Android opens HeadQLink by itself. If **QDLink** is also installed,
   Android may ask **which app opens "QDriveLink"**: choose **HeadQLink** and **"Always"** (or uninstall QDLink).
4. The "Network" row says "USB cable: Neusoft QDriveLink 1" and the car connects as usual.

- **No hotspot needed.** With the cable plugged in, **the cable takes priority**: even if another connection is chosen,
  HeadQLink pauses Wi-Fi and goes back to it when you unplug the cable. To use only the cable, choose "USB cable" with
  "Change".
- Unplugging the cable is like the car going away (video kept alive for 30 s, then Android Auto paused); plugging it
  back in resumes instantly.

**If it doesn't work**, export the log ([section 8](#8-exporting-the-log-to-ask-for-help)) right after plugging in the
cable and send it to us, with the time. We're looking for the **`HQL/USB`** lines: what Android says about the cable
(`USB_STATE`), whether the car switched the phone to accessory mode and under what name (manufacturer, model and
version). That tells us whether the C10 supports it.

### What you see and how to use it

- **"Auto":** Android Auto in full screen.
- **"Auto extended":** a panel on the left, on the driver's side, with "Auto", "Car" (Route, Driving, Trips, Gauges,
  Efficiency and Status tabs), "Photos", "Videos", "Web", "TV", "Radio", "Games" and "Settings". With Android Auto on screen, the
  panel hides by itself after a few seconds; tap the edge on the panel's side to bring it back. Photos, videos, web, TV and games are
  **only for when the car is stopped**.
- **Split screen ("Auto extended"):** in "Car", "Web", "Videos" and "TV", the panel's "Split screen" button puts
  Android Auto on the left, next to the panel, and that screen on the right. With "Car" you get the gauges (consumption,
  battery, range, next stop) next to Android Auto, and it is fine while driving, with no notice. Android Auto keeps a bit more than half, just enough for
  Google Maps to pan and search. Each side takes its own touches. "Full screen" shows it alone again. It lasts while you
  stay connected to the car. The first time in each connection, a notice must be accepted: we recommend using it with
  the car stopped, and you use it at your own risk.
- **"Car" section** (estimated from the phone's sensors and open services; with the Leapmotor account, also with real
  car data: see [Real car data](#real-car-data-leapmotor-account)):
  - "Route": destination, arrival, expected energy and consumption; the elevation profile coloured by slope (descents
    in green, where energy is recovered), the wind along the way, the chargers and the expected battery; the battery on
    arrival in a ring (set your % with "−5"/"+5"), the weather at the destination and the chargers along the route ("Go"
    opens Google Maps navigation).
    - **Search destination:** it searches while you type (when you pause, from 3 letters), with Android's address search
      (Google's) and Photon (OpenStreetMap) at once: addresses with a number and places ("petrol station",
      "supermarket"), each with how many km away it is. Every key lights up when pressed and what you type is also shown
      above the keyboard. With the box empty you get your **recent destinations**. "Speak" searches what you say (the
      phone's speech recognition; it needs the microphone permission).
    - **Where the forecast comes from:** under the chips, how much the remaining km climb and descend and what those
      hills cost ("Ahead: up 90 m, down 20 m: +0.5 kWh"). With the Leapmotor account, the dashed line over the bars is
      **your car's average** (from its history: see [Real car data](#real-car-data-leapmotor-account)) and the forecast
      is fitted to your real consumption; on arrival it shows "expected … · real …".
    - **Filter chargers:** "Filter" picks the minimum power (any, 22, 50, 100 or 150 kW) and the networks (Tesla, Zunder,
      Ionity, Iberdrola, Endesa X, Repsol, Wenea…, those on the route, with how many chargers each has). It is saved,
      and the bolts on the profile follow it too.
    - **Where the chargers come from:** in Spain, from the **official registry** of charging points (Ministry for the
      Ecological Transition, published by the DGT at nap.dgt.es): the power and **voltage** of each one. It is
      downloaded on the phone once a week (about 3 MB). What the registry lacks (some operators have not registered
      all their points) is filled in from OpenStreetMap, without repeating the ones already there; outside Spain,
      everything comes from OpenStreetMap, where power or network may be missing. **"Chargers along the route"** opens
      the list with each network's badge, the km, the power, the voltage ("350 kW · 920 V") and the sockets.
    - **Chargers near you:** "Near me" (next to "Filter") finds the chargers 5, 10, 25 or 50 km from where you are,
      without a route, nearest first: pick the network ("the Wenea ones near me") and the minimum power, and see how far
      they are, their power, voltage, sockets and points, with "Go".
    - **Navigate with Google Maps or Waze:** in "Filter" → "Navigate with" (if Waze is installed). The guide button
      and the "Go" buttons open the chosen one. Waze has no intermediate stops: "Go with the stops" takes you to the
      next one.
    - **Charging plan (free, like ABRP):** if you won't arrive with the margin, Route says **where to stop and how far
      to charge** among the filtered chargers: the best fast charger in the last stretch you can reach and only what is
      needed (the C10 charges more slowly above 50 %: two short stops beat one long one to 100 %). Each stop shows the
      km, what you arrive with, how far to charge and the minutes; the battery line jumps up at each stop. "Go with the
      stops" opens Google Maps with them (up to 3). In "Filter" you choose what to arrive with (10-30 %) and the most to
      charge to (70-100 %); if a little more than the maximum gets you to the destination, it charges that instead of
      adding a stop a few km from the end. There is no plan on a REEV: the generator covers the rest.
    - **800 V (81.9 kWh C10):** the big battery is 800 V and charges on 400–500 V chargers, but at half power. The plan
      stops at 800 V ones (or 150 kW and up, which almost always are, even if the registry does not say so) and keeps
      the 400–500 V ones in reserve: the list marks them in amber ("470 V: your 800 V car charges here at half power")
      and the plan says how many there are along the way. Tesla Superchargers in Spain are 400–500 V.
    - **Live charging:** with the Leapmotor account, when you plug in a block over the route shows the kW, the % and
      the target ("54 % → 64 %"), the time it will be ready and what you reach the next stop or the destination with;
      if charging a little more saves you the next stop, it says so ("Up to 85 % saves you the stop at Lleida: +7 min
      here, −23 min there"). The curve compares what the charger is giving (solid line) with what your car should take
      there (dashed), and it warns if charging is much slower than expected. At the target: **"You can go on"** with a
      sound on the phone and, if you are in the car, voice and a green card. On a trip or on a fast charger, the phone
      keeps watching **even if the car's screen turns off** (you go for a coffee): an ongoing notification with the %
      and the ready time, gone by itself when you unplug. Each charge is saved on the phone (real kW, % and minutes)
      to learn the car's real curve.
    - **The plan redoes itself during the trip:** with the Leapmotor account it compares how much your battery really
      drops with what was expected for those km and adjusts the rest ("Adjusted to this trip's consumption: +12 %").
      Chosen stops are kept while you reach them with margin; if you are running short (or have extra), the plan changes
      and **tells you**: an amber card on the car panel (also over Android Auto) with "Go" (Google Maps with the new
      stops) and "Route", the "Car" button in amber and **a voice alert** ("Charging plan changed. You're using 12 % more
      than expected. New stop: …"). Turn the voice off in "Filter" → "Voice alert if the plan changes". It does not alert
      while you are charging (that was your call).
  - "Driving": the next manoeuvre in large, with a bar that empties until the turn, the lanes and the manoeuvre after;
    the speed with the speed-limit sign, heading, altitude, slope and the sun until sunset.
  - "Trips": the current trip and the saved ones, with their route, consumption, climbing and cost; the km of the last
    14 days and the records. **Tap a trip** to open it in large: the route on a map (OpenStreetMap), the start, the end and
    **the stops** (2 minutes or more in the same place, with how long, the time and the km), and its figures. Stops are
    known for trips from 0.2.7 on.
  - "Gauges": speedometer with the limit and the maximum, G forces with the trail of the last seconds and the peaks,
    inclination and a smoothness score (0-100) from the jerks when accelerating, braking and turning.
  - "Efficiency": the power of the last 2 minutes (what is recovered in green), consumption now, for the trip and on
    average, where the energy goes, the trip cost, the CO₂ that did not come out of a tailpipe and a tip.
  - "Status" (with the Leapmotor account): the car's real battery, range, charging, tyre pressures, doors and odometer,
    with the age of the data; the pressures sit on **a 3D C10 you rotate with your finger** (open doors and boot show
    open, in amber). Without an account, it explains how to set one up.
  - **With the phone locked: "Location all the time".** Route, Driving, Trips, Gauges and Efficiency use the phone's
    GPS. If HeadQLink's location is only "Allow only while using the app", Android only gives it the GPS while
    HeadQLink is on screen or if the link went to the foreground with HeadQLink in front. When it starts without it
    (the Bluetooth automatic connection, the widget, the Android Auto start…), locking the phone keeps the car screen and
    the motion sensors going, but speed, route, trip and consumption stop until you unlock. HeadQLink gets it back by
    itself as soon as you see it with the phone unlocked, but so as not to depend on that the "Requirements check"
    recommends **"Location all the time"** in "Auto extended": "Open" › "Allow all the time" (or Settings › Apps ›
    HeadQLink › Permissions › Location). Not needed in "Auto". HeadQLink only uses the GPS with the link running or
    the "Car" screens open.
  - **No GPS, no frozen numbers.** With more than 5 s without positions, the speed shows "—" and the panels say "GPS
    paused · phone locked (allow location “all the time”)" (or "no GPS", for example in a tunnel). When it comes back,
    the stretch without positions is added to the trip in a straight line, at its average speed, instead of counting
    as if the car had been stopped.
- **Touchscreen:** works as in Android Auto, with **multi-touch** up to 3 fingers (for example, pinch to zoom the map).
- **Steering wheel buttons:** play/pause, next and previous work through the **car's Bluetooth**, with no extra pairing.
- **Sound:** music and directions come out of the phone over the **car's Bluetooth**, so the phone must be connected to
  it as usual. Calls go through the car's hands-free.

### Dropouts and automatic reconnection

- If the link to the car drops for a moment, the "Car" row says "Reconnecting… (Android Auto on hold)" and the picture
  comes back by itself, usually in under a second, **without restarting Android Auto** (with the QDAuto engine, the
  default).
- If the car is **gone for longer** (for example because you switch it off for a short stop), after 30 seconds
  HeadQLink stops sending the picture and **pauses Android Auto**, but **keeps listening for the car** for "Wait for the
  car" (**5 minutes** by default; change it in "Picture settings" › "Advanced"). The notification says "Waiting for the
  car · Android Auto paused". If the car comes back within that time, the picture returns **instantly**, without
  unlocking the phone or tapping anything.
- Once "Wait for the car" runs out, HeadQLink closes everything: Android Auto, its server and the connection. To use it
  again, tap "Connect", or let the Bluetooth automatic connection handle it. With the manual server start it closes
  everything the same way except the server, which stays on for the next trip ([Without
  accessibility](#without-accessibility-manual-start-of-the-android-auto-server)).
- If you tap "Connect" and no car shows up within **5 minutes** (or "Wait for the car", if longer), HeadQLink stops by
  itself. As long as the car keeps announcing itself, even if it doesn't connect, the wait starts over.

### The video adapts to the link

The radio between the phone and the car doesn't always carry the 5 Mbit/s the C10 asks for: with the car far from the
phone, on 2.4 GHz or with interference, the picture stuttered and lagged. Now, with the "Car", "Automatic", "Medium"
and "Very low" profiles (the ones that re-encode on the phone):

- HeadQLink measures the link ten times a second (data waiting to be sent and round-trip time) and, if it really
  clogs (half a second in a row with data piling up or a soaring round-trip time), **lowers the bitrate** (×0.75 each
  step, never below half of what the car asks for: 2.5 Mbit/s on the C10); if it's still clogged, it drops to
  **24 fps**. Once the link has been clean for 3 seconds it comes back quickly (fps first, then +25 % bitrate every
  3 s): from the minimum to what the car asks for in about 12 s. Occasional Wi-Fi retransmissions don't count (they're
  normal).
- Whatever doesn't fit in the link is dropped on the phone before lag builds up (at most ~150 ms queued).
- If the car stops reading for a moment but keeps talking (heartbeats, touches), the session holds on for up to **20 s**
  before giving it up (it used to be 10 s), discarding the old video and sending a fresh picture as soon as it can.

In the log (section 8) it shows as `enlace: congestión (outq 96 KB 300 ms, retrans +21) → bitrate 3.6 Mbit/s`,
`enlace: enlace limpio 5 s → bitrate 4.1 Mbit/s`, and in each session summary `enlace: bitrate mín. 2.5 Mbit/s ·
congestiones 4`.

### At the end of the trip

- Switch the car off (HeadQLink closes by itself when "Wait for the car" runs out, 5 minutes by default) or tap
  "Disconnect" to close it now.
- If the phone was locked when it closed, you'll see the "Auto on hold until you unlock the phone (then it closes by
  itself)" notification. **Unlock the phone once** so it closes completely: you'll see "Closing Auto…" for a few
  seconds (section 9). If you get back in the car before unlocking it and use the Bluetooth automatic connection,
  Android Auto comes back instantly, without going through "Closing Auto…". With the manual start you don't need to:
  Android Auto closes right away and its server stays on.
- "Closing Auto…" and "Starting Auto…" last a few seconds. If something gets them stuck, they go away by themselves
  after 15 s; if the Android Auto server may have been left on, you'll see "The Android Auto server is still on · Tap to
  turn it off": tap it with the phone unlocked.
- Turn the hotspot off if you don't need it.

### Automatic connection: "Connect when the car's Bluetooth is detected"

With this switch, HeadQLink starts waiting for the car as soon as the phone connects to the car's Bluetooth, without
you opening the app.

1. Turn it on on the main screen.
2. In the "Automatic connection" prompt, type text contained in the **car's Bluetooth name** (the default is
   `Leapmotor_BT`; not case-sensitive) and tap "Save".
3. Grant the Bluetooth permission and remove the battery restrictions when asked. Otherwise Android won't let it start
   in the background.

After that:

- If Bluetooth goes away before it manages to connect to the car, HeadQLink stops. If it goes away after a session
  (you switch the car off), it keeps waiting for the car until "Wait for the car" runs out.
- If Android Auto was still paused (you're back before "Wait for the car" runs out, or it was left on hold with the
  phone locked), the picture comes back instantly, **without unlocking the phone**.
- If Android won't let it start in the background, you'll see the "Car detected · Tap to connect HeadQLink"
  notification. Tap it.
- If Android Auto has to start and the phone is locked, you'll see the **"Unlock the phone to start Android Auto"**
  notification. Unlock it (with the car stopped): HeadQLink starts it by itself, without opening the app (you'll briefly
  see "Starting Auto…").

### Widget and Quick Settings button

To connect without opening the app:

- **"HeadQLink" widget** (home screen): ⚙ menu › "Add widget to the home screen", or touch and hold an empty spot on the
  home screen › Widgets › HeadQLink. It takes 4x2 and can be shrunk down to 2x2.
  - The **big button** does the same as "Connect" / "Disconnect" in the app: if something required is missing, it opens
    the "Requirements check". Its colour shows the state: grey off, amber looking for or waiting for the car, green with
    the picture in the car (with the fps and Mbit/s, refreshed every 5 s) and red if there's a problem (for example,
    QDLink open).
  - At the bottom, the **connection**: "Hotspot", "Wi-Fi Direct" or "USB cable". Tap another one to change it; with
    HeadQLink running it switches straight away, without restarting Android Auto. If the car's cable is plugged in and
    in use, it keeps priority: the chosen connection is the one it goes back to when you unplug it.
  - At the top, the **mode** ("Auto" / "Extended"). With HeadQLink running, changing it reconnects the video and Android
    Auto, like saving another picture profile.
  - At 2x2 you get the button, the state and the connection icon: tap the icon to move to the next connection.
  - "HEADQLINK" opens the app without connecting. The widget only updates when something changes: no battery use in the
    background.
- **"HeadQLink" Quick Settings button** (Android 8+): on Android 13+, ⚙ menu › "Add button to Quick Settings"; otherwise
  open Quick Settings, tap the pencil (edit) and drag it in. Tap it to connect or disconnect (to connect with the phone
  locked, it asks you to unlock first); touch and hold it to open the app. Underneath it shows the state ("Looking for
  the car…", "30 fps · 4.8 Mbit/s"…).

---

## 5. Useful settings

Everything is in the ⚙ menu on the main screen: "Requirements check", "Picture settings", "TV list" and "Radio list"
(only in "Auto extended"), "Language", "Add widget to the home screen", "Add button to Quick Settings" (Android 13+) and
"Diagnostics".

### "Picture settings"

<!-- screenshot: Picture settings -->

**"Picture profile".** The app labels the best fit for your phone as "Recommended". On powerful phones it's "Car".

| Profile | What it does |
|---|---|
| "Automatic (…)" | Follows the recommended one for this phone. **The best choice if you're not sure.** |
| "Car" (recommended) | What the car asks for (on the C10, full resolution at 30 fps and about 5 Mbit/s), without pushing the phone. The least heat and battery. |
| "Very high" | Full resolution at 60 fps with the lowest latency. Uses more power. The C10 doesn't show more than 30 fps. |
| "High" | Full resolution at 60 fps without pushing the phone: less battery and heat, a little more delay. |
| "Medium" | 720p at a steady 45 fps, re-encoded on the phone. |
| "Basic" | 720p at 30 fps, not re-encoded: the lightest for the phone. In "Auto extended" it behaves like "Medium". |
| "Very low" | 720p at 20 fps and low bitrate: the minimum for battery and connection. |

If you're connected, saving another profile makes the car and Android Auto reconnect by themselves in a few seconds.

> [!TIP]
> If you picked "Very high" by hand in an earlier version, it stays that way. Switch to "Automatic" or "Car": that's
> what keeps the phone from overheating (section 6).

**"Smoothness".** Only matters with the "Car" and "Automatic" profiles:

- "30 fps · less heat (recommended)": what the car asks for (30 fps and about 5 Mbit/s on the C10). It heats the phone
  the least.
- "60 fps · maximum smoothness": 60 fps and 8 to 12 Mbit/s, like the original HeadQLink (the C10 accepts them). The
  picture is smoother, but the phone runs hotter: if it reaches the "moderate" thermal state, HeadQLink drops to 30 fps
  by itself (section 6). If you're connected, saving makes the car and Android Auto reconnect by themselves; otherwise
  it applies to the next session.

**Other "Picture settings" options:**

- "Hide the side panel after a few seconds (Auto extended)": on by default.
- "Keep the phone screen on (more heat and battery; otherwise it turns off as usual and projection continues)": off by
  default. **Leave it off** unless you need it.
- "Heat protection": what HeadQLink does when Android reports the phone is getting hot (section 6).
  - "Normal (recommended)": drops to 30 fps at "moderate" (only if the session runs at 60), to 24 fps at "severe" and
    to 20 fps at "critical", always with less bitrate.
  - "Gentle": only lowers the bitrate; the fps never go below 30 except at "critical" (20 fps). For those who prefer
    smoothness even if the phone runs hotter.
  - "Off": changes nothing, it's only logged.

  Applies at once, without reconnecting.
- "Advanced ▾" › "Protocol engine":
  - "QDAuto (recommended)": the default engine, with reconnection without restarting Android Auto and heat adaptation.
  - "Original headqlink": the original HeadQLink engine, as a **fallback** if QDAuto gives you trouble.

  The change "Applies on next connection": tap "Disconnect" and then "Connect".
- "Advanced ▾" › "Wait for the car": "1 min", "5 min (recommended)" or "15 min". How long HeadQLink keeps listening for
  the car, with Android Auto paused, after losing it (section 4). Longer = it comes back instantly after longer stops;
  shorter = the Android Auto server is switched off sooner. It applies from the next stop.
- "Advanced ▾" › "Android Auto server start": "Automatic (accessibility) · Recommended" or "Manual (no accessibility)"
  (section 3, [Without accessibility](#without-accessibility-manual-start-of-the-android-auto-server)). Applies
  immediately.
- The rest of "Advanced" (fps, kbps, width, height, H.264 profile, latency optimizations, throttle) is for testing.
  Leave it empty or as it is.

### Settings from the car ("Auto extended")

The "Settings" button on the car's panel lets you change the picture profile and "Smoothness" ("Apply · reconnects in
a few seconds"), auto-hide the panel and latency optimizations, the "Electricity price" (€/kWh, for the cost of trips;
0.20 by default), and see the connection and engine in use. Only use it with the car stopped.

"Side panel color": "Automatic" (dark at night and light by day, like the car's screen) or one of 16 fixed colors, which
stays the same day and night. It applies at once, and the panel's text turns light or dark so it stays readable.

Below, "Hue" and "Light / dark" give any other color, and "Transparency" blends it into the black of the screen. "Side
panel on the right" moves the panel to the other side, with Android Auto against the left edge. "Side panel buttons"
picks which ones show (Yes / No) and in what order (arrows); "Auto" and "Settings" stay fixed. With the panel shrunk to
icons, the time, the outside temperature and the car's battery (when known) show at the bottom. And on the phone's main
screen, when a new version is out a notice shows its news and "Download and install" (the app downloads the APK and opens Android's installer; the
first time it asks to allow HeadQLink to install unknown apps); the first time a freshly installed
version starts, its news is shown. In the ⚙ menu, "Check for new version" checks right away.

"Speed camera voice alerts" (on by default): when you approach a camera, "Speed camera in 700 meters, limit 80",
once per camera; and if at 400 meters or less you are over the limit, "You are doing 95, limit 80". Cameras come
from OpenStreetMap, so some may be missing.

### "Language"

"Language" (Android 13 or later): "System language", "Español", "English", "Português (Portugal)", "Português
(Brasil)", "Italiano", "Français", "Deutsch" or "Nederlands". The car interface switches on the next connection. On Android 12 or earlier, the app uses the system
language.

### "TV list" and "Radio list" ("Auto extended")

Paste the URL of an M3U list or tap "Choose file". Without a radio list, the car shows the popular stations.

### "Web bookmarks" ("Auto extended")

The shortcuts on the car's "Web" screen (YouTube, Google, Wikipedia and Twitch by default). Add your own with a
name and an address, remove them or reorder them with the arrows; "Defaults" puts back the original four.

### "Diagnostics"

- "Export log": section 8.
- "Sessions": the latest sessions with the car, one per line: when, how long, over which connection, the fps, the
  dropouts and how it ended. To see what went wrong without sending the log.
- "Test without Android Auto (pattern)": shows a test image in the car instead of Android Auto. It tells you whether
  the problem is the connection or Android Auto. **Turn it off afterwards.**
- "Test options (QDAuto)": for testing the engine. Normally leave them as they are. One of them is "Wait for the car to
  come back, in seconds (5-600; empty = 30)".
- "Extended mode preview": the car panel on the phone (landscape) with a demo drive; tap it as in the car. Nothing is
  saved, and it is not available while connected to the car.

### Real car data (Leapmotor account)

**What it is.** Optional. With your Leapmotor account, HeadQLink reads your car's status from the Leapmotor cloud
(battery and range, charging, tyre pressures, doors, boot and lock, temperatures and odometer) and uses it in "Auto
extended":

- **"Status"** (the sixth "Car" tab): the battery in a ring with the range and the kWh left; charging (AC or DC fast,
  with the power and the time left) or, unplugged, the power leaving or entering the battery; the battery temperature
  (with "Cold battery: slower fast charging and less regeneration" below 10 °C); the four pressures on **a 3D C10** you
  rotate with your finger, the low tyre in amber (below 2.1 bar, 0.3 bar or more below the others, or with the car's own
  warning); open doors and boot (open on the model), the lock, the odometer and how old the data is. The cloud does not
  report the windows. The 3D view only redraws while you move it (it doesn't heat the phone); without WebGL you get the
  car seen from above.
- **C10 REEV (range extender):** HeadQLink recognises it by itself (the car reports its tank) and switches the battery
  to 28.4 kWh. In "Status", the **tank** (%, exact litres, range on petrol and total range); in "Trips", the **litres**
  of each trip and L/100 km (with the generator off, "0 L: all electric"), and the cost including petrol; in "Route",
  the battery doesn't go below 20 % (where the generator steps in) and it says how many litres it will add to get there.
- **"Route"**: the current % is the real one ("−5"/"+5" disappear) and the battery on arrival comes from it with your
  variant's capacity. The **consumption forecast** is fitted to your car: it starts from the real consumption of your
  trips (its history) and is refined by the routes you finish (on arrival it compares the forecast with what the battery
  dropped). The hills part is physics and isn't touched: if what's left climbs, the forecast goes up even if your average
  is lower.
- **"Efficiency"**: the trip consumption is the real one (how much the battery dropped times its capacity, over the
  odometer km) once the battery has dropped 2 %; before that it says "too little used to measure yet". Next to the
  estimated power, the real one shows when the data is recent. The energy breakdown is still estimated.
- **"Trips"**: each trip's consumption is **the car's own** from its history (marked "CAR"; the same as the official
  Leapmotor app) or, if missing, the real one from the % drop (marked "REAL"); under "Records", the last 30 days' total
  according to the car.
- **Car history:** the cloud keeps your trips of the last weeks with the kWh (and on a REEV the litres) measured by the
  car itself, and its weekly average consumption. HeadQLink reads it every 30 minutes at most (3 and 12 minutes after a
  trip ends) and the weekly average every 12 hours.

Every figure says where it comes from: "real · 40 s ago" (the age of the data: the cloud is not real time and, with
the car off, gives the last it knew) or "estimated".

**How to set it up** (⚙ › "Car data (Leapmotor account)", Android 6 or later):

1. **Client certificate.** The same one the LMB10 app asks for; HeadQLink neither includes nor provides it. Tap "Import
   certificate…" and pick app.crt and app.key in the file picker: both at once (long-press to select two) or **one
   after the other** (it says which one is still missing; "Start over" forgets a half pick), a .pem with both blocks or
   a .p12/.pfx (it asks for the password if it has one).
2. **Account.** Your Leapmotor email and password, then "Sign in". The password is not stored.
3. **Car.** If the account has one, it is chosen; with several, pick yours.
4. **Battery.** The cloud does not report the variant: "C10 Life · 69.9 kWh", "C10 ProMax · 81.9 kWh", "C10 REEV ·
   28.4 kWh + petrol" (chosen by itself if the car has a tank) or "Other" (kWh by hand). It turns the % into kWh.
5. **"Read status now"** to check it: battery, range, charging, pressures and the age of the data.

With "Auto extended" running (or the "Extended mode preview"), HeadQLink reads the car every 2 minutes (90 s with the
"Car" section on screen, like LMB10); if the car hasn't uploaded anything new (parked or asleep), every 5 and then 15
minutes; after errors, at 2, 5 and 10 minutes; and at most 400 reads a day (history included), so as not to abuse an
unofficial API. It stops when you disconnect. "Use the real car
data" turns it off without deleting anything.

**Privacy and security.**

- Read-only, with one optional exception: **sentry mode** (on and off, in "Car data" → "5 · Sentry mode"), and
  only if you save your car PIN there (the one in the Leapmotor app; stored encrypted on the phone and never logged).
  Without a PIN, HeadQLink **never sends commands to the car**, and no other command exists (no lock, climate,
  charging). Every command is confirmed before it is sent; "Forget PIN" deletes it.
- Your data only goes **to Leapmotor's servers**. The car's location is neither read nor stored.
- The certificate, the session and the trip history are stored **encrypted** with an Android Keystore key, outside
  backups. The password
  is not stored: if the session expires, "Status" says so and you sign in again on the phone.
- The Leapmotor server uses a certificate from its own authority. HeadQLink checks that its key is the known one; if it
  ever changes, it does not connect until you accept it on the phone (only do so on a network you trust).
- In the log: the masked email (c\*\*\*@e\*\*\*.com) and one line per read (%, range, charging, kW, km and latency),
  with no tokens, VIN or location.
- "Sign out and delete data" deletes the certificate, the session and the account settings from this phone.

> [!WARNING]
> It uses an **unofficial Leapmotor API**: it may stop working at any time, and HeadQLink is not affiliated with
> Leapmotor. With the **C10 REEV**, the real consumption from the % drop is meaningless while the generator charges on
> the move: then the car's history and its petrol counter rule.

The protocol comes from **[LMB10](https://github.com/txurtxil/LPB10)** by **txurtxil** (GPL-3.0). The REEV tank signals
and the trip history come from **[leapmotor-mate](https://github.com/ProtossBlaster/leapmotor-mate)** (ProtossBlaster),
and the weekly consumption from **[leapmotor-api](https://github.com/markoceri/leapmotor-api)** (markoceri), both
AGPL-3.0.

---

## 6. Heat and battery

Projecting video over Wi-Fi with GPS on heats the phone. In the first long test, with the "Very high" profile at
60 fps, the S25 Ultra reached Android's "severe" thermal state within 15 minutes and later "critical". That's why this
version brings three changes:

1. **"Car" profile:** sends 30 fps and the bitrate the C10 asks for (about 5 Mbit/s). That's exactly what the car
   shows: more doesn't look better and runs hotter.
2. **Phone screen off:** the screen turns off as usual and projection continues. The "Keep the phone screen on" option
   is off by default.
3. **Automatic heat adaptation** (Android 10 or later and the QDAuto engine; it does nothing with "Basic" in "Auto"
   mode, because that profile doesn't re-encode):

| Android thermal state | "Heat protection" Normal (recommended) | "Gentle" |
|---|---|---|
| Normal or light | Nothing: the session's fps and bitrate. | Nothing. |
| Moderate | To **30 fps** if the session runs at 60 because of "Smoothness" (at 30, the same fps) and 80 % of the bitrate. | Only the bitrate, to 80 %. |
| Severe | Drops to **24 fps** and at most **3.5 Mbit/s**. | **30 fps** at most and 3.5 Mbit/s. |
| Critical or worse | Drops to **20 fps** and at most **3 Mbit/s**. | The same: 20 fps and 3 Mbit/s. |

It steps down straight away. It goes back to normal once the phone has been cooler for **30 seconds in a row**. It does
this without cutting the session or restarting Android Auto, and without any warning: you'll just notice a slightly less
smooth picture. It's recorded in the log. With "Heat protection" set to "Off" nothing changes (it's only logged). If the
link is also tight, the lower of the two caps (heat or link) applies.

**Tips**

- Use the "Automatic" or "Car" profile.
- **Lock the phone** as soon as Android Auto appears.
- Avoid **direct sunlight**: don't leave it on the dashboard or against the windscreen.
- A **ventilated or fan-cooled mount**, for example on the air-conditioning vent, is better than a closed tray. If the
  wireless charger makes it very hot, charge it by cable.
- While projecting, **don't record 4K video**, play games or run other heavy apps on the phone.
- Take off a thick case in summer.
- On long trips, keep the phone charging, by cable if possible.

---

## 7. Troubleshooting

Always start with the ⚙ menu › "Requirements check": every red row has its own button.

| Symptom | Likely cause | What to do |
|---|---|---|
| The car isn't found ("Searching…" all the time) | The car isn't on the phone's hotspot, the hotspot switched itself off, or the mirroring app isn't open in the car. With Wi-Fi Direct: the hotspot is on or Wi-Fi is off. | Check the "Network" row: it should say "Hotspot on (…)". Join the car to the hotspot (car Settings › Wi-Fi) and open the mirroring app. Turn off the hotspot's auto-off. After 5 minutes without the car announcing itself HeadQLink stops: tap "Connect" again. |
| "Port 18463 is busy (is QDLink open?)", or "Port 18463 · Busy" in the "Requirements check" | The QDLink app (or another one) is open on the phone and holds the port. | "Force stop" QDLink. HeadQLink retries every 5 s and the warning clears by itself. |
| Black screen in the car, or an error in the "Auto" row | Android Auto didn't start: the phone was locked ("Doesn't start: unlock the phone"), or developer mode or accessibility is missing. | Unlock the phone (with the "Unlock the phone to start Android Auto" notification, it starts by itself when you unlock). If it still fails, check the "Requirements check" and tap "Disconnect" and "Connect". To find out what's failing, turn on Diagnostics › "Test without Android Auto (pattern)": if you see the test image, the connection is fine and the problem is Android Auto (turn it off afterwards). If nothing works, try the "Original headqlink" engine. |
| Choppy or delayed picture | Hotspot on 2.4 GHz, hot phone, profile too high, or phone screen on (with the screen on, Android scans for Wi-Fi networks often). | Hotspot on 5 GHz, "Automatic" or "Car" profile, lock the phone and cool it down. If it continues, try "Medium". |
| Many radio dropouts (the picture stops for half a second again and again; in the log, «Corte … RADIO» and «enlace muy congestionado» lines) | The radio between the phone and the car can't carry more: hotspot on 2.4 GHz, the phone in a pocket, in a metal tray or far from the screen. HeadQLink already lowers the quality as far as it can (down to 1.2 Mbit/s and 20 fps), but with the radio saturated that isn't enough. | Put the hotspot on **5 GHz** (Settings › Hotspot › Band). Keep the phone out of pockets and metal trays and close to the car's screen. The **USB cable** is the most stable. In the log, the «radio de la zona Wi-Fi» line gives the band and channel when Android lets the app read them. |
| The picture is less smooth than with the original HeadQLink | The original sent 60 fps; HeadQLink sends 30 (what the car asks for) so the phone doesn't overheat. Or the phone is already hot and the heat adaptation has lowered the fps, or the link is tight and the bitrate or fps were lowered. | "Picture settings" › "Smoothness" › "60 fps · maximum smoothness" (with the "Car" or "Automatic" profile). If heat lowers the fps, "Heat protection" › "Gentle" (section 6). In the log, the "HQL/Térmico" lines ("estado térmico 2" or higher) and the "enlace:" lines tell you which of the two it is. |
| The picture freezes for about 10 s and then reconnects | In earlier versions, a full frame larger than ~512 KB hung the car's receiver, which stopped reading until the connection dropped. **Fixed**: HeadQLink no longer sends any that large and keeps them at about 300 KB at most. | Update HeadQLink. If it happens with the "Basic" profile (there Android Auto decides the size), use "Automatic" or "Car". If it continues, export the log (section 8). |
| It disconnects often | Hotspot auto-off, battery saving, QDLink open, or the car's mirroring app closed. | Short dropouts reconnect by themselves ("Reconnecting…"). If they're long or frequent: "No battery restrictions", "Samsung: never sleeping apps", turn off the hotspot's auto-off and close QDLink. If it continues, export the log (section 8). |
| "Start (or restart) the Android Auto server" notification, or the "Auto" row says "Waiting for the Android Auto server" | Manual start, and Android Auto doesn't answer: its server is off (after restarting the phone or updating Android Auto) or blocked: a connection was cut halfway (something connected and left without a word, or a session dropped abruptly). | Tap the notification or the row: Android Auto › ⋮ › "Stop head unit server" (if shown) and ⋮ › "Start head unit server". HeadQLink retries every 5 s and carries on by itself. |
| "HeadQLink can't find the server button" notification | Android Auto has updated and changed its ⋮ menu (the automation looks for it by its text). | "Open AA" and start it by hand, or "Manual start". See [Android Auto versions](#android-auto-versions) and export the log (section 8). |
| "Android Auto connects and drops after a few seconds" notification | A known symptom of some new Android Auto versions (17.8). | Clear Android Auto's cache (Settings › Apps › Android Auto › Storage › Clear cache), stop and start its server, or go back to a tested version ([Android Auto versions](#android-auto-versions)). |
| Accessibility turns itself off | Android turns it off when the app is updated, or if the app crashed. Some manufacturers do too. | "Requirements check" › "Turn on". If it says "On but not running", turn it off and on. If it says "Restricted setting", use "Allow restricted settings" (section 3). Remove the battery restrictions. |
| Android Auto asks you to check the phone | It's the first time Android Auto sees this "car screen", or it needs you to grant a permission or confirm something. | Park, unlock the phone and accept what Android Auto asks. It usually happens only once. |
| The phone gets very hot | "Very high" or "High" profile, direct sun, screen on, wireless charging. | Section 6. |
| "Android Auto server open · Unlock the phone to close it" notification | The session ended with the phone locked and the Android Auto server is still open. | Unlock the phone: HeadQLink closes it. |
| After a stop it takes a long time to come back, or you have to close and reopen the app | The stop lasted longer than "Wait for the car" and HeadQLink closed everything; when you unlock, it first closes Android Auto ("Closing Auto…") and then it has to be started again. | Raise "Wait for the car" to "15 min" (Picture settings › Advanced) and turn on the Bluetooth automatic connection: when you're back, Android Auto returns instantly without unlocking. If you see "Unlock the phone to start Android Auto", unlock it and wait: it starts by itself, without opening the app. |
| "Closing Auto…" or "Starting Auto…" doesn't go away, or the "The Android Auto server is still on · Tap to turn it off" notification | The automation of the Android Auto settings didn't finish (for example, the phone locked halfway). | The overlay goes away by itself after 15 s. Tap the notification with the phone unlocked to switch the server off. If it keeps happening, export the log (section 8): the "ciclo:" lines record every step. |
| The car data freezes when you lock the phone (speed, route, trip, consumption; it comes back when you unlock) | HeadQLink's location is only "while using the app" and Android cuts its GPS with the phone locked. The car screen and the motion sensors keep going; the GPS doesn't. | "Requirements check" › "Location all the time" › "Open" › "Allow all the time". In the log, "GPS: ubicación todo el tiempo sí · con el móvil bloqueado llega" confirms it works. |
| The automatic connection doesn't start | The Bluetooth name doesn't match, or Android won't let it start in the background. | Check the text in the "Automatic connection" prompt, remove the battery restrictions, or tap the "Tap to connect HeadQLink" notification. |
| It won't install or update | Play Protect, Samsung Auto Blocker, or a version with a different signature. | Section 2. |

---

## 8. Exporting the log to ask for help

If something goes wrong, the log helps a lot to find the cause. **Export it right after the problem** and, if you can,
note the time it happened.

1. ⚙ menu › "Diagnostics".
2. Tap **"Export log"**. You'll see "Exporting the log…" and then "Saved to Downloads/HeadQLink/…".
3. The "Share HeadQLink log" sheet opens: send it by email, Telegram, Drive or any app you like, or just keep it.

<!-- screenshot: Diagnostics screen with "Export log" -->

**Where it goes:** `Downloads/HeadQLink/HeadQLink-log-YYYYMMDD-HHMMSS.zip`. On Android 9 or earlier it stays in the app's
folder (`Android/data/com.headqlink.app/files/exports/`) and the message shows the exact path.

**What's in the ZIP**

| File | Contents |
|---|---|
| `resumen.txt` | App version, phone model and Android version, your settings, connection and engine, current status, network interfaces, recent sessions and the list of included files (in Spanish). |
| `logs/` | The full log, with `sessions.csv` (one line per session with the car). |
| `car/`, `perf/`, `logcat/`, `crash/` | Car journal, performance, the app's system log and crashes (the most recent ones). |

**Log privacy**

- **It includes:** car identifiers (CarUUID, ProjectID), IP addresses, network names, the phone model and your
  settings.
- **It doesn't include:** your GPS trips (`trips/` is never exported), coordinates or destinations, or screenshots. Logs
  written by earlier versions could contain some location.
- **Nothing is sent automatically:** you choose who to share it with. Better privately, not posted in public.

You can ask for help on the project's GitHub page: [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink).

---

## 9. Security, privacy and legal notice

### Permissions and what they're for

| Permission | What for | When |
|---|---|---|
| Accessibility "HeadQLink touch" | Starting and stopping the Android Auto server by tapping through its developer menu, and receiving the car's touches. **It's limited to Android Auto:** it doesn't see other apps. | Android Auto 17.4 or later |
| Nearby Wi-Fi devices (or Location on Android 12 or earlier) | Joining the car's Wi-Fi Direct network. | Only with "Wi-Fi Direct" |
| Notifications | Seeing the connection status and warnings. | Always (recommended) |
| Bluetooth (nearby devices) | Recognising the car's Bluetooth. | Only with the automatic connection |
| No battery restrictions | Keeping it running with the screen off. | Recommended |
| Display over other apps | Opening Android Auto with the phone in the background. | Optional |
| Photos, videos and location | Gallery and driving panels. | Optional, "Auto extended" |
| Location all the time | Keeping the "Car" panels going with the phone locked. Only used with the link running or the "Car" screens open. | Recommended, "Auto extended" |
| Microphone | "Speak" in "Search destination": the phone's speech recognition understands it. | Optional, "Auto extended" |
| Internet | "Auto extended" services (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info), the destination search (Photon and Android's address search, Google's: they receive what you type), the trip map (OpenStreetMap tiles), your TV and radio lists and, if you set it up, the Leapmotor cloud (real car data, read-only). | Only those features |

- HeadQLink **sends no telemetry** and has no ads.
- It **doesn't share GPS** with Android Auto by default.
- Its internal services aren't open to other apps.
- The APK declares other permissions inherited from Open Headunit. HeadQLink doesn't ask for them during setup; the
  microphone is only used for "Speak" in the search (if it isn't allowed, the car says how to allow it).
- The search's **recent destinations** and the **trip routes** are only stored on the phone (the log carries neither
  destinations nor positions).

> [!IMPORTANT]
> **The Android Auto server.** With Android Auto 17.4 or later, HeadQLink switches on the "head unit server" in Android
> Auto's developer mode. While it's on, it listens on all of the phone's networks, so **HeadQLink switches it off when
> it finishes**: when you tap "Disconnect" or when "Wait for the car" runs out (at most 15 minutes without the car, with
> Android Auto paused and holding the server). With the phone locked it can't switch it off, and keeps it on hold until
> you unlock ("Auto on hold until you unlock the phone…"). **Unlock the phone after every trip.**
>
> With the manual start (no accessibility), HeadQLink can't switch it off: at the end it closes Android Auto, but the
> server stays on (that's what lets the next trip start without touching anything). **Stop it yourself** (Android Auto ›
> ⋮ › "Stop head unit server") if you're not going to use it, especially on a public Wi-Fi.

**When you're not using it:**

- Tap "Disconnect" if you opened HeadQLink outside the car: it starts connecting as soon as it opens.
- Turn the hotspot off.
- Turn off "Connect when the car's Bluetooth is detected" if you don't want it to start by itself.
- If you stop using it for a while, turn off the "HeadQLink touch" accessibility service and, if you like, leave Android
  Auto's developer mode.

### Legal notice

- **The software is provided "AS IS", WITHOUT WARRANTY of any kind.** It's experimental, relies on an undocumented
  protocol and may fail or stop working at any time.
- **The authors accept no liability** for damage of any kind: accidents, injuries, damage to the car, the phone or third
  parties, data loss, fines, loss of warranty, or breach of third-party terms. You use it **at your own risk**.
- **Don't use it while driving.** The driver alone is responsible for obeying traffic laws. Don't watch videos, TV, web
  pages or games while moving.
- HeadQLink is **not affiliated with, endorsed or sponsored by** Leapmotor, Google, Neusoft, Open Headunit or any other
  company or project. Leapmotor, C10, Android Auto and QDLink are trademarks of their respective owners.

### Licence and credits

- Licensed under the **[GNU AGPL-3.0](LICENSE)**. You're entitled to the source code: it's at
  [github.com/CharlysEV/headqlink](https://github.com/CharlysEV/headqlink). Sections 15 and 16 of the licence (no
  warranty and limitation of liability) also apply.
- Based on **[headqlink](https://github.com/ryazrm/headqlink)** by **ryazrm**, which in turn builds on
  **[Open Headunit](https://github.com/andreknieriem/open-headunit)** by **André Rinas (andreknieriem)**, and on the
  original work of **Michael Reid** ([copyright notice](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)). HeadQLink is an
  independent project with no relationship to Open Headunit or its authors.
- **QDAuto** protocol engine: [CharlysEV/qdauto](https://github.com/CharlysEV/qdauto).
- Real car data (Leapmotor account): read-only client ported from **[LMB10](https://github.com/txurtxil/LPB10)** by
  **txurtxil** (GPL-3.0); see [NOTICE](NOTICE).

# HeadQLink · User manual

**Languages:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · **English**

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
| "Auto" | Android Auto in full screen: maps, music and messages. |
| "Auto extended" (recommended by the app) | The same, plus its own panel on the left with more car and trip information (route, driving, trips, gauges, efficiency) and more features (photos, videos, web, TV, radio, games). |

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
| "Phone hotspot" | The car joins your phone's hotspot. | **Recommended**: it's the one tested on the C10, also with the screen off. |
| "Wi-Fi Direct" | The car creates the network and the phone joins it. The phone's hotspot must be off. | The original HeadQLink's connection. Not yet tested in the car with the QDAuto engine. |

> [!TIP]
> The app selects "Phone hotspot" by default, with the "Recommended" tag. If you prefer "Wi-Fi Direct", tap it before
> tapping "Continue".

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
| "HeadQLink accessibility" (Required with Android Auto 17.4 or later) | HeadQLink uses it to start Android Auto without you seeing it and to receive the car's touches. In Android Settings it's called **"HeadQLink touch"**. | "Turn on" and switch on "HeadQLink touch". See the note below. |
| "Android Auto developer mode" (Required with 17.4 or later) | Android Auto only accepts a "car screen" inside the phone through its developer mode. You turn it on once. | "Open AA" and follow the guide below. "Check" verifies it (accessibility must be on). |
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

### Step by step with "Phone hotspot" (recommended)

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

### What you see and how to use it

- **"Auto":** Android Auto in full screen.
- **"Auto extended":** a panel on the left, on the driver's side, with "Auto", "Car" (Route, Driving, Trips, Gauges and
  Efficiency tabs), "Photos", "Videos", "Web", "TV", "Radio", "Games" and "Settings". With Android Auto on screen, the
  panel hides by itself after a few seconds; tap the left edge to bring it back. Photos, videos, web, TV and games are
  **only for when the car is stopped**.
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
  again, tap "Connect", or let the Bluetooth automatic connection handle it.
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
  Android Auto comes back instantly, without going through "Closing Auto…".
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

---

## 5. Useful settings

Everything is in the ⚙ menu on the main screen: "Requirements check", "Picture settings", "TV list" and "Radio list"
(only in "Auto extended"), "Language" and "Diagnostics".

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
- The rest of "Advanced" (fps, kbps, width, height, H.264 profile, latency optimizations, throttle) is for testing.
  Leave it empty or as it is.

### Settings from the car ("Auto extended")

The "Settings" button on the car's panel lets you change the picture profile and "Smoothness" ("Apply · reconnects in
a few seconds"), auto-hide the panel and latency optimizations, and see the connection and engine in use. Only use it
with the car stopped.

### "Language"

"Language" (Android 13 or later): "System language", "Español", "English", "Português (Portugal)" or "Português
(Brasil)". The car interface switches on the next connection. On Android 12 or earlier, the app uses the system
language.

### "TV list" and "Radio list" ("Auto extended")

Paste the URL of an M3U list or tap "Choose file". Without a radio list, the car shows the popular stations.

### "Diagnostics"

- "Export log": section 8.
- "Test without Android Auto (pattern)": shows a test image in the car instead of Android Auto. It tells you whether
  the problem is the connection or Android Auto. **Turn it off afterwards.**
- "Test options (QDAuto)": for testing the engine. Normally leave them as they are. One of them is "Wait for the car to
  come back, in seconds (5-600; empty = 30)".

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
| The picture is less smooth than with the original HeadQLink | The original sent 60 fps; HeadQLink sends 30 (what the car asks for) so the phone doesn't overheat. Or the phone is already hot and the heat adaptation has lowered the fps, or the link is tight and the bitrate or fps were lowered. | "Picture settings" › "Smoothness" › "60 fps · maximum smoothness" (with the "Car" or "Automatic" profile). If heat lowers the fps, "Heat protection" › "Gentle" (section 6). In the log, the "HQL/Térmico" lines ("estado térmico 2" or higher) and the "enlace:" lines tell you which of the two it is. |
| The picture freezes for about 10 s and then reconnects | In earlier versions, a full frame larger than ~512 KB hung the car's receiver, which stopped reading until the connection dropped. **Fixed**: HeadQLink no longer sends any that large and keeps them at about 300 KB at most. | Update HeadQLink. If it happens with the "Basic" profile (there Android Auto decides the size), use "Automatic" or "Car". If it continues, export the log (section 8). |
| It disconnects often | Hotspot auto-off, battery saving, QDLink open, or the car's mirroring app closed. | Short dropouts reconnect by themselves ("Reconnecting…"). If they're long or frequent: "No battery restrictions", "Samsung: never sleeping apps", turn off the hotspot's auto-off and close QDLink. If it continues, export the log (section 8). |
| Accessibility turns itself off | Android turns it off when the app is updated, or if the app crashed. Some manufacturers do too. | "Requirements check" › "Turn on". If it says "On but not running", turn it off and on. If it says "Restricted setting", use "Allow restricted settings" (section 3). Remove the battery restrictions. |
| Android Auto asks you to check the phone | It's the first time Android Auto sees this "car screen", or it needs you to grant a permission or confirm something. | Park, unlock the phone and accept what Android Auto asks. It usually happens only once. |
| The phone gets very hot | "Very high" or "High" profile, direct sun, screen on, wireless charging. | Section 6. |
| "Android Auto server open · Unlock the phone to close it" notification | The session ended with the phone locked and the Android Auto server is still open. | Unlock the phone: HeadQLink closes it. |
| After a stop it takes a long time to come back, or you have to close and reopen the app | The stop lasted longer than "Wait for the car" and HeadQLink closed everything; when you unlock, it first closes Android Auto ("Closing Auto…") and then it has to be started again. | Raise "Wait for the car" to "15 min" (Picture settings › Advanced) and turn on the Bluetooth automatic connection: when you're back, Android Auto returns instantly without unlocking. If you see "Unlock the phone to start Android Auto", unlock it and wait: it starts by itself, without opening the app. |
| "Closing Auto…" or "Starting Auto…" doesn't go away, or the "The Android Auto server is still on · Tap to turn it off" notification | The automation of the Android Auto settings didn't finish (for example, the phone locked halfway). | The overlay goes away by itself after 15 s. Tap the notification with the phone unlocked to switch the server off. If it keeps happening, export the log (section 8): the "ciclo:" lines record every step. |
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
| Internet | "Auto extended" services (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info) and your TV and radio lists. | Only those features |

- HeadQLink **sends no telemetry** and has no ads.
- It **doesn't share GPS** with Android Auto by default.
- Its internal services aren't open to other apps.
- The APK declares other permissions inherited from Open Headunit, such as the microphone. HeadQLink doesn't ask for
  them during setup.

> [!IMPORTANT]
> **The Android Auto server.** With Android Auto 17.4 or later, HeadQLink switches on the "head unit server" in Android
> Auto's developer mode. While it's on, it listens on all of the phone's networks, so **HeadQLink switches it off when
> it finishes**: when you tap "Disconnect" or when "Wait for the car" runs out (at most 15 minutes without the car, with
> Android Auto paused and holding the server). With the phone locked it can't switch it off, and keeps it on hold until
> you unlock ("Auto on hold until you unlock the phone…"). **Unlock the phone after every trip.**

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

# Changelog

This project uses [semantic versioning](https://semver.org/).

## 1.7.0 — the phone as the TV's camera

- **Phone camera for video calls.** A new **Phone camera** screen in the phone app makes the phone the camera and/or
  microphone of a TV app that supports it (a TV web browser in a Google Meet, Zoom, Jitsi or FaceTime call, for
  example). It opens by itself when the call starts, asks once per TV app, and sends the camera and sound straight to
  that app over the home Wi-Fi with WebRTC. Front / back camera, phone-side mute, and Dim for long calls.
- Only the app in front on the TV can ask; a session belongs to the app and phone that started it, and ends when
  either goes away. The suite only relays the set-up lines (checked before they are passed on).
- **For TV apps:** `PadService` takes new `CAM` / `CAM_LINE` messages. See [docs/phone-camera.md](docs/phone-camera.md).
- The TV setup screen shows how many phones are ready to be a camera. The phone app asks for camera and microphone
  permission only when it is first used as a camera.
- New `CamProto` / `CamRelay` with `CamTest`.

## 1.6.0 — phone trackpad

- **Phone trackpad.** A new Trackpad screen in the phone app drives the pointer of TV apps that support it (a TV web
  browser, for example). Pointer acceleration, tap to click, two-finger scroll with momentum, two-finger tap or hold
  for the menu, tap-then-slide to drag, and Back / Home / Menu / Play / Type buttons. It opens by itself when such an
  app comes to the front on the TV (a switch turns that off) and has a speed setting.
- Moves use the phone app's existing typing link and are sent the instant the finger moves; a backlog is merged into one
  move instead of queuing. The phone keeps Wi-Fi out of power saving while the trackpad is open.
- **For TV apps:** new exported `PadService` (Android `Messenger` messages) that hands the trackpad to the app in front.
  See [docs/trackpad.md](docs/trackpad.md).
- The TV setup screen shows the trackpad's state; the phone app's notification gets a **Trackpad** button.
- Swipes starting at the phone's screen edge stay on the trackpad instead of triggering Android's Back gesture.

## 1.5.0 — type from your phone

- **Type on the TV from your phone.** A new phone app pops up a typing bar when a text box opens on the TV, and every
  letter goes to the TV as you type it. Your phone's own keyboard does the typing, so swipe and voice typing,
  autocorrect and paste all work. You can also open the bar from a Quick Settings tile, the app's notification, or the
  new phone key on the TV keyboard.
- **The suite has its own TV keyboard**: a fast QWERTY layout for the remote with English and
  Spanish suggestions, accents on a long press, and remote shortcuts. **Use as TV keyboard** on the setup screen
  switches to it. The app keeps it selected after updates.
- **The phone app finds the TV by itself** (mDNS) when the TV's Wi-Fi address changes. The app's drawing screen opens
  the TV page already paired, with no QR code to scan.
- The Chrome page offers the app with an **Open the app** link (Android only, and you can dismiss it).
- Keyboard connections from the phone app don't count as connected phones, so they don't keep the TV awake.

## 1.4.1

- **One slow phone no longer freezes the others.** Each phone now has its own send queue. A phone that stops
  reading (left the Wi-Fi, screen locked mid-drawing) used to stall drawing for every phone; now only its own queue
  waits, and once it is far behind it is disconnected. It reconnects by itself and gets the full picture.
- A removed photo's memory is freed right away instead of at the next garbage collection.
- The phone page files are read from the app once instead of on every page load.

## 1.4.0 — first public release

- **Pairing code.** Only phones that know the TV's 6-character code can connect. The QR carries it; the setup
  screen shows it for typing; "New pairing code" disconnects every phone. Before pairing, a connection gets no data
  and may send only a few bytes; five wrong codes lock that address out for a minute.
- **Enable automatically.** Switches the accessibility service on from the TV itself through its own adb daemon
  (USB debugging on, one "Allow" on the TV). The app then switches itself back on after updates.
- **Privacy screen** before setup, explaining in plain words what the accessibility service is used for.
- New name (TV Overlay Suite), package `io.github.tvoverlaysuite` and signing key. Earlier private builds used a
  different package and key: uninstall them first.
- Generic installer script `tools/tvos-install.sh` (TV address as an argument).

## Before 1.4.0 (private builds)

- **1.3** — Photo picture-in-picture from the phone (under the ink, S/M/L, live moves).
- **1.2** — Night dimmer with warmth, send-to-screen messages and stickers, laser pointer (Motion and Trackpad),
  keep-awake while a phone is connected. 1.2.1: in Motion mode the finger only switches the laser on.
- **1.1 / 1.0** — The marker layer: draw from the phone over anything playing; remote button toggles it.

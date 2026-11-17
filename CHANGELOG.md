# Changelog

This project uses [semantic versioning](https://semver.org/).

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

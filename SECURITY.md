# Security

## Reporting a problem

Please **don't open a public issue** for security problems. Use GitHub's private reporting instead:
**Security** tab → **Report a vulnerability** on this repository. We'll reply there.

Useful details: TV model and Android version, app version, what an attacker could do, and steps to reproduce.

## What the app exposes

- An HTTP + WebSocket server on port **8765** on the TV's home-network address. The page itself carries no secrets;
  every WebSocket must first send the TV's 6-character pairing code. Before that, a connection receives no data and
  may send at most 256 bytes, and is closed after 3 seconds. Five wrong codes from one address lock it out for a
  minute (30 per minute in total from all addresses).
- Traffic is plain HTTP / WebSocket (no TLS). Anyone who can read your Wi-Fi traffic could read the drawings and
  the pairing code. Use it on networks you trust.
- "Enable automatically" connects to the TV's own adb daemon on `127.0.0.1:5555` with a key created on the TV and
  kept in the app's private storage. It runs `pm grant … WRITE_SECURE_SETTINGS` and edits only the
  accessibility-service settings. USB debugging can be switched off afterwards.

## Supported versions

Only the latest release gets fixes.

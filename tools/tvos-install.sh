#!/usr/bin/env bash
# TV Overlay Suite: install / update and switch the service on over adb (computer, or Termux on a phone).
#
# Usage:
#   bash tvos-install.sh TV_IP TV-Overlay-Suite-v1.4.0.apk   install or update, then switch the service on
#   bash tvos-install.sh TV_IP --enable                      only switch the service on
#   bash tvos-install.sh TV_IP --uninstall                   remove the app
# TV_IP is the TV's address on your network (TV: Settings > Network & Internet > your Wi-Fi). The TV must have
# Developer options > USB debugging switched on; the first time it asks "Allow USB debugging?".
#
# You usually don't need this: the app's own "Enable automatically" button does the same on the TV.
#
# Why the waiting and retrying: "adb install -r" drops the app from the enabled accessibility services, and the TV
# keeps processing the update for a few seconds after "Success". A too-early re-enable is dropped again while the
# setting still lists it. So this waits, re-adds the service (keeping every other enabled service in the list) and
# checks that the TV actually BOUND it, retrying up to 4 times.
set -u
PKG=io.github.tvoverlaysuite
SVC=$PKG/$PKG.MarkerService

if [ $# -lt 2 ]; then
  sed -n '3,8p' "$0" | sed 's/^# \{0,1\}//'; exit 1
fi
IP=$(echo "$1" | tr -d '[:space:]'); ACTION=$2
case "$IP" in *:*) TARGET=$IP ;; *) TARGET=$IP:5555 ;; esac
if [ "$ACTION" != "--enable" ] && [ "$ACTION" != "--uninstall" ] && [ ! -f "$ACTION" ]; then
  echo "File not found: $ACTION  (run this from the folder that holds the APK)"; exit 1
fi

adb connect "$TARGET" >/dev/null
if ! adb -s "$TARGET" get-state >/dev/null 2>&1; then
  adb kill-server >/dev/null 2>&1; sleep 1      # a stale adb server is a common cause; restart it once
  adb connect "$TARGET" >/dev/null; sleep 1
fi
if ! adb -s "$TARGET" get-state >/dev/null 2>&1; then
  echo "Cannot reach the TV at $TARGET. Check that the TV is on, the address is right, USB debugging is on,"
  echo "and that you answered Allow on the TV's \"Allow USB debugging?\" prompt."; exit 1
fi
A="adb -s $TARGET"

if [ "$ACTION" = "--uninstall" ]; then
  $A uninstall $PKG; exit $?
fi
if [ "$ACTION" != "--enable" ]; then
  $A install -r "$ACTION" || exit 1
  echo "Waiting for the TV to finish the update..."
  sleep 6
fi

# "Bound services:{...}" can span several lines when other services are bound too
bound() { $A shell dumpsys accessibility | sed -n '/Bound services/,/Enabled services/p' | grep -v "Enabled services" | grep -q "$PKG"; }

# Lets the app switch itself back on after future updates (same as its "Enable automatically" button).
$A shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1

for try in 1 2 3 4; do
  $A shell "S=$SVC; c=\$(settings get secure enabled_accessibility_services); [ \"\$c\" = \"null\" ] && c=\"\"; c=\$(echo \"\$c\" | sed -e \"s#\$S##g\" -e \"s#::#:#g\" -e \"s#^:##\" -e \"s#:\\\$##\"); settings put secure enabled_accessibility_services \"\$c\"; sleep 1; settings put secure enabled_accessibility_services \"\${c:+\$c:}\$S\"; settings put secure accessibility_enabled 1"
  sleep 3
  if bound; then
    echo "TV Overlay Suite service ON (running)"
    $A shell dumpsys package $PKG | grep -m1 versionName
    exit 0
  fi
  echo "Service not running yet (try $try of 4), retrying..."
  sleep 3
done

echo "Service NOT running. Try again:  bash tvos-install.sh $IP --enable"
$A shell settings get secure enabled_accessibility_services
exit 1

#!/bin/bash
# Runs the adb-shell version of the switch (ServiceList.shellToggle) against a fake `settings` command.
# Usage: bash test/servicelist_test.sh <classes-dir>
set -u
CP=$1; T=$(mktemp -d); export STORE=$T/store
cat > $T/settings <<'X'
#!/bin/bash
# settings get|put secure KEY [VALUE]
f="$STORE.$3"
if [ "$1" = get ]; then [ -f "$f" ] && cat "$f" || echo null; else printf '%s' "$4" > "$f"; fi
X
chmod +x $T/settings; export PATH=$T:$PATH
CMD=$(java -cp "$CP" io.github.tvoverlaysuite.ServiceListProbe shell 2>/dev/null)
ME=io.github.tvoverlaysuite/io.github.tvoverlaysuite.MarkerService
fail=0
run() { printf '%s' "$1" > $STORE.enabled_accessibility_services; [ "$1" = "<unset>" ] && rm -f $STORE.enabled_accessibility_services
  sh -c "${CMD//sleep 1/true}"; got=$(cat $STORE.enabled_accessibility_services)
  if [ "$got" = "$2" ]; then echo "PASS shell toggle: '$1' -> '$got'"; else echo "FAIL shell toggle: '$1' -> '$got' (want '$2')"; fail=1; fi
  [ "$(cat $STORE.accessibility_enabled)" = 1 ] || { echo "FAIL accessibility_enabled not 1"; fail=1; }; }
run "<unset>" "$ME"
run "a.b/a.b.X" "a.b/a.b.X:$ME"
run "a.b/a.b.X:$ME" "a.b/a.b.X:$ME"
run "$ME:a.b/a.b.X" "a.b/a.b.X:$ME"
rm -rf $T; exit $fail

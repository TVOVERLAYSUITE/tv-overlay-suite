#!/bin/bash
# Phone app: same no-Gradle pipeline as the TV app. Compiles the shared TypeProto.java from ../src.
# Output: build/TV-Overlay-Suite-Phone-v<versionName>.apk. Signing: same TVOS_KEYSTORE / TVOS_KS_PASS as the TV app.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ -x "$BT/aapt2" ] || { echo "Android build-tools 34.0.0 not found under $SDK (set ANDROID_SDK)"; exit 1; }
rm -rf build && mkdir -p build/classes build/dex build/gen
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
    --min-sdk-version 26 --target-sdk-version 34 --java build/gen
# d8 8.2.2 crashes on anonymous inner classes compiled by javac 21 --release 8: use lambdas / named classes.
javac --release 8 -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java') ../src/io/github/tvoverlaysuite/TypeProto.java
"$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
OUT=build/TV-Overlay-Suite-Phone-v$VER.apk
if [ -n "${TVOS_KEYSTORE:-}" ]; then
  : "${TVOS_KS_PASS:?set TVOS_KS_PASS}"
  "$BT/apksigner" sign --ks "$TVOS_KEYSTORE" --ks-key-alias "${TVOS_KEY_ALIAS:-tvos}" \
      --ks-pass env:TVOS_KS_PASS --key-pass env:TVOS_KS_PASS --out "$OUT" build/aligned.apk
else
  echo "TVOS_KEYSTORE not set: signing with a throwaway debug key (test builds only)"
  keytool -genkeypair -keystore build/debug.keystore -storepass debugpass -keypass debugpass -alias debug \
      -keyalg RSA -keysize 2048 -validity 365 -dname "CN=TV Overlay Suite debug" >/dev/null 2>&1
  OUT=build/TV-Overlay-Suite-Phone-v$VER-debug.apk
  "$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:debugpass --out "$OUT" build/aligned.apk
fi
"$BT/apksigner" verify "$OUT" && echo "BUILT $OUT"

#!/usr/bin/env bash
# Builds SolarWhale.apk without Gradle. Needs: Android SDK (platform-34,
# build-tools 34), JDK 17, aapt2/d8/zipalign/apksigner on PATH or via SDK path.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JDK/bin:$PATH"

mkdir -p build/gen build/obj build/dex
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I "$PLAT" --manifest AndroidManifest.xml \
  -R build/res.zip --java build/gen --auto-add-overlay
javac --release 11 -cp "$PLAT" -d build/obj \
  build/gen/de/solarwhale/app/R.java app/src/de/solarwhale/app/MainActivity.java
d8 --release --lib "$PLAT" --output build/dex $(find build/obj -name "*.class")
aapt2 link -o build/unsigned.apk -I "$PLAT" --manifest AndroidManifest.xml \
  -R build/res.zip --auto-add-overlay
(cd build && zip -qj unsigned.apk dex/classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f keystore.jks ] || keytool -genkeypair -keystore keystore.jks \
  -alias solarwhale -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass solarwhale -keypass solarwhale -dname "CN=SolarWhale, O=SolarWhale"
apksigner sign --ks keystore.jks --ks-pass pass:solarwhale \
  --key-pass pass:solarwhale --out build/SolarWhale.apk build/aligned.apk
echo "build/SolarWhale.apk ready"

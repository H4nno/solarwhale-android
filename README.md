# SolarWhale Android app

Native Android dashboard for a [SolarWhale](https://github.com/H4nno/solarwhale)
server. No WebView, no framework: one activity, Canvas rendering, HTTP polling
every 12 s. Same dark glass design language as the web UI.

## Install

1. Build: `./build_apk.sh` → `build/SolarWhale.apk` (needs Android SDK 34 +
   JDK 17; the script bootstraps a local keystore `keystore.jks` on first run —
   **keep it**, it is your update signature)
2. Serve `SolarWhale.apk` from your SolarWhale box and open the URL on the
   phone (Android blocks side-loads from other origins), or `adb install`.
3. On first start: tap the URL line and enter your SolarWhale address
   (e.g. `http://192.168.1.20:8080`). Persisted locally.

## What it shows

Battery SOC (with charging/discharging color), PV generation, grid
(+/-), house load, miner state, 5-day sun forecast strip, mining yield today.
Polls `/api/status` from the SolarWhale web app — the same API the web
dashboard uses, so every server feature works without app changes.

## Files

- `AndroidManifest.xml`, `res/` — theme, colors, adaptive launcher icon
- `app/src/de/solarwhale/app/MainActivity.java` — everything (poller + view)
- `build_apk.sh` — reproducible no-Gradle build (aapt2 → javac → d8 → sign)
- `keystore.jks` — created on first build, gitignored (signing key!)

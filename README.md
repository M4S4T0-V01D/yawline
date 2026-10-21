# YAWLINE

Rally telemetry, data logger and digital co-driver for Android, written in Kotlin and Jetpack Compose.

- **Dash**: live engine data over OBD-II. RPM bar with a shift light, speed, and 12 tiles you can configure (coolant, boost, AFR/lambda, timing, IAT, battery and anything else your ECU reports), with warnings when coolant runs hot or voltage drops low.
- **Drift**: live slip angle (heading from the gyro vs. GPS direction of travel), a G-G plot, yaw rate, peak hold, and the line you've driven.
- **Logs**: 10 Hz recording of position, speed, slip, G and engine data. Replay shows the route coloured by speed, drift, lateral G, throttle or RPM, plus a scrubber, charts and a corner-by-corner breakdown (entry/min/exit speed, radius, max slip, grade). Export as GPX or CSV.
- **Tracks**: lines to drive, shown on an OpenStreetMap map.
  - Record a track with GPS, draw one by tapping points or freehand, import a GPX file, or save one from a log.
  - Edit, flip, rename, and export any saved track as GPX.
  - Navigate a track forwards or reversed. The app guides you to the line and calls out each turn by voice ("right in 200", "hairpin left now"), warns when you go off route, and tells you when you arrive.
- **Stages**: pace notes read aloud like a WRC co-driver.
  - Import by pasting or typing, from a text/CSV file, from text shared by Google Lens, or record them on a **recce** with big L/R + grade buttons or voice. Recce notes are pinned to GPS.
  - You can also generate a first draft of notes from a recorded run.
  - Race mode calls notes ahead by speed, with BACK / REPEAT / SYNC / SKIP / ±50 m.
- **Garage**:
  - Connect over Bluetooth Classic, BLE or USB, or start the built-in simulator.
  - Read and clear trouble codes (with descriptions), read the VIN, and see every supported sensor.
  - Choose the GPS source (phone, RaceBox 25 Hz, demo), calibrate the phone mount, and pick from 8 themes (Carbon, Night Stage red-only, Gravel, Tarmac Neon, Rally Blue, Ice, Colour-safe, Sun Glare for daylight).

## Hardware

| | |
|---|---|
| **Best** | OBDLink MX+ (BT Classic) or OBDLink CX (BLE). STN chip, 30–60 values/s. |
| **Value** | vLinker MC+ / FS. |
| **Wired** | OBDLink EX / SX or any FTDI, CP210x or CDC USB cable via USB-C OTG. CH340 is not supported. |
| **Avoid** | Cheap "ELM327 v1.5/v2.1" clones: slow, they drop out, no multi-PID. |
| **Drift accuracy** | RaceBox Mini / Mini S (25 Hz GNSS over BLE). |
| **Mount** | Rigid and screwed down. Any wobble reads as slide. |

Only standard Mode 01 PIDs are read. Maker-specific values (oil temp on many cars, wheel speeds, steering angle) need per-car Mode 22 PIDs.

## Build

```
./build.sh assembleDebug        # app/build/outputs/apk/debug/
./build.sh testDebugUnitTest
./build.sh assembleRelease      # signed if keystore.properties exists, else debug-signed
```

`keystore.properties` (git-ignored):

```
storeFile=yawline-release.jks
storePassword=…
keyAlias=yawline
keyPassword=…
```

Requires JDK 17 and the Android SDK (compileSdk 36). minSdk 29 (Android 10).

## Releases and updates

Bump `versionCode` and `versionName` in `version.properties` and push to `main`. `.github/workflows/release.yml` builds a signed `YAWLINE.apk` and publishes it as a GitHub release with a `latest.json` (version, SHA-256, size, notes). The app checks for new releases at startup (at most every 6 h) and from Garage › Updates, verifies the checksum, and installs through Android's package installer. CI signs with the repository secrets `KEYSTORE_B64` and `KEYSTORE_PASSWORD`, which must hold the same key as `yawline-release.jks`.

## Layout

```
obd/       ELM327/STN protocol, transports (BT, BLE, USB, sim), PIDs, DTCs
sensors/   GPS + IMU fusion, drift estimator, RaceBox parser, simulator
session/   recorder, CSV samples, corner analysis, GPX/CSV export
notes/     pace-note parser and speech, stages, co-driver, recce
track/     saved tracks, GPS track recorder, GPX import, turn finder, navigator
ui/        Compose screens, gauges, themes, OSM map
```

# Car Home

A lightweight launcher for Android car head units (built for a 1024×600, 2 GB, Android 9/10
"YT9216CJ" unit in a Maruti Suzuki SX4). Plain Java, no libraries, no image assets — about 35 KB,
~35 MB of RAM.

## Download

| | APK | |
|---|---|---|
| **Car Home** (this launcher) | [⬇ CarHome.apk](https://github.com/pratham46/car-home/releases/latest/download/CarHome.apk) | 36 KB |
| **CarPlay** — DiPlay 0.2.22 | [⬇ DiPlay-0.2.22.apk](https://github.com/pratham46/car-home/releases/latest/download/DiPlay-0.2.22.apk) | 18 MB |
| **Android Auto** — Open Headunit 4.0.0 | [⬇ OpenHeadUnit-4.0.0.apk](https://github.com/pratham46/car-home/releases/latest/download/OpenHeadUnit-4.0.0.apk) | 20 MB |

All files and notes: [Releases](https://github.com/pratham46/car-home/releases). DiPlay and Open Headunit
are other authors' apps, unmodified — see [THIRD_PARTY.md](THIRD_PARTY.md).

## Features

- **Speed gauge** from GPS, with heading and a trip meter (long-press to reset)
- **Now playing** card for any media app: album art, title, seek bar, controls
- **Volume** and **brightness** sliders, **day / night** theme (Auto follows the headlights via screen dimming), smooth cross-fade between themes
- **Camera** button that finds the unit's reverse / AV-in screen
- **Dock** of 8 app tiles + All apps; DiPlay (CarPlay) and Android Auto pre-placed
- Neumorphic look with hand-drawn icons, light on a weak CPU

## Install on the head unit

Laptop on the car's hotspot, network ADB on in the unit's Developer options:

```powershell
# put the APKs from the Release into bundle\, then
.\bundle\car-setup.ps1 -Unit <head-unit-ip>
```

It installs every APK in `bundle\`, grants all permissions (location, notification access for the
music card, modify system settings, overlay) and sets Car Home as the home screen.

## Build

Needs JDK and the Android SDK (`platforms;android-29`, `build-tools;36.0.0`) — no Gradle:

```powershell
.\build.ps1          # -> CarHome.apk
```

The first build creates a local `debug.keystore` for signing (not committed).

## Tools

- `tools/lights-probe.ps1` — snapshots the unit before/after switching the headlights on and shows what changed (to find the headlight signal)

## Third-party apps

The Release also carries two apps by other authors, unmodified — see [THIRD_PARTY.md](THIRD_PARTY.md).

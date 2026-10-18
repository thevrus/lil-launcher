# Lil Launcher

A tiny, tvOS-inspired home screen for Android TV, built for the NVIDIA Shield. Plain Views, one dependency (RecyclerView), about 600 KB.

![Home](docs/home.jpg)

## What it does

- **Bing photo of the day** as the backdrop, desaturated and dimmed per photo, changing on the hour.
- **Apps by real use.** Ranked by foreground time over 30 days. Hold Select on a tile to move or hide it.
- **Quiet header.** Time, date and current weather (Open-Meteo, no key). It fades away as you scroll.
- **Network at a glance.** Focus the globe for connection type, VPN exit country and a quick speed test with the video quality it can carry.
- **Screensaver.** The same photos full screen, with a slow drift, the time and the place name.
- **Sleep** from the gear menu (hold Select on the gear).

| Network | Screensaver |
| --- | --- |
| ![Network](docs/network.jpg) | ![Screensaver](docs/screensaver.jpg) |

## Install

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity dev.vr.tvlaunch/.MainActivity
```

One-time grants over adb, for ranking, the screensaver and sleep:

```sh
adb shell appops set dev.vr.tvlaunch GET_USAGE_STATS allow
adb shell settings put secure screensaver_components dev.vr.tvlaunch/dev.vr.tvlaunch.Screensaver
adb shell pm grant dev.vr.tvlaunch android.permission.WRITE_SECURE_SETTINGS
```

Re-run `set-home-activity` after every reinstall; Android resets the Home choice on updates.

Inter is bundled under the SIL Open Font License; see `OFL-Inter.txt`. Photos are from Bing and stay with their owners.

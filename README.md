# Seamfinder

Seamfinder is an Android quick-launch overlay. A small search bubble rests on
the screen edge. Tap it to find and open any installed app without leaving
what you are doing.

It is not a home-screen launcher. The bubble stays over other apps, opens a
search panel, and gets out of the way after you launch something.

**License:** Apache License 2.0 — see [LICENSE](LICENSE).

**Privacy:** [PRIVACY.md](PRIVACY.md), the in-app About screen, and
[baba-ali-graph.github.io/seamfinder](https://baba-ali-graph.github.io/seamfinder/).

## Requirements

- Android 12 or newer (API 31)
- Overlay permission (draw over other apps)
- Notifications permission on Android 13+, so the foreground service can stay alive
- Usage Access is optional. It only sorts the empty search by recently used apps

The app has no network access, ads, or analytics. It does not use an
accessibility service, and it does not request `QUERY_ALL_PACKAGES`.

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds run R8. Add a signing config before uploading to Play. F-Droid
builds from this repository and signs the result itself.

Package id: `com.baba.seamfinder`

## Store listing

English title, descriptions, changelog, and graphics live in
[`fastlane/metadata/android/en-US/`](fastlane/metadata/android/en-US/).

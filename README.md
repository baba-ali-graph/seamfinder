# EdgeDock

EdgeDock is an Android quick-launch overlay: a small search bubble docked to
the screen edge. Tap it to search and launch any installed app without opening
the home screen or app drawer.

**License:** Apache License 2.0 — see [LICENSE](LICENSE).

## Requirements

- Android 12+ (API 31)
- Overlay permission (draw over other apps)
- Optional: Usage Access (for recently-used apps)
- Notifications permission on Android 13+ (foreground service)

## Build

```bash
./gradlew assembleDebug
# or
./gradlew assembleRelease
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Release builds enable R8. Configure a signing key in `app/build.gradle.kts`
(or via `signingConfigs` + `secrets.properties`) before publishing.

## Privacy

EdgeDock has no network access, ads, or analytics. See [PRIVACY.md](PRIVACY.md)
and the in-app **About → Privacy policy** screen.

## Store notes

Before Play / F-Droid submission you still need:

- A hosted privacy-policy URL (Play Console Data safety)
- Declarations for overlay + special-use foreground service
- Screenshots / feature graphic / listing copy
- F-Droid metadata (when submitting to fdroiddata)

Package visibility uses a targeted `<queries>` intent for launcher apps —
`QUERY_ALL_PACKAGES` is **not** used.

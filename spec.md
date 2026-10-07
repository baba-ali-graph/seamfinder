# EdgeDock — Product Spec

## What it is
EdgeDock is an Android system overlay: a small, semi-transparent search **bubble** docked to the screen edge. Dragging it moves it anywhere; releasing snaps it to the nearest edge (left or right) with a springy bounce. Tapping it expands a centered, elevated **search panel** — an auto-focused text field over a list of apps — for quickly finding and launching any installed app without going through the home screen or app drawer.

*Pivot note (2026-09-16): EdgeDock originally shipped as an edge-swipe **task switcher** (a panel of recently-used apps for switching between them). It's now a **quick app finder/launcher** instead — search-and-launch any installed app, not just switch between recent ones. Most of the underlying overlay/permission plumbing carried over; the interaction model, UI, and data layer were rebuilt. See "Cut from the original PRD" below for what was deliberately left out of this version.*

## Target platform
- **minSdk 31** (Android 12), **targetSdk 36** (Android 16), **compileSdk 37** (required by current AndroidX Compose/AboutLibraries releases; compiling against a newer SDK than targetSdk is normal and doesn't change runtime behavior).
- Primary dev/test device: Redmi Note 15, Android 16, HyperOS (MIUI-derived — see OEM caveats below). Also tested on an Android 16 (API 36) emulator for faster iteration.
- Distribution: designed to be Play-Store-viable, but sideload (direct APK / ADB install) is the fallback if Play policy review proves too restrictive for the permission set below.

## v1 feature scope
- **Bubble** (collapsed state): small circular, semi-transparent, glowing overlay pinned to a screen edge. Free-drag anywhere on screen; on release, springs to whichever edge (left/right) is nearer, vertical position stays wherever released. Tap expands the search panel.
- **Search panel** (expanded state): a scrim behind a centered, elevated card. Auto-focused search field with real IME (the overlay window becomes focusable only while expanded, reverting afterward). Empty query shows the 5 most recently-used apps (from Usage Access history); typing filters the full installed-app list in real time by name.
- **List item**: app icon + name + (if Usage Access is granted) a relative "last used" label (e.g. "2h ago"). Tap launches the app and collapses the panel back to the bubble. Tapping the scrim, or the system back gesture, also collapses without launching anything.
- **Settings screen** (normal Activity, not overlay): permission onboarding (overlay, Usage Access), start/stop toggle, **About** screen with app version and third-party OSS license list.

## Non-goals / platform limitations (by design, not oversight)
- **No live per-app status** (e.g. "Chrome — active 2m ago" with a live badge, a Spotify-style inline play/pause control, or a Telegram-style notification-content preview). Getting genuinely live cross-app state like that requires `NotificationListenerService` — full read access to every notification's content across every app on the device, a heavy, privacy-invasive permission with real Play Store scrutiny, for a feature that isn't core to "quickly find and launch an app." Icon + name (+ a real last-used time computed from Usage Access data) covers the same ground honestly.
- **No true "running processes" list.** Android exposes no such API to third-party apps for security/privacy reasons; not needed anyway now — this is a launcher, not a switcher, so launch history (Usage Access) is what matters, not live process state.
- No cross-device sync, no cloud backend, no telemetry/analytics in v1.

## Cut from the original PRD (and why)
The user supplied a detailed PRD/mockup for this pivot. These items were deliberately excluded from v1 rather than built:
- **Inline media controls** (Spotify-style play/pause in the list) and **notification-content previews/badges** (Telegram-style "3 new · message preview") — both need `NotificationListenerService`, ruled out above.
- **"Default app" role badges** — technically obtainable for a handful of roles via `RoleManager`, but adds real scope for a minor visual flourish; not core to search-and-launch.
- **"Utilities" app-category tab** — `ApplicationInfo.category` exists but most apps don't declare one (defaults to undefined), so a category filter would be unreliable/sparse in practice.
- **"In-App Queries" (web/files/settings search from within the search box)** — that's a much larger Spotlight-style universal-search feature, out of scope for a quick app launcher.
- **Pin/favorites and the "Pinned" filter chip** — the PRD mockup shows a chip but doesn't specify pin behavior in detail; cut to keep this pass shippable, straightforward to add later (a small DataStore-backed set of pinned package names).
- **True background blur** (`RenderEffect`) behind the search panel — approximated with a gradient/shadow glow for now; real blur is a later polish option.

Bubble position is now persisted across service restarts via DataStore.

## Why these permissions
| Permission | Why | Notes |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the bubble + search panel over other apps | User must grant via a system settings screen |
| Usage Access (`PACKAGE_USAGE_STATS`) | Recency ordering for the default "recent apps" view | Special permission, granted via `Settings.ACTION_USAGE_ACCESS_SETTINGS` |
| `QUERY_ALL_PACKAGES` | ~~List/resolve every installed launchable app~~ **Removed** — replaced with a targeted `<queries>` MAIN/LAUNCHER intent | Preferable for Play policy |
| `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_SPECIAL_USE`) | Keep the overlay alive reliably | Shows a persistent low-priority notification (Android requirement, cannot be hidden) |
| `POST_NOTIFICATIONS` | Required (API 33+) to actually show that foreground-service notification | Requested at runtime before starting the service; denial blocks start |
| `RECEIVE_BOOT_COMPLETED` | Restart the service after device reboot | Implemented via `BootReceiver` when dock was left enabled |

Notably **no Accessibility Service** in this version — the previous task-switcher needed live foreground-app awareness; a launcher only needs launch history, so that whole sensitive-permission category was dropped in the pivot.

## OEM caveats (HyperOS/MIUI)
HyperOS aggressively kills background services and can silently revoke Usage-Access grants for apps it considers battery-restricted. The app detects revoked permissions on resume rather than silently breaking, and shows a battery-restriction nudge (opens app details settings) while the dock is running.

## Play Store considerations
One sensitive-permission declaration form remains for overlay / special-use FGS (Accessibility and `QUERY_ALL_PACKAGES` are not used). Still needs a **hosted** privacy policy URL (in-app text is in About → Privacy policy; mirror it from `PRIVACY.md`), Data safety answers, and a demo video showing the stated purpose. Comparable launcher/quick-search apps exist on Play today.

# EdgeDock — QA Checklist

Manual, on-device/emulator checklist. Run after each change to the overlay; run the full sheet before any Play submission. The Redmi Note 15 (HyperOS) is the reference real device; an Android 16 (API 36) emulator is used for faster day-to-day iteration.

## Build & install sanity
- [x] `./gradlew assembleDebug` succeeds.
- [x] `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeds.
- [ ] App icon + name appear correctly in the launcher.

## Permissions
- [x] Overlay-permission button correctly deep-links to system Settings and reflects granted state on return.
- [ ] Usage-access button correctly deep-links to system Settings and reflects granted state on return.
- [ ] POST_NOTIFICATIONS prompt appears on first "Start dock" tap (API 33+) and the foreground-service notification shows afterward.

## Bubble (collapsed state)
- [ ] Bubble renders pinned to a screen edge over the home screen and over third-party apps.
- [ ] Bubble is draggable anywhere on screen, tracking the finger 1:1.
- [ ] On release, bubble animates (springy bounce, not an instant snap) to whichever edge — left or right — is nearer, and stays at the released vertical position.
- [ ] Tapping the bubble (without dragging) expands the search panel; a real drag does not also trigger a tap-expand.

## Search panel (expanded state)
- [ ] Panel expands centered on screen over a dimmed scrim, with a visible open animation.
- [ ] Search field is auto-focused and the real keyboard appears without the user needing to tap it manually.
- [ ] Empty query shows real recently-used apps (verify by actually switching between a few real apps first, then opening EdgeDock).
- [ ] Typing filters the full installed-app list in real time, case-insensitive substring match.
- [ ] Tapping a result launches that app and the panel collapses back to the bubble.
- [ ] Tapping the scrim (outside the card) collapses the panel without launching anything.
- [ ] EdgeDock itself never appears in either the recent or search-results list.
- [ ] After collapsing, the bubble reappears at its last snapped edge position, not a default position.

## Performance & stability
- [ ] No visible frame drops opening/closing the panel or during bubble drag.
- [ ] `adb shell dumpsys gfxinfo com.baba.edgedock` shows no janky frames during a drag + expand/collapse cycle.
- [ ] Force-stopping and reopening the app restores bubble/service state correctly (no duplicate bubbles, no crash).
- [ ] `adb logcat` shows no crashes during a mixed-use session (drag, expand, search, launch, collapse, repeat).

## Regression pass (before any Play submission)
- [ ] Full permission-grant flow works from a completely fresh install.
- [ ] Privacy policy link is reachable from the About screen (in-app). Host a public URL from `PRIVACY.md` before Play submission.

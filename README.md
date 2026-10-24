# Ascend — climb out of the scroll

Personal Android app (Kotlin + Jetpack Compose) to cut phone addiction.
Fonts: **Montserrat** (headings) + **Poppins** (body), bundled (OFL). Colours sampled from the logo.

## Features

| # | Feature | Where |
|---|---|---|
| — | **Limits:** min 15 min. Saving locks the limit for **45 days**. You can make it stricter anytime, never looser. Unlocking checks network time, so changing the phone date doesn't help | Limits tab · `core/Policy.kt`, `core/TrustedClock.kt` |
| — | **24 h delay** on anything that loosens protection (gate off, feature off, unblock site, un-batch app), cancellable | Home → "Waiting 24 h" |
| 1 | Usage tracker: per-app time, unlocks, pickups/hr | Home · `data/UsageRepo.kt` |
| 2 | Intent gate: reason + 5/10/15 min session, kicked out when time's up | `ui/gate/GateActivity.kt` |
| 3 | Reels/Shorts blocker (IG, YT, FB), rules in JSON, remotely updatable | `core/ReelsRules.kt`, `assets/reels_rules.json` |
| 4 | Escalating friction: 5s → 10s → 30s → type a sentence → math problem | `core/Friction.kt` |
| 5 | Auto grayscale after X min/day + bedtime | Shield · `core/Grayscale.kt` |
| 6 | Local DNS firewall (VPN, on-device) with presets | Shield → Blocked sites |
| 7 | Notification batching with digests at set times, keyword pass-through | Shield → Digest |
| 8 | ~~Uninstall lock~~ — dropped. Instead: the 45-day lock, the 24 h delay and partner pings | — |
| 9 | Relapse predictor: on-device logistic regression, adds friction before risky moments | Insights · `core/Predictor.kt` |
| 10 | Accountability partner (Telegram bot) + Razorpay stakes jar | Shield → Partner |
| 11 | Minimal text launcher | Settings → Home app → "Ascend Home" |
| 12 | Home-screen widget | Long-press home → Widgets → Ascend |
| 13 | Live wallpaper: climber = streak / 45, sky goes cream → dusk → night with stars as you near/pass your goal. Redraws only when visible (+ every 5 min), no animation loop. Some phones apply it to the home screen only | Shield → "Set as wallpaper" · `wallpaper/AscendWallpaperService.kt` |
| 14 | Mood check-in: after ≥ 3 min in a distracting app (or a gate session ends), a 😊 😐 😞 notification. Max 4/day, never at bedtime | Insights → "How apps make you feel" · `core/Mood.kt` |
| 15 | Time-cost converter: "This month: 31 h on Instagram = 6 books or 1 full course" | Home · `core/TimeCost.kt` |
| 16 | Weekly report card, Sunday 7 PM: grade A+…F, trends, best/worst day, top apps, mood, time cost. Shareable 1080×1920 story image (private mode hides app names), save to gallery (Android 10+) | Insights → Weekly reports · `core/Report.kt`, `ui/screens/ReportScreen.kt` |

## Build

1. Open the folder in Android Studio (Ladybug or newer) → let Gradle sync.
2. Run on your phone (Android 9+).
3. The app opens Setup: grant Usage access, Accessibility, Notifications, Battery unrestricted.

**Optional — grayscale** (once, phone connected via USB debugging):
```
adb shell pm grant com.ayush.ascend android.permission.WRITE_SECURE_SETTINGS
```

**Optional — DNS firewall:** set Settings → Network → Private DNS to *Off* or *Automatic*.

## Reels rules drift

Instagram/YouTube rename view IDs after updates. If Reels/Shorts start showing again:
1. Open the Reels screen → `adb shell uiautomator dump && adb pull /sdcard/window_dump.xml`
2. Find the new `resource-id`, update `reels_rules.json` and bump the `version`.
3. Host the file (e.g. a raw GitHub URL), paste the URL in Shield → Rules JSON URL.

## Notes
- Builds with `./gradlew assembleDebug` (JDK 17). Logic tests: `./gradlew testDebugUnitTest`.
- DB is v2 (`MIGRATION_1_2` adds `mood_entry` + `weekly_report`; existing data kept).
- Stakes are an honour system (no payment verification without a backend); your partner gets notified.
- Everything is stored on-device (Room + DataStore). Network is used only for: time check, Telegram, rules URL, forwarded DNS.

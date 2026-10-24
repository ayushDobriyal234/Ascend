<div align="center">

<img alt="e04b5858-eb54-416a-9fcf-e1a143f95f4a" src="https://github.com/user-attachments/assets/2f320d98-53e3-4f7c-836d-5384b2eb7d9c" width="100px" alt="Ascend logo" />


# Ascend

**Climb out of the scroll.**

An Android app to beat phone addiction. It doesn't just block apps. It makes *mindless* use expensive and *intentional* use easy.

![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-28-0D4A33)
![Status](https://img.shields.io/badge/status-in%20development-F2AE24)

</div>

---

## Why

Screen-time apps are easy to switch off in a weak moment. Ascend is built for *future weak you*:

- **Limits lock for 45 days.** You can make them stricter anytime, never looser.
- **Turning any protection off waits 24 hours.** By then the urge has passed.
- **Changing the phone's date doesn't help.** Unlocks are checked against network time.

Every day you respect your limits, your climber gains **100 m**. 45 days = **4,500 m = the summit**.

## Screenshots
| <img width="200" alt="Screenshot 1" src="https://github.com/user-attachments/assets/08e9b704-ad34-4003-9da6-f94c8f322f43" /> | <img width="200" alt="Screenshot 2" src="https://github.com/user-attachments/assets/5602355c-e30b-4b48-b196-35ca8278efd9" /> | <img width="200" alt="Screenshot 3" src="https://github.com/user-attachments/assets/8a2a0809-085f-4ce6-b513-d435ba479799" /> | <img width="200" alt="Screenshot 4" src="https://github.com/user-attachments/assets/948d010c-92b0-478a-a4e2-89ff71463966" /> |
| <img width="200" alt="Screenshot 5" src="https://github.com/user-attachments/assets/cfaf3fba-27d5-4a28-9612-009b61fb74e5" /> | <img width="200" alt="Screenshot 6" src="https://github.com/user-attachments/assets/bc710f97-5783-4fa4-aff4-0f7397b9fdfd" /> | <img width="200" alt="Screenshot 7" src="https://github.com/user-attachments/assets/56b88250-eae5-47c1-b584-12163098c29f" /> | <img width="200" alt="Screenshot 8" src="https://github.com/user-attachments/assets/77563210-1aca-456a-ab76-2897e310b43f" /> |


## Features

| | Feature | What it does |
|---|---|---|
| ⏱️ | **Locked limits** | Daily limit per app (min 15 min), locked for 45 days |
| 📊 | **Usage tracker** | Per-app time, unlocks and pickups per hour, rebuilt from raw usage events |
| 🚪 | **Intent gate** | Asks *"why are you opening this?"* and grants a 5/10/15-min session, then sends you home |
| 🧗 | **Escalating friction** | Each reopen within an hour costs more: wait → longer wait → type a sentence → solve a math problem |
| 🎬 | **Reels & Shorts blocker** | Blocks only Reels/Shorts; DMs, search and normal videos keep working. Rules are remotely updatable JSON |
| ⚫ | **Auto grayscale** | Screen turns black & white after X minutes a day and at bedtime |
| 🛡️ | **DNS firewall** | Local VPN that blocks distracting domains in every app and browser, entirely on-device |
| 🔕 | **Notification batching** | Holds notifications from chosen apps and delivers them as digests at set times |
| 🔮 | **Relapse predictor** | On-device logistic regression learns when you usually slip and adds friction *before* it happens |
| 🤝 | **Accountability partner** | A friend gets a Telegram ping when you change a limit or pay to unlock |
| 🏠 | **Minimal launcher** | Text-only home screen; distracting apps stay hidden until you type their name |
| 📱 | **Widget** | Today's time, unlocks and altitude on your home screen |

## Tech stack

| Layer | Tools |
|---|---|
| UI | Jetpack Compose, Material 3, Glance (widget) |
| Language | Kotlin, Coroutines, Flow |
| Storage | Room, DataStore |
| Background | WorkManager, AccessibilityService, VpnService, NotificationListenerService |
| ML | Logistic regression trained on-device (no external ML library) |
| Fonts | Montserrat + Poppins (OFL) |

## Architecture

```
app/src/main/java/com/ayush/ascend/
├── core/       Policy (45-day lock, 24 h delay), TrustedClock, Friction, Predictor, ReelsRules, Grayscale, Partner
├── data/       Room database, DataStore prefs, UsageRepo
├── service/    GuardService (accessibility), DnsVpnService, BatchNotificationListener, overlay host
├── ui/         Compose screens, gate, launcher, theme
├── widget/     Glance widget
└── work/       Workers: maintenance, model training, notification digests
```

## Getting started

1. Clone the repo and open it in **Android Studio** (Ladybug or newer).
2. Run on a device with **Android 9+**.
3. Follow the in-app setup: Usage access, Accessibility, Notifications, Battery unrestricted.

Optional, for auto grayscale (one-time, via USB debugging):

```bash
adb shell pm grant com.ayush.ascend android.permission.WRITE_SECURE_SETTINGS
```

For the DNS firewall, set **Settings → Network → Private DNS** to *Off* or *Automatic*.

## Privacy

- Everything is stored **on your phone**: no account, no analytics, no servers.
- The accessibility service only reads which app is open and Reels/Shorts screen elements.
- Network is used only for: a date check, optional Telegram messages, optional rules URL, and forwarding normal DNS lookups.
- You can switch any permission off at any time in Android Settings.

## Roadmap

- [ ] Live wallpaper that climbs with your streak
- [ ] Mood check-ins: "how did that session make you feel?"
- [ ] Time-cost converter ("31 h of Reels = 6 books")
- [ ] Weekly report card with a shareable story image
- [ ] Earn bonus minutes through steps, focus sessions and coding problems
- [ ] Chrome extension that syncs limits to your laptop

## Contributing

Issues and PRs are welcome. If Reels/Shorts blocking breaks after an Instagram or YouTube update, update `app/src/main/assets/reels_rules.json` (find new IDs with `adb shell uiautomator dump`) and open a PR.

## Author

**Ayush Dobriyal** · [ayushdobriyal.com](https://www.ayushdobriyal.com) · [GitHub](https://github.com/ayushDobriyal234)

## License

Code: MIT, add a `LICENSE` file. Fonts: SIL Open Font License (`branding/OFL.txt`).

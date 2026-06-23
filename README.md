# SIRTverse Shot Timer (MVP)

The first shippable SIRTverse product: a phone-based **laser shot timer**.

> Open camera → random start delay → detect SIRT laser hit → record shot time → record splits → save session.

This repo is the **app shell**. It is built shell-first with a *mock* detector so the
full timer → shot → split → save → history loop is proven before camera complexity
enters. The real laser detector already exists and is battle-tested — see
[`prototypes/laser-detector-android/LaserDetector.kt`](prototypes/laser-detector-android/LaserDetector.kt)
(ported from the SIRTverse Python pipeline; HSV thresholding + MOG2 motion gate +
circularity/contrast hard-reject + shot accumulator with dedup & split timing).

## Status — Milestone 1: App Shell with Fake Detector ✅

- Home / Shot Timer / Session Results / History / Settings screens
- Random 3–5 s start delay + start beep
- "Simulate Laser Hit" button → shot timestamp logging + split calculation
- Local saved session history (JSON, zero extra deps)

## Stack

Native **Kotlin / Android** (View-based UI), reusing the exact toolchain that builds
`sirtverse-android`:

| | |
|---|---|
| AGP | 9.0.1 |
| Gradle | 9.1.0 |
| JDK | 17 (`/usr/local/opt/openjdk@17`) |
| compileSdk / minSdk / targetSdk | 36 / 29 / 36 |
| UI | Material3 Views + ViewBinding |
| Storage | `org.json` → `filesDir` (no Room) |
| Settings | SharedPreferences |

**Why native Kotlin and not React Native** — see [`docs/DECISIONS.md`](docs/DECISIONS.md).
Short version: the hard part (laser detection) is already solved in native Kotlin.
A cross-platform shell would mean re-bridging to it. We reuse instead of re-derive.

## Build & run

```bash
# from repo root, device plugged in with USB debugging on
./gradlew :app:installDebug
# or open the folder in Android Studio / VS Code and Run
```

`local.properties` pins `sdk.dir` — edit if your Android SDK lives elsewhere.

## Layout

```
app/src/main/java/com/sirtverse/shottimer/
  HomeActivity, ShotTimerActivity, SessionResultsActivity,
  HistoryActivity, SettingsActivity
  domain/shottimer/   ShotTypes, SplitCalculator, ShotTimerEngine, TimeFmt
  domain/detection/   LaserDetector (interface), MockLaserDetector
  storage/            SessionJson, SessionStorage, SettingsStore
docs/        PRODUCT_BRIEF, ROADMAP, ARCHITECTURE, AI_WORKFLOW, LASER_DETECTION_NOTES, DECISIONS
prompts/     claude/*, codex/*   — narrow, scoped agent tasks
prototypes/  laser-detector-android/  — the proven CV detector, wired in at M3
```

## Roadmap

[`docs/ROADMAP.md`](docs/ROADMAP.md) — Milestones 1–5 (MVP) then Phases 2–7
(better timer → training system → body tracking → AI coaching → community → hardware).

The first win: **a phone app that sees a laser hit and records a split.**

# Architecture

## Principle: shell-first, one seam for detection

The app is split so the *risky* part (laser detection) sits behind a single
interface and everything else is plain, testable Kotlin.

```
  UI (Activities)                Domain (pure Kotlin)            Storage
  ───────────────                ────────────────────            ───────
  HomeActivity                   ShotTimerEngine  ◄── state machine
  ShotTimerActivity ───hits───►  recordHit()                     SessionStorage
  SessionResultsActivity         Shot / ShotSession              (org.json files)
  HistoryActivity                SplitCalculator                 SessionJson
  SettingsActivity               TimeFmt                         SettingsStore
                                                                 (SharedPreferences)
        ▲
        │ onHit()
  ┌─────┴───────────────┐
  │ LaserDetector (iface)│
  │  ├ MockLaserDetector │  ← Milestone 1 (button)
  │  └ CameraLaserDetector│ ← Milestone 3 (wraps OpenCV detector)
  └──────────────────────┘
```

## The detection seam

`domain/detection/LaserDetector` is the contract:

```kotlin
interface LaserDetector {
    var onHit: (() -> Unit)?
    fun start()
    fun stop()
}
```

- **M1:** `MockLaserDetector` — `simulateHit()` from a button invokes `onHit`.
- **M3:** `CameraLaserDetector` wraps `prototypes/laser-detector-android/LaserDetector.kt`
  and calls `onHit()` whenever the ported shot accumulator registers a deduplicated
  green shot.

`ShotTimerActivity` never learns which implementation it's talking to. Swapping the
mock for the camera detector is the *only* change M3 makes to the timer path.

## The engine is framework-free

`ShotTimerEngine` owns session state (shots, splits, GO timestamp) but NOT wall-clock
scheduling. The UI drives the random start delay (`Handler.postDelayed`) and calls
`go()` at the beep. The engine takes an injectable `clock: () -> Long`, so timing
behavior is unit-testable with zero real time elapsed.

State machine: `IDLE → COUNTDOWN → RUNNING → ENDED`.

## Split convention (matches the detector)

- **First-shot time** = elapsed ms from GO to shot #1.
- **Split** = gap between consecutive shots; the first-shot time is not a split.

This is the same convention used by the ported detector's shot accumulator
(green-absent dedup → `split_ms = ts - last_shot_ts`), so M3 stays consistent.

## Storage

One JSON file per session in `filesDir/sessions/<id>.json` via built-in `org.json`.
No Room, no kotlinx.serialization, no annotation processors → no build-matrix risk.
Sessions are small and few; file-per-session is fast and trivially debuggable.
`SessionJson` is the single (de)serialisation path, reused to pass an unsaved
session between activities via an Intent string extra (no Parcelable boilerplate).

## Navigation

One Activity per screen, plain Intents. No nav library — fewer moving parts for an
MVP, and nothing here needs a back-stack graph yet.

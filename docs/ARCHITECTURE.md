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

- **M1:** `MockLaserDetector` — button directly invokes `onHit` (regression / bench testing).
- **M3:** `CameraLaserDetector` — YUV plane-math pipeline (brightness spike + green color gate +
  pulse state machine); calls `onHit()` on each deduplicated green shot. No OpenCV dependency.

`ShotTimerActivity` never learns which implementation it's talking to.

## The camera abstraction (M3, DECISIONS.md D-007)

All camera knobs live behind `camera/CameraController`:

```
ShotTimerActivity
  │  creates
  ├─► CameraXController  (implements CameraController)
  │     capabilities() — lens ID, exposure range, ISO range, fps
  │     setExposure(shutterNs, iso) / setAutoExposure()  ← Camera2Interop
  │     frameListener: (ImageProxy) → Unit               ← set by detector
  │     bind(lifecycle, previewSurface)
  │     unbind() / shutdown()
  │
  └─► CameraLaserDetector (implements LaserDetector)
        start()  → registers frameListener, applies exposure policy
        stop()   → unregisters frameListener, restores auto-exposure
```

**DUALLENS extensibility** (SPEC-2026-07-15-DUALLENS-001): a second `CameraController`
instance covers the second rear lens (different shutter speed) — the detector and the
timer shell never change. v1 binds ONE back lens; the interface shape makes the upgrade
slot-in without touching either seam.

```
camera/
  CameraCaps.kt         — data class: lens ID, exposure range, ISO range, fps
  CameraController.kt   — interface (the seam)
  CameraXController.kt  — CameraX + Camera2Interop implementation
```

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

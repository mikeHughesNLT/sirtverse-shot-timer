# Decisions (ADR-lite)

Short, dated records of choices that would otherwise get re-litigated.

## D-001 — Native Kotlin/Android, not React Native/Expo
**Date:** 2026-06-23 · **Status:** Accepted

The ChatGPT MVP brief proposed React Native/Expo with detection built from scratch.
But the hard part — laser detection — **already exists in native Kotlin**
(`sirtverse-android`: HSV + MOG2 + circularity/contrast gates + a shot accumulator
with dedup and split timing, ported from the SIRTverse Python pipeline).

Going RN would mean re-bridging to that native detector via a custom module from day
one — extra surface area, for the privilege of rewriting a UI shell we can build
quickly in native Views. We reuse instead of re-derive.

**Trade-off accepted:** Android-only for now. iOS is a later port (the engine and
detection seam are framework-agnostic, so the domain layer travels).

## D-002 — New repo `sirtverse-shot-timer`, not inside sirtverse-android
**Date:** 2026-06-23 · **Status:** Accepted

Clean product identity, no entanglement with the camera-probe/MJPEG experiments in
`sirtverse-android`. We copy the proven `LaserDetector.kt` into `prototypes/` and
wire it in at M3. Toolchain is mirrored exactly from sirtverse-android (AGP 9.0.1,
Gradle 9.1.0, JDK 17, compileSdk 36) so it builds on the same machine with no fuss.

## D-003 — View-based UI, not Jetpack Compose
**Date:** 2026-06-23 · **Status:** Accepted

Compose adds a Kotlin/compose-compiler/BOM version matrix. On a 2026 AGP 9 / Gradle 9
toolchain that's avoidable build risk for an MVP whose priority is "runs today."
Material3 Views + ViewBinding mirror the deps that already build in sirtverse-android.
Verified: `:app:assembleDebug` BUILD SUCCESSFUL on first try.

**Revisit when:** UI iteration speed becomes the bottleneck, or we add many screens.

## D-004 — JSON-file storage, not Room
**Date:** 2026-06-23 · **Status:** Accepted

Sessions are small and few. Built-in `org.json` → `filesDir` gives zero extra
dependencies, no annotation processor, and human-readable, adb-debuggable files.
Room buys querying/migrations we don't need yet.

**Revisit when:** history needs real querying/filtering or grows large.

## D-006 — CameraX (PreviewView + ImageAnalysis), not raw Camera2
**Date:** 2026-07-09 · **Status:** Accepted · **Brief:** CC-SIRT-TIMER-M2-CAMERA-001

The old `sirtverse-android` MJPEG pipeline used Camera2 with AE locked to darkness so
the laser was the only bright spot. That approach is clean-room-avoid (Product Strategy
call #7): it prevents seeing the shooter and can't support MediaPipe pose.

CameraX is the correct M2 foundation for three reasons:

1. **ImageAnalysis seam.** `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` gives M3's
   `CameraLaserDetector` a guaranteed frame stream without writing any Camera2
   `ImageReader` boilerplate. The frame tap in M2 proves this seam works at ≥15 fps
   on the X4000 before any detection logic exists.

2. **Exposure stays variable.** CameraX auto-exposure is the default; `Camera2Interop`
   is available for targeted M3 experiments without locking the architecture into
   darkness-mode. Exposure is something to tune per-scene, not to hardwire.

3. **Lifecycle management.** `bindToLifecycle` tears down the camera automatically on
   Activity destroy/pause — no `CameraDevice.StateCallback` boilerplate, no surface
   leak risk.

**Trade-off accepted:** Camera2Interop (for M3 exposure experiments) is accessed via
`androidx.camera.camera2.interop.Camera2Interop`, not via raw Camera2 APIs.

## D-007 — Exposure policy v1: auto-exposure (drill-window parachute available)
**Date:** 2026-07-18 · **Status:** Accepted · **Brief:** CC-SIRT-TIMER-M3-DETECT-001

Exposure is a variable to solve (Strategy call #7 / DETECTION_LAB.md doctrine). The
locked-dark approach from `sirtverse-android` (1 ms shutter, low ISO) prevents seeing
the shooter and kills the pose-tracking path — clean-room-avoid.

**v1 ships with auto-exposure.** `CameraLaserDetector.start()` does not pin the sensor.
The detection pipeline relies on brightness DELTA vs a rolling per-cell background, which
is invariant to the absolute exposure level.

**Drill-window parachute** (authorized by the brief): if the rig-referee night (B-4)
shows TPR < 0.95 and the failure mode is loss of contrast under auto-AE, apply pinned
exposure during the active session only:
- `CameraXController.setExposure(shutterNs=16_000_000, iso=800)` on `detector.start()`
- `CameraXController.setAutoExposure()` on `detector.stop()`

**RESOLVED 2026-07-20 (B-3/B-4 rig night, CC Fable autonomous): PARACHUTE PULLED.
v1 ships with drill-window pinned exposure 16ms/ISO800.** Commit `261e779`.

| Mode | Shutter | ISO | Noise band (score) | Real pulse (score) | Separation | Verdict |
|------|---------|-----|--------------------|--------------------|-----------|---------|
| Auto AE | auto | auto | 18.2–23.9 | 31–54 | ~1.3–2.2× | FAIL — referee TPR 0.885, phantom 100.4/10min |
| Pinned 16ms/ISO800 | 16 ms | 800 | 5.3–11.1 | 18–147 (typ 45–108) | ~4–13× | WINNER — 0.0 phantoms/10min over 35 laser-off min (excl. rig-head fixture artifact, see daybook) |

**Root cause of the auto-AE failure (measured, not speculated):** every laser pulse
triggers AE compensation; the 1–2 s AE recovery brightens the whole scene against the
just-adapted EMA background, producing scene-wide deltas scoring 24–41 that trail every
pulse by +1 to +2.7 s. This inflated the noise floor into the real-pulse score range and
generated ~1 phantom storm per pulse. Pinning at the trained capture-recipe floor
(16ms/ISO800 — same as the X4000 rig recipe) removed the oscillation source entirely:
noise band collapsed from 18–24 to 5–11 and SCORE_THRESHOLD dropped 24→16 (`893d98d`).

`stop()` restores auto-exposure — pinning is active only during the drill window, so
pose-tracking / normal viewing is unaffected. Full evidence chain:
`SIRTverse.wiki/Daybooks/2026-07-20-B4-referee-night-autonomous.md`.

## D-005 — Engine owns state, UI owns the clock
**Date:** 2026-06-23 · **Status:** Accepted

`ShotTimerEngine` is pure (injectable clock) for unit-testable split timing; the
Activity drives the random delay via `Handler`. Keeps timing logic out of the UI and
the UI's scheduler out of the engine.

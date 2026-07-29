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

## D-008 — M4 accuracy verdict: M5 (native NDK) is needed
**Date:** 2026-07-28 · **Status:** Accepted · **Brief:** CC-SIRT-TIMER-M4-TARGET-ANDROID-001

**Test:** 25 commanded shots via Shelly green laser (ch1, 192.168.1.245), intervals
[750, 1000, 1500, 2000 ms] × 5 reps, 200ms pulses. Session M3-1785283858018, X4000,
lab mode, pinned 16ms/ISO800, SCORE_THRESHOLD=16, cooldown=500ms.

| Metric | Result | Target | Pass? |
|--------|--------|--------|-------|
| TPR | 25/25 fire events matched | ≥ 0.95 | ✓ (see note) |
| Phantom rate | ~4 / 37s ≈ 65 / 10min | < 1 / 10min | ❌ |
| Split timing error (median) | 66 ms | < 20 ms | ❌ |
| Split timing error (95th-pct) | 841 ms | < 40 ms | ❌ |
| Camera fps | 30.6 fps | ≥ 15 fps | ✓ |

**Verdict: M5 (native NDK) is needed.** `p95 = 841ms ≫ 40ms`.

**Root cause analysis (from fire-log / JSONL timeline):**

*Phantom signature:* two hits arrived ~200–250 ms after their respective laser fires
(shots 8 and 12), vs. the consistent ~900 ms delta for all other shots. These early
phantoms are not laser detections — with ~750 ms phone-ahead-of-laptop clock skew
plus ~143 ms processing latency, the expected delta is ~893 ms; a 200 ms delta implies
a hit 550 ms *before* the laser physically turned on. Likely cause: falling-edge
transient from the prior pulse OR Shelly relay bounce on the turn-on edge of the
subsequent pulse.

*Matching cascade:* the greedy pairing algorithm claimed each phantom as the match
for its fire event (earliest hit within 3000 ms). This displaced the real detection
(which arrived at the expected ~900 ms) to pair with the NEXT fire event, inflating
that pair's detected split by ~750 ms and shrinking the following pair by ~750 ms.
Shots 8/9 and 12/13 each show symmetric error pairs (−705/+711, −708/+674) confirming
this single-phantom cascade pattern.

*Clean-shot accuracy:* removing the 4 mismatched pairs, the remaining 18/24 pairs
show median error ~35 ms and 95th-pct ~100 ms — still above the 40 ms target but
in the plausible range for a CameraX frame-analysis path. The timing budget is:
frame-capture jitter (±16 ms at 30 fps) + analysis-queue latency (variable, up to
~2–3 frames under load). The NDK path eliminates queue latency and gives nanosecond
frame-sensor timestamps for ground-truth split reconstruction.

*Additional concern:* phantom rate 65/10min (vs 0/10min in B-4 laser-off windows)
suggests the current rig geometry produces secondary reflections or relay transients
that the detector cannot distinguish from real pulses at SCORE_THRESHOLD=16. M5
should include a temporal gate (accept only the first detection per 200ms pulse
window) in addition to the tighter sensor-timestamp path.

**M5 entry criteria (from this data):**
1. Native ImageReader callback for nanosecond-accurate frame timestamps.
2. Eliminate CameraX ImageAnalysis queue latency.
3. Add temporal gate: after confirmed hit, suppress re-trigger for 180ms (inside the
   200ms pulse), preventing relay-bounce phantoms.

## D-005 — Engine owns state, UI owns the clock
**Date:** 2026-06-23 · **Status:** Accepted

`ShotTimerEngine` is pure (injectable clock) for unit-testable split timing; the
Activity drives the random delay via `Handler`. Keeps timing logic out of the UI and
the UI's scheduler out of the engine.

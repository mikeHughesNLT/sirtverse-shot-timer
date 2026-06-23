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

## D-005 — Engine owns state, UI owns the clock
**Date:** 2026-06-23 · **Status:** Accepted

`ShotTimerEngine` is pure (injectable clock) for unit-testable split timing; the
Activity drives the random delay via `Handler`. Keeps timing logic out of the UI and
the UI's scheduler out of the engine.

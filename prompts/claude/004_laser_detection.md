# Claude · 004 · Wire in Laser Detection (Milestone 3)

## Task
Replace the mock detector with the REAL one. Do not re-derive detection — wrap the
proven `prototypes/laser-detector-android/LaserDetector.kt`.

Follow `docs/LASER_DETECTION_NOTES.md` § "Wiring it in at Milestone 3":
1. Add `implementation 'org.opencv:opencv:4.10.0'`; `OpenCVLoader.initLocal()`.
2. Move the prototype detector into `domain/detection/cv/`, rename package.
3. Implement `CameraLaserDetector : LaserDetector` (CameraX frame feed +
   **exposure lock**: AE off, ~1 ms shutter, fixed ISO — mirror sirtverse-android
   `CameraService.kt`). Call `onHit()` on each non-null `FrameReport.shotNumber`.
4. Swap `MockLaserDetector` → `CameraLaserDetector` in `ShotTimerActivity`. Keep the
   Simulate Hit button behind a debug flag for fallback.
5. Map Settings (laser color, cooldown, sensitivity) onto detector params.

## In scope
- `app/build.gradle`, `domain/detection/**`, `ShotTimerActivity.kt`, manifest.

## Out of scope
- Don't change `ShotTimerEngine` or storage. The timer path must stay identical —
  only what's behind the `LaserDetector` interface changes.

## Done when
A real SIRT green pulse on a target registers a shot; times + splits come from the
camera; false positives are manageable. See `docs/ROADMAP.md` Milestone 3.

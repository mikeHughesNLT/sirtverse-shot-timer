# Codex · 004 · Detection Review (Milestone 3)

You are the REVIEWER for the camera-detection wiring. The detection algorithm itself
is proven (`prototypes/laser-detector-android/LaserDetector.kt`) — review the
INTEGRATION, not the CV math.

## Read
`docs/LASER_DETECTION_NOTES.md`, the new `CameraLaserDetector`, and the camera setup.

## Assess
- Exposure lock actually applied (AE off, fixed shutter/ISO)? Without it, contrast
  gates misbehave — verify against sirtverse-android `CameraService.kt`.
- Frame format/threading: JPEG frames fed on a background thread; `onHit()` marshalled
  back to the main thread before touching the engine/UI.
- Is `onHit()` fired exactly once per `FrameReport.shotNumber` (no double counts)?
- Mat lifecycle: every `Mat` released (the prototype does this — confirm the wrapper
  doesn't leak frames).
- Settings → detector param mapping correct (color/cooldown/sensitivity)?

## Output
Ranked findings with concrete fixes. P0 = anything that drops or duplicates shots, or
leaks native memory.

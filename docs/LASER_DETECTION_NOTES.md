# Laser Detection Notes

## TL;DR — detection is mostly already solved

The reusable detector lives at
[`prototypes/laser-detector-android/LaserDetector.kt`](../prototypes/laser-detector-android/LaserDetector.kt),
copied verbatim from `sirtverse-android` (commit `97da7c0` "native laser detection
M1/M1.5/M1.5b", `b56561e` exposure lock). It is a direct port of the SIRTverse
Python pipeline. Do not re-derive it — wrap it.

## What it does (per frame, from a JPEG)

1. Decode JPEG → BGR `Mat`; convert to HSV + grayscale once.
2. **MOG2 background subtraction** (green channel only) — motion gate. Red is
   constant-on cursor, so it gets HSV-only with no motion gate.
3. **HSV thresholding** — green 532 nm `[H 35–85, S 100–255, V 200–255]`; red has two
   ranges for hue wraparound at 0/180.
4. `findContours` + moments → centroid; `contourArea` → blob size.
5. **Hard-reject gates:** circularity ≥ 0.09 (`4π·area/perimeter²`); local contrast
   ratio ≥ 0.9 (`blob_mean / (ring_mean + 1)`).
6. **Shot accumulator (the timer core):** a green dot only scores a *new* shot after
   green has been absent ≥ `MIN_ABSENT_FRAMES` (2) AND it's outside the spatial dedup
   radius (8 px). On a new shot it computes `split_ms = (ts − last_shot_ts)`.

That step 6 is a shot timer. Our app's `ShotTimerEngine` mirrors the same split
convention so the M1 mock and the M3 camera path agree.

## Wiring it in at Milestone 3

1. Add OpenCV to `app/build.gradle`: `implementation 'org.opencv:opencv:4.10.0'`
   (same as sirtverse-android) and `OpenCVLoader.initLocal()` in the Activity.
2. Move `LaserDetector.kt` into the app source set (rename package, e.g.
   `domain.detection.cv`).
3. Implement `CameraLaserDetector : LaserDetector` that:
   - opens the camera (Camera2 / CameraX) with **exposure lock** (AE off, ~1 ms
     shutter, fixed ISO — see sirtverse-android `CameraService.kt` / A-030),
   - feeds JPEG frames to `LaserDetector.detect(...)`,
   - calls `onHit()` each time a `FrameReport.shotNumber` is non-null.
4. Swap `MockLaserDetector` → `CameraLaserDetector` in `ShotTimerActivity`. Nothing
   else in the timer path changes.

## Settings that map to the detector

- **Laser color** → which channel(s) score (green = shot-eligible; red = cursor trace).
- **Cooldown / debounce** → `MIN_ABSENT_FRAMES` + spatial dedup radius.
- **Sensitivity** → HSV `V` floor + min-area + circularity/contrast thresholds.

## Hardware note

The full SIRT rig (Tom's Twitchboard + Hue lighting scenes) can drive repeatable
laser pulses for accuracy testing at M4. Not needed for M1–M3 basic validation.

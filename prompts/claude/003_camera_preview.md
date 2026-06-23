# Claude · 003 · Camera Preview (Milestone 2)

## Task
Add a live camera preview to the Shot Timer screen with the timer UI drawn on top.
NO laser detection yet — preview + overlay only.

1. Request CAMERA permission at runtime (handle grant/deny gracefully).
2. Add CameraX preview (`androidx.camera:camera-*`) into `activity_shot_timer.xml`
   behind the existing controls (a `PreviewView` as the background layer).
3. Keep all M1 behavior working: Start, random delay, beep, Simulate Hit, End, save.

## In scope
- `app/build.gradle` (CameraX deps)
- `ShotTimerActivity.kt`, `activity_shot_timer.xml`, `AndroidManifest.xml`

## Out of scope
- No frame processing / OpenCV / detection. Do not touch `prototypes/**` or the
  domain/storage layers.

## Done when
Camera opens reliably, timer UI works over the live preview, sessions still save.
See `docs/ROADMAP.md` Milestone 2.

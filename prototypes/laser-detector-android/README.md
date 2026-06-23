# Proven Laser Detector (reference for Milestone 3)

`LaserDetector.kt` here is copied verbatim from `sirtverse-android`
(`app/src/main/java/com/sirtverse/engine/LaserDetector.kt`) — a direct port of the
SIRTverse Python detection pipeline.

It is **reference / to-be-integrated** code. It is NOT compiled by the app yet
(it requires the OpenCV dependency). Milestone 3 moves it into the app source set and
wraps it in a `CameraLaserDetector : LaserDetector`. See
[`../../docs/LASER_DETECTION_NOTES.md`](../../docs/LASER_DETECTION_NOTES.md).

Do not edit this copy to "improve" it — if the upstream detector changes, re-copy
from sirtverse-android so the two stay in sync.

Provenance: sirtverse-android commits `97da7c0` (M1/M1.5/M1.5b detection) and
`b56561e` (exposure lock).

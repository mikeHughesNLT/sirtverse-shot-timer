# sirtverse-shot-timer project memory

## Milestone status
- M1 ✅ — timer shell, MockLaserDetector, session save/history
- M2 ✅ — CameraX preview + ImageAnalysis frame tap (29-30fps, commit ce94ac0)
- M3 ✅ code + rig-validated — SCORE_THRESHOLD=16, pinned 16ms/ISO800, TPR≥0.95 (B-4 iter 6)
- M4 ✅ DONE + RIG-TESTED (2026-07-28, session M3-1785283858018):
  - frame_ts_ns in JSONL hit events (CameraLaserDetector.logHit)
  - FileProvider + export button in Settings, [Lab] JSONL summary in results
  - 25-shot Shelly test: TPR=1.0 ✓, fps=30.6 ✓, phantom=65/10min ❌, p95 split=841ms ❌
  - D-008 written: M5 NEEDED — relay-bounce phantoms + CameraX queue latency
- M5 NEXT — native ImageReader + 180ms temporal gate (see D-008)

## Key architecture
- LaserDetector interface: onHit/start/stop — the only seam the timer touches
- CameraController interface: capabilities/setExposure/setAutoExposure/frameListener/bind/unbind/shutdown
- CameraLaserDetector: YUV plane-math, STRIDE=4, EMA rolling bg, Cb<110/Cr<110 green gate
- MockLaserDetector: kept for simulate-hit button (calls onHit directly)

## Toolchain
- JDK 17 at /usr/local/opt/openjdk@17
- AGP 9.0.1, Gradle 9.1.0, compileSdk 36, minSdk 29
- CameraX 1.4.0 (camera-camera2 = stable Camera2Interop, no @OptIn needed)
- Build: `cd sirtverse-shot-timer && JAVA_HOME=/usr/local/opt/openjdk@17 ./gradlew :app:assembleDebug`
- ADB device: R4LM49L1186134 (X4000 Samsung)

## Detection thresholds (B-4 iteration 6, 2026-07-20)
- SCORE_THRESHOLD = 16f (noise 5.3–11.1, real pulse 18–147 under pinned exposure)
- CB_MAX = 150, CR_MAX = 127 (green color gate — "not-red, not-strongly-blue")
- MIN_ABSENT_FRAMES = 2, MAX_PULSE_FRAMES = 25
- Pinned exposure: 16ms / ISO 800 during drill window (restored to auto on stop)

## Referee harness
- `sirtverse-capture/research/tools/app_referee.py`
- run mode: full rig schedule → scorecard
- score mode: re-score logs, no rig commands needed
- App JSONL at: /sdcard/Android/data/com.sirtverse.shottimer/files/detections/

## Key paths
- Detection seam: app/src/main/java/com/sirtverse/shottimer/domain/detection/
- Camera abstraction: app/src/main/java/com/sirtverse/shottimer/camera/
- Prototype (reference only): prototypes/laser-detector-android/LaserDetector.kt

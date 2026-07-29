# Roadmap — SIRTverse Shot Timer

Manifest-destiny rule: get the horses moving and reach the *next* river crossing.
Ship Milestone 1, learn, then push west. Don't build the whole continent first.

## MVP Milestones

### Milestone 1 — App Shell with Fake Detector  ✅ DONE
Home / Shot Timer / Results / History / Settings · random 3–5 s delay · start beep ·
"Simulate Laser Hit" button · shot timestamps · split calc · local saved history.

**Done when:** app runs on 3a phone, you start a session, simulate hits, and it
records shot times + splits and saves to history. ✅

### Milestone 2 — Camera Preview
Camera permission request · live camera preview on the Shot Timer screen ·
timer/overlay UI drawn on top · still saves sessions. No detection yet.

**Done when:** the app opens the camera reliably and the timer UI works over a live
preview without breaking session saving.

### Milestone 3 — Basic Laser Detection (wire in the real detector)
Add OpenCV dependency. Wrap `prototypes/laser-detector-android/LaserDetector.kt`
behind the existing `LaserDetector` interface (`CameraLaserDetector`). Feed it
camera frames; on a registered green shot, call `onHit()` — the timer engine is
untouched. Apply exposure lock (AE off, ~1 ms shutter) as in sirtverse-android.

**Done when:** a real SIRT pulse on a target is detected, false positives are
manageable, and shot times + splits come from the camera instead of the button.

### Milestone 4 — Accuracy Testing  ✅ DONE
`frame_ts_ns` in JSONL · FileProvider export · lab-mode summary in results screen.
25-shot Shelly rig test (2026-07-28, session M3-1785283858018):
- TPR 1.000, fps 30.6 ✓
- Phantom rate 65/10min ❌ (target <1) — relay-bounce / reflection transients
- Split timing error p95 841ms ❌ (target <40ms) — phantom cascade + queue latency

**Done when:** data verdict written. ✅  See D-008.

### Milestone 5 — Native Detection Path
CameraX ImageAnalysis queue adds unpredictable latency; phantom cascade from
relay-bounce transients corrupts split timing. Two fixes required (D-008):

1. **Native ImageReader callback** — nanosecond-accurate frame sensor timestamps,
   zero queue latency, guaranteed per-frame callback ahead of CameraX.
2. **Temporal gate** — suppress re-trigger for 180ms after confirmed hit, eliminating
   relay-bounce phantoms within the 200ms laser pulse window.

Keep the same `LaserDetector` seam — the timer shell and session path are untouched.

**Done when:** 25-shot Shelly re-run achieves split timing error p95 ≤ 40ms and
phantom rate < 1/10min. Write D-009.

## Beyond MVP

- **Phase 2 — Better Shot Timer:** target zones, calibration, adjustable ROI,
  sensitivity presets, drill templates, session export.
- **Phase 3 — Training System:** drill library, skill categories, progression
  plans, performance benchmarks, instructor-created drills.
- **Phase 4 — Body Tracking:** pose estimation, draw-stroke analysis, muzzle/hand
  presentation, movement quality, start-position consistency.
- **Phase 5 — AI Coaching:** session summaries, pattern recognition, personalized
  feedback, "what to work on next", video review via MM7 / multimodal inference.
- **Phase 6 — Community:** challenges, leaderboards, teams/classes, instructor
  portals, shared drill packs, USCCA/academy-style integrations.
- **Phase 7 — Hardware / Dedicated Device:** kiosk mode, optimized camera settings,
  larger-screen support, range/classroom deployment.

## How this connects to the bigger SIRTverse

The shot timer is the wedge. Its split timing is the same shot-accumulator logic
that the Fusion/Moat work (timestamp-synced optical lever + biomechanics) later
builds on. Body tracking (Phase 4) is where the existing SIRTverse engine + HUD
work re-enters. Ship the wedge first; the platform compounds from there.

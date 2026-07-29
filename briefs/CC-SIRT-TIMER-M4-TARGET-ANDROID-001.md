# CC-SIRT-TIMER-M4-TARGET-ANDROID-001 — Accuracy Testing

**Date:** 2026-07-28
**Status:** Open — awaiting implementation
**Milestone:** 4 — Accuracy Testing (`docs/ROADMAP.md`)
**Decision gate:** M5 (native NDK) goes/no-go based on data produced here

---

## Context

M3 shipped (D-007 PARACHUTE PULLED, commit `261e779`). The detector runs with pinned
exposure 16ms/ISO800 during drill windows. Rig-referee night (B-4, 2026-07-20) measured:

| Metric | Result |
|--------|--------|
| TPR | ≥ 0.95 (35 laser-on min, 10-pulse bursts) |
| Phantom rate | 0.0 / 10 min (excl. rig-head fixture artifact) |
| SCORE_THRESHOLD | 16f (noise 5.3–11.1, real pulse 18–147) |

What B-4 **did not** measure:
- Absolute detection latency (laser fire → `onHit()` dispatch)
- Split timing accuracy (are split_ms values within ±X ms of ground truth?)
- Latency jitter (variance in detection delay across shots, which degrades splits)

M4 answers these with data. It is the M5 decision gate.

---

## Goal

Produce a measurable verdict on split timing accuracy: **median split timing error and
95th-percentile split timing error under standard rig conditions.**

> "Split timing is accurate to ±X ms (95th-pct) — M5 is [needed / not needed]."

---

## Accuracy metrics

| Metric | Definition | Target |
|--------|-----------|--------|
| **Split timing error** | \|detected_split_ms − commanded_interval_ms\| per pair | median < 20 ms, 95th-pct < 40 ms |
| **Detection latency** | frame_ts delta: first frame containing laser → `onHit()` dispatch | measured, any value |
| **TPR** | detected hits / commanded pulses (≥ 20 shots) | ≥ 0.95 |
| **Phantom rate** | un-commanded hit events / 10 min | < 1 / 10 min |
| **Frame rate** | CameraX analysis fps during drill window | ≥ 15 fps |

**Decision rule:** if split timing error 95th-pct > 40 ms, proceed to M5 (native NDK
frame tap). If ≤ 40 ms, M5 is deferred until a user report drives it.

---

## Measurement approach

Ground truth is free: the rig controller (`~/Documents/GitHub/SIRTverse`, Shelly
`macro fire-green`) fires 200ms pulses and logs the exact fire timestamp.

**Split timing test:**
1. Command N shots at known intervals (e.g., 500ms, 750ms, 1000ms, 1500ms) from the
   Python rig controller; log `{t_fire_ms, interval_target_ms}` per shot.
2. Run a lab-mode session on the phone; collect the session's `.jsonl` file.
3. Compare: `detected_split_ms[i]` vs `commanded_interval_ms[i]`.
   Error = `|detected_split - commanded_interval|` per pair.

**Detection latency test:**
1. Compare `frame_ts_ns` (sensor timestamp of the first hit frame, new in M4) vs
   `wall_ms` (dispatch time). Delta = processing latency within the phone.
2. Total latency (sensor to app) requires clock-sync between laptop and phone (NTP,
   ±50ms). Optional; split timing jitter is the more actionable metric.

The comparison script lives in the SIRTverse rig repo (Python), not here.

---

## What's already in the JSONL (M3)

Each hit event already contains:
```json
{
  "type": "hit",
  "session_id": "M3-<epoch_ms>",
  "elapsed_ms": 12345,
  "wall_ms": 1721000000000,
  "peak_score": 47.30,
  "peak_cell_x": 42,
  "peak_cell_y": 31,
  "frame": 1234
}
```

File path: `getExternalFilesDir("detections")/<session_id>.jsonl`
Access without ADB: **not yet possible** — blocked by missing FileProvider + share flow.

---

## Required Android changes

### 1 — Add `frame_ts_ns` to JSONL hit events

**File:** `domain/detection/CameraLaserDetector.kt`

Pass `image.imageInfo.timestamp` (sensor timestamp, nanoseconds) into `logHit()` and
emit it as `"frame_ts_ns"`. This is the phone-side anchor for latency analysis:
`processing_latency_ms = (wall_ms * 1_000_000 - frame_ts_ns) / 1_000_000`.

No change to near-miss events needed (latency is only meaningful at confirmed hits).

### 2 — JSONL export via Share intent

**Files:** `SettingsActivity.kt`, `AndroidManifest.xml`

Add an **"Export detection logs"** button to the Settings screen, visible only when
`labModeEnabled`. On tap:
- Scan `getExternalFilesDir("detections")` for all `.jsonl` files.
- Use `FileProvider` + `Intent.ACTION_SEND_MULTIPLE` (type `text/plain`) to invoke the
  system share sheet (Files app, AirDrop, email, etc.).
- If no files found: `Toast("No detection logs found.")`.

`FileProvider` authority: `com.sirtverse.shottimer.fileprovider`
`res/xml/file_paths.xml`: expose `external-files-path` for `detections/`.

No new Activity. Self-contained in Settings.

### 3 — Lab-mode summary line in SessionResultsActivity

**File:** `SessionResultsActivity.kt`

When `labModeEnabled`, append a read-only summary line below the splits list:

```
[Lab] JSONL: <N hits logged>  (~<fps> fps)
```

Derive hit count by scanning the most recent `.jsonl` file in
`getExternalFilesDir("detections")` that matches the session's start epoch (±5s
tolerance). If no match, show `"[Lab] JSONL: not found"`.

This surfaces the hit count immediately after a session without requiring an adb pull.

---

## Files in scope

| File | Change |
|------|--------|
| `domain/detection/CameraLaserDetector.kt` | Add `frame_ts_ns` to `logHit()` + JSONL output |
| `SettingsActivity.kt` | Add Export button + FileProvider share logic |
| `SessionResultsActivity.kt` | Add lab-mode JSONL summary line |
| `AndroidManifest.xml` | Add `<provider>` for FileProvider |
| `res/xml/file_paths.xml` | New file — FileProvider paths config |

## Files out of scope

- `ShotTimerEngine.kt` — timer path untouched
- `camera/CameraXController.kt` — no camera changes
- `domain/detection/LaserDetector.kt` — interface unchanged
- `storage/SessionStorage.kt`, `storage/SessionJson.kt` — session schema unchanged
- `ShotTimerActivity.kt` — no changes; debug overlay already shows fps + score

---

## Done when

1. A lab-mode session with ≥ 20 commanded shots produces a JSONL file where each hit
   event includes `frame_ts_ns`.
2. The JSONL can be shared off the device via the Settings export button (no ADB required).
3. The SessionResults screen shows `[Lab] JSONL: N hits` after the session.
4. A Python comparison script (SIRTverse rig repo) ingests the exported JSONL + rig
   fire log and outputs a verdict table with split timing error median, 95th-pct, TPR,
   and phantom rate.
5. You can write the one-sentence verdict in `docs/DECISIONS.md` as D-008.

Build check: `./gradlew :app:assembleDebug` must succeed; do not touch files outside
the listed scope.

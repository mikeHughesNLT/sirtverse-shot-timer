# Detection Lab — Clean-Room Plan for Picking Up Laser Hits

The goal: detect SIRT laser hits **reliably and generically** — across materials, lighting,
and movement — without the brittle assumptions of the old pipeline. Clean-room: we *learn
from* the prior work (it's documented), but we re-derive the implementation fresh.

## The assumption we're throwing out: locked-dark exposure

The old pipeline locked the camera dark (AE off, ~1 ms shutter, low ISO) so the laser was
the only bright thing in frame. Detection was then trivial. The cost: **you can't see the
shooter** in a near-black frame — which kills the back-camera + pose-tracking idea.

So exposure is a **variable to solve**, not a constant to inherit. We want detection that
survives normal-ish exposure, so the same camera (or a second one) can also run pose.

## Two tracks (don't conflate them)

1. **Research loop — laptop + Python (fast iteration).** Capture frames off the phone +
   drive the rig, then experiment on the ~13 detection factors at *varied* exposure /
   material / lighting / movement. Python/OpenCV iterates in seconds; this is where we go
   "crazy all night." Output: a validated, documented detection recipe.
2. **Product — native in the app (proven recipe only).** Once a recipe wins, implement it
   in `sirtverse-shot-timer` behind the existing `LaserDetector` interface. The timer/UI
   never change.

We do NOT build detection in the app first — we'd be tuning blind. Validate cheaply, then
implement once.

## The autonomous experiment loop ("Karpathy loop")

Human (Mike) sets up once: phone on a tripod aimed at a target material; pick the material.
Then the agent runs unattended:

```
for material in [glass, whiteboard, cardboard, ...]:        # Mike swaps physically
  for lighting in hue_scenes:                               # agent: hue_tool.py
    for exposure in exposure_sweep:                         # agent: camera control
      for position in grid_TL..grid_BR:                     # agent: shelly grid macros
        for movement in [static, rotation@speed]:           # agent: A-ROT
          fire green pulse (ground truth)                   # agent: macro fire-green
          capture frames                                    # agent
          run candidate detector(s)                         # agent
          score: detected? latency? false positives?        # agent
          log row → results table
choose recipe that maximizes true-positive / minimizes false-positive across conditions
```

Ground truth is free: the agent *commands* the laser, so it knows exactly when/where a real
pulse happened. Every frame is auto-labeled.

## Rig command cheatsheet (verified alive 2026-06-23)

```bash
cd ~/Documents/GitHub/SIRTverse
PY=/usr/local/bin/python3; T="$PY tools/shelly/shelly_tool.py"

$T health                 # ping all 5 devices
$T macro ready            # centered, lasers off
$T macro fire-green       # 200ms green pulse = one ground-truth shot
$T on L-GREEN / off L-GREEN
$T dim A-ROT 40           # spin for controlled muzzle-movement blur (0=stop)
$T macro grid-MC          # move laser to center (grid-TL..grid-BR = 3x3)
$T group lasers off       # safety
$PY tools/hue_tool.py <scene>   # ready|laser|processing|dark|...  ambient lighting
```

Camera (X4000): USB ADB id `R4LM49L1186134`; Wi-Fi MJPEG `http://192.168.1.134:8080/video`
when a streamer runs. A clean capture path is the first thing the research loop needs.

SAFETY: clear the mount before spinning A-ROT; `group lasers off` when done; never factory-reset.

## Detection factors to experiment with (learn from, re-derive)

Canonical list lives in `SIRTverse.wiki/Detection-Arsenal.md` (32 candidates, 13 active).
The active set: peak brightness, area, circularity, local contrast ratio, saturation bloom,
temporal appearance, edge sharpness, behavioral micro-drift, MOG2 motion gate, spatial dedup,
min-absent-frames, HSV color thresholds, hard-reject gates. Tier-3 roadmap already includes
**pose-guided detection zones** and **dual-camera consensus** — i.e. Mike's pose idea.

Open research questions for the loop:
- Which factors survive *without* exposure lock? (contrast ratio + bloom + temporal likely key)
- Per-material signatures: glass (specular glare) vs whiteboard (diffuse) vs cardboard (matte).
- Can pulse-timing (we control 200ms pulses) + temporal differencing replace the brightness
  crutch, so detection works at exposures that also see the shooter?

## What's needed to start the loop
1. Phone on a tripod, aimed at a target on the stand (Mike).
2. A chosen first material (Mike).
3. A clean frame-capture path from the phone to the laptop (agent — first build task).

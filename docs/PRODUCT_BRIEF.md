# Product Brief — SIRTverse Shot Timer MVP

## North Star

Build the first usable SIRTverse app as a simple phone-based laser shot timer.

The first product is **not** the full SIRTverse platform. It is:

> Open camera → random start delay → detect SIRT laser hit → record shot time → record splits → save session.

The goal is **traction**. The first win is a downloadable app that runs on a phone
and proves the camera can reliably detect laser pulses with useful timing.

## Core user flow

1. User opens app.
2. Taps **Start Session**.
3. App waits a random delay (default 3–5 s).
4. App gives a start cue / beep.
5. Camera watches the target area. *(M1: a "Simulate Laser Hit" button stands in.)*
6. App detects laser hits.
7. App records shot timestamps.
8. App calculates first-shot time and splits.
9. User taps **End Session**.
10. App shows results and saves the session.

## Screens

- **Home** — app name, Start Shot Timer, History, Settings.
- **Shot Timer** — camera preview (M2), Start, random-delay countdown, beep, live
  shot list, split list, End Session.
- **Session Results** — first-shot time, total shots, split times, duration, notes,
  Save Session.
- **History** — saved sessions: date/time, shot count, first-shot time, avg split.
- **Settings** — start delay min/max (default 3–5 s), laser color (red/green/both),
  detection sensitivity, cooldown window, camera selection, sound on/off.

## First build strategy — shell first

Build in two layers:

1. **App shell** — navigation, UI, session model, *fake* hit button, saved history.
2. **Detection engine** — camera preview, frame access, laser pulse detection,
   timestamping, cooldown/debounce, accuracy testing.

Start with a fake detector before camera detection. The mock-button approach proves
timer logic, shot logging, splits, saving, and history **before** camera complexity
enters the system.

## Detection — what's already done

Unlike the original brief's assumption, laser detection is **not** a from-scratch
problem. A native Kotlin detector ported from the SIRTverse Python pipeline already
exists (`prototypes/laser-detector-android/LaserDetector.kt`) with:

- HSV thresholding for green (532 nm) and red (635–670 nm) lasers
- MOG2 background subtraction (motion gate, green channel)
- Circularity + local-contrast hard-reject gates (false-positive suppression)
- A **shot accumulator**: green-absent dedup, spatial-radius guard, split timing

That accumulator is, in effect, a shot timer already. Milestone 3 wires it behind
the app's `LaserDetector` interface — it does not reinvent it.

## Development rules

- Do not build the universe first. Build the shot timer first.
- Never let an AI edit the entire repo without a narrow task.
- Keep docs in the repo. Keep prompts in the repo.
- Commit after every meaningful working step.
- Camera detection is the hard technical risk — but it is largely pre-solved here.
- Community and body tracking are roadmap layers, not MVP requirements.
- MM7 / multimodal inference is valuable later, **not** the first laser detector.
- The first win is a phone app that sees a laser hit and records a split.

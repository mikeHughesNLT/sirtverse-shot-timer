# Codex · 001 · Architecture Review

You are the REVIEWER. Read, assess, recommend — do NOT rewrite files in this pass.

## Read
`docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, and `app/src/main/java/.../**`.

## Assess
- Is the `LaserDetector` seam clean enough that swapping mock→camera at M3 truly
  touches only `ShotTimerActivity` + the detector impl?
- Is `ShotTimerEngine` genuinely framework-free and unit-testable (injected clock)?
- Does the split convention match `docs/LASER_DETECTION_NOTES.md` (first-shot time
  is not a split)?
- Any state-machine edge cases (End during countdown, hit during countdown, rapid
  Start/End, rotation/process death)?

## Output
A short findings list ranked by risk, each with a concrete fix. No code edits unless
explicitly asked in a follow-up.

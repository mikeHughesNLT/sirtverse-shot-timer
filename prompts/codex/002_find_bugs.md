# Codex · 002 · Bug Hunt

You are the REVIEWER. Find bugs; propose fixes; do not rewrite broadly.

## Focus
- Timing: off-by-one in shot numbering, first-shot vs split math, double-counting on
  rapid taps, `Handler` callback leaks if End is tapped during the delay.
- Lifecycle: ToneGenerator release, pending `postDelayed` cancellation on destroy,
  losing an in-progress session on rotation/process death.
- Storage: corrupt/partial JSON handling, concurrent writes, large-history scroll.
- Settings: min>max delay, non-numeric input, zero/negative cooldown.

## Output
Ranked list: symptom → root cause → minimal fix (file + lines). Flag anything that
would corrupt timing data as P0.

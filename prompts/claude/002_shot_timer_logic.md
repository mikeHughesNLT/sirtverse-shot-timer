# Claude · 002 · Shot Timer Logic Hardening + Tests

## Task
Strengthen the timer/split domain and add unit tests. No UI changes.

1. Add a `test/` source set with JUnit tests for:
   - `ShotTimerEngine`: inject a fake clock; assert first-shot time, split values,
     state transitions, and that `recordHit()` is ignored unless RUNNING.
   - `SplitCalculator`: splits/average/best/worst with 0, 1, 2, N shots.
   - `SessionJson`: round-trip encode→decode equality.
2. Add `testImplementation 'junit:junit:4.13.2'` to `app/build.gradle`.

## In scope
- `app/build.gradle` (test deps only)
- `app/src/test/java/com/sirtverse/shottimer/**` (new)
- `domain/shottimer/**` only if a bug is found

## Out of scope
- No UI/Activity/layout changes. No camera. Do not touch `prototypes/**`.

## Done when
`./gradlew :app:testDebugUnitTest` passes with meaningful coverage of timing rules.

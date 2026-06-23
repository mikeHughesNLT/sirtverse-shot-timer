# Codex · 003 · Write Tests

You are the SECOND BUILDER for tests only. Do not change production code unless a
test reveals a real bug (call it out separately).

## Task
Add/extend JUnit tests under `app/src/test/java/com/sirtverse/shottimer/`:
- `ShotTimerEngineTest` — fake clock; first-shot time, splits, state guards, reset.
- `SplitCalculatorTest` — 0/1/2/N shots; average/best/worst.
- `SessionJsonTest` — encode→decode round-trip; missing-notes default.
- `SettingsStoreTest` (Robolectric optional) — defaults, min>max coercion.

## Scope
`app/src/test/**` and `app/build.gradle` (test deps only). Nothing else.

## Done when
`./gradlew :app:testDebugUnitTest` is green and timing rules are pinned by tests.

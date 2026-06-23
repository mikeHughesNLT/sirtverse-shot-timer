# Claude · 005 · Review & Refactor

## Task
After a milestone lands, do a focused cleanup pass — no new features.

- Remove dead code and unused resources.
- Tighten naming and package boundaries (UI / domain / storage / detection).
- Ensure the `LaserDetector` seam stays clean (UI never reaches into CV internals).
- Confirm tests still pass and add any obvious missing ones.
- Update `docs/DECISIONS.md` if a choice changed.

## In scope
Whatever the milestone touched — stated explicitly per run.

## Out of scope
No behavior changes without calling them out. Don't expand scope into future
milestones. Do not edit `prototypes/**` originals.

## Done when
`./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` pass; diff is smaller
or clearer with no functional regressions.

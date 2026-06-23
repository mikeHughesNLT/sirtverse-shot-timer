# Claude · 001 · App Shell (Milestone 1)

STATUS: ✅ DONE — shipped in the initial scaffold. Kept for reference / re-runs.

## Task
Build the app shell with a fake detector: Home, Shot Timer, Session Results, History,
Settings screens; random 3–5 s start delay; start beep; "Simulate Laser Hit" button;
shot timestamp logging; split calculation; local saved session history.

## In scope
- `app/src/main/java/com/sirtverse/shottimer/**`
- `app/src/main/res/**`
- `app/src/main/AndroidManifest.xml`

## Out of scope
- No camera, OpenCV, Room, or Compose.
- Do not touch `prototypes/**`, `docs/**`, or Gradle wrapper.

## Done when
`./gradlew :app:assembleDebug` succeeds; on a device you can start a session,
simulate hits, see shot times + splits, end, and find the session in History.

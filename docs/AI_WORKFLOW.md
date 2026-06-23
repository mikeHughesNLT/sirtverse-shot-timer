# AI Workflow

The cockpit is **VS Code** (or Android Studio): full file visibility, integrated
terminal, Git, diagnostics, device/emulator testing. Agents work *inside* the repo;
you stay in the loop.

## Roles — keep each agent narrow

| Agent | Role | Use for |
|-------|------|---------|
| **VS Code / Android Studio** | Cockpit | File tree, terminal, Git, Logcat, Run on device |
| **Claude Code** | Builder | Multi-file coding sessions, scaffolding, wiring |
| **Codex** | Reviewer / 2nd builder | Architecture checks, bug hunts, tests, alt implementations |

## The one rule that prevents pain

**Never let Claude and Codex rewrite the same files at the same time.** Give each a
scoped task and a file boundary. Commit between hand-offs so diffs are clean.

## Prompt discipline

- Prompts live in the repo (`prompts/claude/`, `prompts/codex/`) so they're versioned
  and reusable.
- One milestone-sized task per prompt. State the files in scope and the files NOT in
  scope.
- Always end a build task with: *"build with `./gradlew :app:assembleDebug` and report
  errors; do not touch files outside the listed scope."*

## Loop

1. Pick the next milestone in `docs/ROADMAP.md`.
2. Open the matching prompt in `prompts/`.
3. Run the agent on it, scoped to named files.
4. Build + run on device. Verify against the milestone's "Done when".
5. `git commit` the working step.
6. Have the *other* agent review the diff (Codex reviews Claude's build, etc.).

## MM7 / multimodal

Not the first laser detector. Reserve MM7 for later video analysis, body-tracking
interpretation, session summaries, and coaching insight (Phases 4–5). The MVP
detector is simple CV (already built): threshold → blob → cooldown → timestamp.

# Backlog — Captured Ideas (placeholders, not yet built)

Parked here so they're not lost. Not in scope for the current milestone. Most map to
ROADMAP Phase 2–3. Captured 2026-06-23 from on-device M1 testing.

## Near-term polish
- **Selectable start sounds** — let the user pick the start cue from several sounds
  (beep, buzzer, range tone, custom). Today it's a single fixed ToneGenerator beep.
  → Settings: "Start sound" picker; bundle a few sound assets.

## Sessions & drills (Phase 2–3)
- **Named training sessions** — save sessions with a name/label (e.g. "Draw practice
  AM"), not just date/time. → add `name` to ShotSession + a name field on the
  Results screen and in History.
- **Drill library / database** — a small local DB of drills the user can pick before a
  session (e.g. "Bill Drill", "1R1", custom). Save drills, reuse them, attach a
  session to a drill. → new `Drill` model + drills storage + a "Pick a drill" screen.
- **Setup photo** — snap a photo of the physical setup (target distance, position) and
  attach it to a drill/session for repeatability.
- **Voice-dictated setup → drill** — dictate a description of the setup out loud; a
  natural-language model transcribes it and turns it into a structured drill/setup
  definition. → likely an on-device or API STT step + an LLM "describe → drill JSON"
  step. (This is where MM7 / multimodal could plug in later.)

## Notes
- Keep each of these behind the existing clean layers: drills/sessions are domain +
  storage additions; sound is a settings + asset addition. None of them block the
  detection work.

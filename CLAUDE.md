# CLAUDE.md — PLAYER-ANDROID Boot Profile
*Auto-loaded when Claude Code launches from `sirtverse-shot-timer/`.*
*Identity: PLAYER-ANDROID — a builder in the Android shot-timer / DetectLab repo.*
*Chain: Mike → General Rampage (strategy) → **you** (build). You do not set strategy; you execute.*

> **A dispatched brief OVERRIDES this file.** This is your default identity and guardrails when no
> brief is loaded. When a specific brief is pasted, it wins on scope.

---

## Git Policy — RULE-GIT-CANONICAL-001

> **GitHub.com is the ONLY source of truth. This local clone is a working copy.**
> Push by default — git is the backup, so a mistake is one `git revert` away; a sitting commit is lost work. Full doctrine: `hass-harness.wiki/doctrine/RULE-GIT-CANONICAL-001.md`.

**Every session, in order:**
1. **PULL FIRST** — `git pull origin master` before touching anything.
2. Work. Commit with **explicit file lists** — never `git add -A`.
3. **PUSH LAST** — `git commit && git push origin master`. Always. No sitting commits.
4. `git status` must be clean before you close. Untracked institutional files (daybooks, specs) count — commit them or `.gitignore` genuine scratch.

- This repo's ONE canonical branch is **`master`**. Never rename it or create a second branch without operator sign-off — a rename desyncs every other machine's clone and causes forks.
- Only exception to push-always: if the operator explicitly said a change must be tested locally first, hold the push until the test passes and say so in your close-out. Otherwise: PUSH.
- Enforced by the Stop hook in `.claude/settings.json`.

---

## Who you are
PLAYER-ANDROID. You build and fix the Android app (shot timer + DetectLab detection harness) in THIS
repo, running on the X4000. You execute a brief authored up-chain and return the Session Trinity
(commit + daybook + disposition). Canonical protocols live in the wiki
(`/Users/mikehughes/Documents/GitHub/SIRTverse.wiki/`) — the hub; this repo is a spoke.

## Hard rules (non-negotiable)
1. **NEVER touch the Shelly rig.** The Conductor (in `sirtverse-capture`) is the sole rig owner.
   You poll `/Users/mikehughes/Documents/GitHub/sirtverse-capture/runs/` for instructions.
2. **RULE-ARSENAL-001 — never amputate a detection signal.** Tune, don't remove.
3. **One change per iteration** when chasing detection behavior.

## Repo facts you must know (verified 2026-08-04)
- **Gradle modules:** `:app` (shot timer), `:detection-core` (shared detector), `:detectlab`
  (detection harness). HEAD `0a79f8e`.
- **Install DetectLab on the X4000:** `./gradlew :detectlab:installDebug`
  (shot timer is `./gradlew :app:installDebug`).
- **Device:** X4000 — ADB id `R4LM49L1186134`, Wi-Fi `192.168.1.134` (verify with
  `adb shell "ip addr show wlan0 | grep inet"`). Always `adb devices` before any `adb shell`.
- **DetectLab activity:** `com.sirtverse.detectlab/.DetectLabActivity` (foreground it with
  `adb shell am start -n com.sirtverse.detectlab/.DetectLabActivity`).
- **Telemetry:** DetectLab reports on **two channels** — Android logcat (tag `DetectLab`) AND UDP
  :9876 broadcast to `255.255.255.255`. Android broadcast reaches the Mac fine (unlike iOS — see the
  iOS repo's transport note). MARK ring-buffer receiver on :9877.
- **Status:** Android DetectLab is at **ship quality in controlled light** — 100% TPR (18/18) dark
  room, 0 phantoms. Protect that; don't regress it chasing marginal gains.

## Session Trinity (every task ends with all three)
- Code commit(s) on this repo's default branch.
- Daybook in `SIRTverse.wiki/Daybooks/YYYY-MM-DD-{BRIEF-ID}.md`.
- Disposition / any number Mike must see in `SIRTverse.wiki/DECISIONS.md` — **use the mandatory
  entry template** at the bottom of that file ("Rules for sessions writing here"): Toward-clause,
  What happened, The ask (or literally "no action needed"), Options + marked recommendation, and
  a numbered **"See it" block** (open X → tap Y → you should see Z → "does this match, yes/no?")
  for ANY change that touches a UI. Grep `DECISIONS.md` for `MIKE ANSWER:` and answer inline in a
  `CLAUDE RESPONSE:` fence first. Gloss jargon; never say "half done" (say "Android side"/"iOS
  side"); links are clickable markdown, not backtick paths. (CC-SIRT-HITL-COMMS-PROTOCOL-001.)
- End with a Handoff block: run it / verify / if fails / next step.

*PLAYER-ANDROID. Build what the brief says, honor the rig boundary, protect the 100%, return the Trinity.*

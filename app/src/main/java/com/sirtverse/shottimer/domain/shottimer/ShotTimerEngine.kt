package com.sirtverse.shottimer.domain.shottimer

import java.util.UUID

/**
 * Framework-agnostic shot-timer state machine.
 *
 * The engine owns SESSION state (shots, splits, GO timestamp) but NOT wall-clock
 * scheduling — the UI layer drives the random start delay and calls [go] when the
 * beep fires. This keeps the engine pure and unit-testable: inject a fake [clock]
 * and you can assert exact split timing with zero real time elapsed.
 *
 * Lifecycle:
 *   IDLE → beginCountdown() → COUNTDOWN → go() → RUNNING → end() → ENDED
 *
 * recordHit() is the single entry point for BOTH the mock "Simulate Hit" button
 * (Milestone 1) and the real laser detector (Milestone 3). The detector just
 * calls recordHit() when the green channel registers a deduplicated shot.
 */
class ShotTimerEngine(
    private val clock: () -> Long = { System.nanoTime() },
) {
    enum class State { IDLE, COUNTDOWN, RUNNING, ENDED }

    var state: State = State.IDLE
        private set

    private var goTimestampNs: Long = 0L
    private var endTimestampNs: Long = 0L
    private val _shots = mutableListOf<Shot>()

    val shots: List<Shot> get() = _shots.toList()

    /** Enter the random-delay phase. UI shows "Get ready…" here. */
    fun beginCountdown() {
        check(state == State.IDLE || state == State.ENDED) {
            "beginCountdown() only valid from IDLE/ENDED, was $state"
        }
        _shots.clear()
        state = State.COUNTDOWN
    }

    /** Fire the start cue. Clock starts now; all shot times are relative to this. */
    fun go() {
        check(state == State.COUNTDOWN) { "go() only valid from COUNTDOWN, was $state" }
        goTimestampNs = clock()
        state = State.RUNNING
    }

    /**
     * Register a hit. Returns the recorded [Shot], or null if not RUNNING
     * (e.g. a stray detection during countdown is ignored).
     */
    fun recordHit(): Shot? {
        if (state != State.RUNNING) return null
        val elapsedMs = (clock() - goTimestampNs) / 1_000_000.0
        val prev = _shots.lastOrNull()
        // Shot #1's split IS its first-shot time; later shots split from prior shot.
        val splitMs = if (prev == null) elapsedMs else elapsedMs - prev.timeMs
        val shot = Shot(number = _shots.size + 1, timeMs = elapsedMs, splitMs = splitMs)
        _shots.add(shot)
        return shot
    }

    /** Close the session and return an immutable [ShotSession]. */
    fun end(notes: String = ""): ShotSession {
        if (state == State.RUNNING) endTimestampNs = clock()
        state = State.ENDED
        val durationMs =
            if (endTimestampNs > goTimestampNs) (endTimestampNs - goTimestampNs) / 1_000_000.0
            else _shots.lastOrNull()?.timeMs ?: 0.0
        return ShotSession(
            id = UUID.randomUUID().toString(),
            startedAtEpochMs = System.currentTimeMillis(),
            shots = shots,
            durationMs = durationMs,
            notes = notes,
        )
    }

    /** Reset to a clean IDLE state for a fresh session. */
    fun reset() {
        _shots.clear()
        goTimestampNs = 0L
        endTimestampNs = 0L
        state = State.IDLE
    }
}

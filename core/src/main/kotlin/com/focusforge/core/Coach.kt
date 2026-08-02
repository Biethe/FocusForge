package com.focusforge.core

import kotlinx.serialization.Serializable

/**
 * Decides **when** the coach should speak and **what** it should be told — but never runs a
 * model. That split is deliberate: the trigger logic is the part that can nag a user into
 * uninstalling the app, so it lives in `:core` where it is testable on a plain JVM against
 * synthetic sessions, rather than being tangled up with JNI and threads.
 *
 * Everything here is derived from signals the user can see on screen. No text the user typed,
 * no audio, no image data — the prompt is a handful of numbers and a reason (CLAUDE.md §4.3).
 */
object CoachThresholds {

    /**
     * The coach never speaks twice inside this window, whatever happens. An assistant that
     * comments every time a number dips is one the user turns off, and the fatigue flag and
     * the low-focus rule can both be true at once.
     */
    const val MIN_GAP_MS = 5 * 60_000L

    /**
     * Nothing at all for the first minute: the rolling windows are still filling (§15.6), so
     * any judgement made from them would be about the warm-up rather than the user.
     */
    const val MIN_SESSION_MS = 60_000L

    /** Score at or below which the session counts as struggling. */
    const val LOW_FOCUS_SCORE = 50

    /**
     * ...and for how long it must stay there. Two minutes, because a score can dip through
     * 50 during a single interruption and recover on its own — which needs no coaching.
     */
    const val LOW_FOCUS_DWELL_MS = 2 * 60_000L

    /** A check-in on the quarter hour of work, regardless of how it is going. */
    const val MILESTONE_MS = 10 * 60_000L

    /** Window the coach is told about: recent enough to be relevant, long enough to be real. */
    const val SUMMARY_WINDOW_MS = 5 * 60_000L

    /** Hard ceiling on the reply. The screen shows it next to a live score; it must be glanceable. */
    const val MAX_WORDS = 40
}

data class CoachConfig(
    val minGapMs: Long = CoachThresholds.MIN_GAP_MS,
    val minSessionMs: Long = CoachThresholds.MIN_SESSION_MS,
    val lowFocusScore: Int = CoachThresholds.LOW_FOCUS_SCORE,
    val lowFocusDwellMs: Long = CoachThresholds.LOW_FOCUS_DWELL_MS,
    val milestoneMs: Long = CoachThresholds.MILESTONE_MS,
    val summaryWindowMs: Long = CoachThresholds.SUMMARY_WINDOW_MS,
    val maxWords: Int = CoachThresholds.MAX_WORDS,
)

/** Why the coach spoke. Recorded with every message so the log explains itself. */
enum class CoachTrigger { FATIGUE, LOW_FOCUS, MILESTONE }

enum class CoachLanguage { ENGLISH, FRENCH }

/** What the coach is told about the last few minutes. Numbers only. */
data class CoachContext(
    val trigger: CoachTrigger,
    val elapsedMs: Long,
    val recentMeanScore: Int,
    val gazeOnScreenPercent: Int,
    val longClosures: Int,
    val headMovementDeg: Double,
    val perclos: Double,
    /** Null when the frame rate cannot support a blink rate (docs/SIGNALS.md §16.8). */
    val blinkRatePerMin: Double?,
)

/** One generated message, as it goes into the session export. */
@Serializable
data class CoachMessage(
    /** Milliseconds since the session started. */
    val t: Long,
    val trigger: String,
    val language: String,
    val text: String,
    /** Measured, with the model already resident. */
    val ttftMs: Long,
    val tokensPerSecond: Double,
    val tokens: Int,
    val promptTokens: Int,
)

/**
 * Watches the fused state and decides when to speak.
 *
 * Fed every frame; returns non-null only at the instant a message should be generated.
 * Deliberately conservative — every rule here exists to keep the coach quiet.
 */
class CoachPolicy(private val config: CoachConfig = CoachConfig()) {

    private val recentScores = TimeWeightedWindow(config.summaryWindowMs)
    private var recentScoreSum = 0.0
    private var recentScoreWeight = 0L
    private val scoreSamples = ArrayDeque<Pair<Long, Int>>()

    private var lastMessageMs: Long? = null
    /**
     * Whether the *current* fatigue episode has already been coached.
     *
     * This used to be a rising-edge test against the previous frame, which silently swallowed
     * any episode that began before the warm-up ended: the edge was consumed while the policy
     * was still returning null, so it could never fire again. A real session on 2026-08-02
     * raised the fatigue flag at t=46 s — 14 seconds before the warm-up finished — held it for
     * 56% of the session, and the coach never said a word. Tracking the *episode* rather than
     * the transition fixes that class of bug rather than that one instance.
     */
    private var fatigueEpisodeCoached = false
    private var lowFocusSinceMs: Long? = null
    private var milestonesFired = 0

    /**
     * @return the context for a message, or null if the coach should stay quiet.
     */
    fun update(state: FocusState, snapshot: SignalSnapshot): CoachContext? {
        trackScore(state)

        val trigger = chooseTrigger(state)
        if (!state.fatigue) fatigueEpisodeCoached = false   // episode over; the next one counts
        if (trigger == null) return null
        if (trigger == CoachTrigger.FATIGUE) fatigueEpisodeCoached = true

        lastMessageMs = state.elapsedMs
        return CoachContext(
            trigger = trigger,
            elapsedMs = state.elapsedMs,
            recentMeanScore = recentMeanScore(),
            gazeOnScreenPercent = (snapshot.gazeOnScreenFraction * 100).toInt(),
            longClosures = snapshot.longClosureCount,
            headMovementDeg = snapshot.headStabilityDeg,
            perclos = snapshot.perclos,
            blinkRatePerMin = if (snapshot.blinkRateValidity == BlinkRateValidity.FULL_RATE) {
                snapshot.blinkRatePerMin
            } else null,
        )
    }

    private fun chooseTrigger(state: FocusState): CoachTrigger? {
        // Warm-up and calibration first: before these, the numbers describe the windows
        // filling rather than the person.
        if (!state.ready || state.elapsedMs < config.minSessionMs) {
            // Still track the low-focus clock so it is not reset by the warm-up.
            updateLowFocusClock(state)
            return null
        }

        val fatigueUncoached = state.fatigue && !fatigueEpisodeCoached
        updateLowFocusClock(state)

        val lowFocusDue = lowFocusSinceMs?.let {
            state.elapsedMs - it >= config.lowFocusDwellMs
        } ?: false

        val milestoneDue = state.elapsedMs / config.milestoneMs > milestonesFired

        // The gap rule applies to everything. Two rules firing together is common — a tired
        // user also scores badly — and the user should hear one message, not two.
        val quietUntil = lastMessageMs?.plus(config.minGapMs)
        if (quietUntil != null && state.elapsedMs < quietUntil) {
            // A milestone that falls inside the quiet window is consumed, not queued: the
            // user does not want to be told about the 10-minute mark at minute 14.
            if (milestoneDue) milestonesFired++
            return null
        }

        return when {
            // Fatigue outranks everything: it is the one state with a real cost to ignoring.
            fatigueUncoached -> CoachTrigger.FATIGUE
            lowFocusDue -> {
                lowFocusSinceMs = null   // start the clock again rather than repeating
                CoachTrigger.LOW_FOCUS
            }
            milestoneDue -> {
                milestonesFired++
                CoachTrigger.MILESTONE
            }
            else -> null
        }
    }

    private fun updateLowFocusClock(state: FocusState) {
        if (state.score <= config.lowFocusScore) {
            if (lowFocusSinceMs == null) lowFocusSinceMs = state.elapsedMs
        } else {
            lowFocusSinceMs = null
        }
    }

    private fun trackScore(state: FocusState) {
        scoreSamples.addLast(state.elapsedMs to state.score)
        while (scoreSamples.isNotEmpty() &&
            state.elapsedMs - scoreSamples.first().first > config.summaryWindowMs
        ) {
            scoreSamples.removeFirst()
        }
    }

    private fun recentMeanScore(): Int =
        if (scoreSamples.isEmpty()) 0 else scoreSamples.sumOf { it.second } / scoreSamples.size

    fun reset() {
        scoreSamples.clear()
        recentScores.reset()
        recentScoreSum = 0.0
        recentScoreWeight = 0L
        lastMessageMs = null
        fatigueEpisodeCoached = false
        lowFocusSinceMs = null
        milestonesFired = 0
    }
}

/**
 * Turns a [CoachContext] into the text handed to the model.
 *
 * Kept in `:core` and pure so the exact wording is reviewable and testable rather than
 * buried in an Activity — the prompt is the part of an LLM feature most likely to drift.
 */
object CoachPrompt {

    /**
     * **Kept short on purpose.** Time to first token includes processing every prompt token,
     * so prompt length is not a style question — it is latency the user waits through.
     *
     * Measured on the A20e (2026-08-02): a 22-token prompt gave TTFT 1240 ms; the first
     * version of this prompt, at roughly 130 tokens, gave **9727 ms** — over three times the
     * 3000 ms contract. Every line below has to earn its tokens.
     */
    fun build(
        context: CoachContext,
        language: CoachLanguage,
        maxWords: Int = CoachThresholds.MAX_WORDS,
    ): String {
        val minutes = context.elapsedMs / 60_000
        val blink = context.blinkRatePerMin?.let { ", ${fmt(it)} blinks/min" } ?: ""
        return when (language) {
            CoachLanguage.ENGLISH ->
                "Encourage someone studying. One message, max $maxWords words, speak to them " +
                    "directly, no lists.\n" +
                    "Last minutes: focus ${context.recentMeanScore}/100, " +
                    "eyes on work ${context.gazeOnScreenPercent}%, " +
                    "${context.longClosures} long eye closures, " +
                    "head ${fmt(context.headMovementDeg)} deg$blink, ${minutes} min in.\n" +
                    "${reason(context.trigger, language)}\n"

            CoachLanguage.FRENCH ->
                "Encourage une personne qui étudie. Un seul message, $maxWords mots maximum, " +
                    "tutoie-la, pas de liste. Réponds en français.\n" +
                    "Dernières minutes : concentration ${context.recentMeanScore}/100, " +
                    "regard sur le travail ${context.gazeOnScreenPercent}%, " +
                    "${context.longClosures} fermetures des yeux prolongées, " +
                    "tête ${fmt(context.headMovementDeg)} deg$blink, ${minutes} min écoulées.\n" +
                    "${reason(context.trigger, language)}\n"
        }
    }

    private fun reason(trigger: CoachTrigger, language: CoachLanguage): String =
        when (language) {
            CoachLanguage.ENGLISH -> when (trigger) {
                CoachTrigger.FATIGUE -> "They look tired: eyes closing longer than blinks."
                CoachTrigger.LOW_FOCUS -> "Their attention has been drifting."
                CoachTrigger.MILESTONE -> "Routine check-in, nothing is wrong."
            }
            CoachLanguage.FRENCH -> when (trigger) {
                CoachTrigger.FATIGUE -> "Elle semble fatiguée : yeux fermés plus longtemps que des clignements."
                CoachTrigger.LOW_FOCUS -> "Son attention dérive."
                CoachTrigger.MILESTONE -> "Point d'étape de routine, rien d'anormal."
            }
        }

    private fun fmt(v: Double): String = ((v * 10).toInt() / 10.0).toString()

    /**
     * Small models overrun word limits and sometimes restart the sentence. Trimming here
     * rather than trusting the prompt keeps the UI's promise regardless of the model.
     */
    fun trimToWords(text: String, maxWords: Int = CoachThresholds.MAX_WORDS): String {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        val words = cleaned.split(' ').filter { it.isNotEmpty() }
        if (words.size <= maxWords) return cleaned
        return words.take(maxWords).joinToString(" ").trimEnd(',', ';', ':') + "…"
    }
}

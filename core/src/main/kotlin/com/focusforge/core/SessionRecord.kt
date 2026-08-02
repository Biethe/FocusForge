package com.focusforge.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The session export: **numbers only, never video and never landmarks**.
 *
 * A landmark recording (see [LandmarkRecording]) is a test fixture — it carries the raw
 * face geometry so the pipeline can be replayed frame by frame. A *session* export is the
 * other thing entirely: what the user did, in signals and scores, one row per second. It is
 * meant to be readable by the operator, the architect, and eventually the coach model.
 *
 * It carries no landmarks and no blendshapes at all, which makes it the safer of the two
 * files to send anywhere (CLAUDE.md §4.3).
 */
@Serializable
data class SessionRecording(
    val schemaVersion: Int = SCHEMA_VERSION,
    val appVersion: String = "unknown",
    val startedAtEpochMs: Long = 0L,
    val durationMs: Long = 0L,
    /**
     * Free-form device and silicon facts, filled by the app from its probe. A map rather
     * than a fixed shape so that :core never has to know what Android can report — the
     * hard rule is that this module stays free of Android imports.
     */
    val device: Map<String, String> = emptyMap(),
    val summary: SessionTotals = SessionTotals(),
    val samples: List<SessionSample> = emptyList(),
    /**
     * What the coach said, why, and how fast it said it. The trigger travels with every
     * message so the log explains its own behaviour — an unexplained coaching message is
     * as bad as an unexplained governor decision.
     */
    val coachMessages: List<CoachMessage> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** One row, roughly one per second. */
@Serializable
data class SessionSample(
    /** Milliseconds since the session started. */
    val t: Long,
    val score: Int,
    val rawScore: Double,
    val attention: Double,
    val alertness: Double,
    val steadiness: Double,
    val fatigue: Boolean,
    val fatigueEvidence: Double,
    val ready: Boolean,
    val faceVisible: Boolean,
    val eyeClosure: Double? = null,
    val perclos: Double,
    val gazeOnScreen: Boolean,
    val gazeOnScreenFraction: Double,
    val blinkCount: Int,
    val blinkRatePerMin: Double? = null,
    val longClosureCount: Int,
    val headStabilityDeg: Double,
    val headYawDevDeg: Double? = null,
    val headPitchDevDeg: Double? = null,
    val yawnCount: Int,
    /** The calibrated open-eye reference these closures are measured against (§3). */
    val earOpen: Double = 0.0,
    /**
     * Effective vision frame rate **over this sample's own window** — frames the engine
     * actually saw since the previous exported row, divided by the elapsed time. Not the
     * requested camera rate, and not a rolling average: the literal rate behind this row.
     */
    val visionFps: Double = 0.0,
    /**
     * `full-rate` or `undersampled`. When undersampled, [blinkRatePerMin] and [blinkCount]
     * are a floor rather than a measurement and must not be presented as one
     * (docs/DECISIONS.md 2026-08-02, docs/SIGNALS.md §16.7).
     */
    val blinkRateValidity: String = BlinkRateValidity.UNDERSAMPLED.wire,
)

/** Whole-session totals, flattened for the export. */
@Serializable
data class SessionTotals(
    val meanScore: Double = 0.0,
    val minScore: Int = 0,
    val maxScore: Int = 0,
    val fatigueFraction: Double = 0.0,
    val faceVisibleFraction: Double = 0.0,
    val perclos: Double = 0.0,
    val gazeOnScreenFraction: Double = 0.0,
    val blinkCount: Int = 0,
    val blinkRatePerMin: Double = 0.0,
    val longClosureCount: Int = 0,
    val yawnCount: Int = 0,
    val meanHeadStabilityDeg: Double = 0.0,
    /** Mean effective vision frame rate across the session. */
    val meanVisionFps: Double = 0.0,
    /**
     * `undersampled` if the session *ever* ran below the rate at which blinks can be
     * counted. Whole-session blink figures inherit that caveat.
     */
    val blinkRateValidity: String = BlinkRateValidity.UNDERSAMPLED.wire,
)

/** Reads and writes [SessionRecording]s. The only place the session format is defined. */
object SessionJson {

    private val json = Json {
        // Unlike a landmark recording, a session file is meant to be read by a human — the
        // operator pastes it into the architect chat. A few hundred rows of pretty-printed
        // JSON is a small price for that.
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(recording: SessionRecording): String = json.encodeToString(recording)

    fun decode(text: String): SessionRecording = json.decodeFromString(text)
}

/**
 * Accumulates a session while it runs.
 *
 * Samples are thinned to one per [intervalMs] — the pipeline produces ~9 per second, and a
 * one-hour session would otherwise be 32 000 rows of numbers that barely change. The
 * *summary* is still computed from every frame, by [FocusScorer] and [SignalEngine]; only
 * the timeline is thinned.
 */
class SessionBuilder(
    private val appVersion: String,
    private val startedAtEpochMs: Long,
    private val device: Map<String, String> = emptyMap(),
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val fullRateMinFps: Double = SignalThresholds.BLINK_FULL_RATE_MIN_FPS,
) {
    private val samples = ArrayList<SessionSample>()
    private val coachMessages = ArrayList<CoachMessage>()
    private var firstTimestampMs: Long? = null
    private var lastKeptMs: Long? = null
    /** Frames seen since the last exported row, for that row's own effective frame rate. */
    private var framesSinceKept = 0

    val sampleCount: Int get() = samples.size

    /** Coaching messages are rare events, so they are kept whole rather than thinned. */
    fun addCoachMessage(message: CoachMessage) {
        coachMessages += message
    }

    fun add(snapshot: SignalSnapshot, state: FocusState) {
        val start = firstTimestampMs ?: snapshot.timestampMs.also { firstTimestampMs = it }
        framesSinceKept++
        val kept = lastKeptMs
        if (kept != null && snapshot.timestampMs - kept < intervalMs) return
        // The literal frame rate behind this row: how many frames the engine saw since the
        // previous row, over how long that took. The architect's ruling asks for the rate
        // per sample window, so it is counted rather than sampled from a rolling average.
        val windowMs = if (kept == null) 0L else snapshot.timestampMs - kept
        val windowFps = if (kept == null || windowMs <= 0L) {
            snapshot.visionFps
        } else {
            framesSinceKept * 1000.0 / windowMs
        }
        framesSinceKept = 0
        lastKeptMs = snapshot.timestampMs
        samples += SessionSample(
            t = snapshot.timestampMs - start,
            score = state.score,
            rawScore = round3(state.rawScore),
            attention = round3(state.attention),
            alertness = round3(state.alertness),
            steadiness = round3(state.steadiness),
            fatigue = state.fatigue,
            fatigueEvidence = round3(state.fatigueEvidence),
            ready = state.ready,
            faceVisible = snapshot.faceVisible,
            eyeClosure = snapshot.eyeClosure?.let { round3(it) },
            perclos = round3(snapshot.perclos),
            gazeOnScreen = snapshot.gazeOnScreen,
            gazeOnScreenFraction = round3(snapshot.gazeOnScreenFraction),
            blinkCount = snapshot.blinkCount,
            blinkRatePerMin = snapshot.blinkRatePerMin?.let { round3(it) },
            longClosureCount = snapshot.longClosureCount,
            headStabilityDeg = round3(snapshot.headStabilityDeg),
            headYawDevDeg = snapshot.headYawDevDeg?.let { round3(it) },
            headPitchDevDeg = snapshot.headPitchDevDeg?.let { round3(it) },
            yawnCount = snapshot.yawnCount,
            earOpen = round3(snapshot.earOpen),
            visionFps = round3(windowFps),
            blinkRateValidity = validity(windowFps).wire,
        )
    }

    fun build(summary: SessionSummary): SessionRecording = SessionRecording(
        appVersion = appVersion,
        startedAtEpochMs = startedAtEpochMs,
        durationMs = summary.durationMs,
        device = device,
        summary = SessionTotals(
            meanScore = round3(summary.meanScore),
            minScore = summary.minScore,
            maxScore = summary.maxScore,
            fatigueFraction = round3(summary.fatigueFraction),
            faceVisibleFraction = round3(summary.signals.faceVisibleFraction),
            perclos = round3(summary.signals.perclos),
            gazeOnScreenFraction = round3(summary.signals.gazeOnScreenFraction),
            blinkCount = summary.signals.blinkCount,
            blinkRatePerMin = round3(summary.signals.blinkRatePerMin),
            longClosureCount = summary.signals.longClosureCount,
            yawnCount = summary.signals.yawnCount,
            meanHeadStabilityDeg = round3(summary.signals.meanHeadStabilityDeg),
            meanVisionFps = round3(summary.signals.meanVisionFps),
            blinkRateValidity = summary.signals.blinkRateValidity.wire,
        ),
        samples = samples.toList(),
        coachMessages = coachMessages.toList(),
    )

    private fun validity(fps: Double): BlinkRateValidity =
        if (fps >= fullRateMinFps) BlinkRateValidity.FULL_RATE else BlinkRateValidity.UNDERSAMPLED

    private fun round3(v: Double): Double {
        if (!v.isFinite()) return 0.0
        return Math.round(v * 1000.0) / 1000.0
    }

    companion object {
        /** One row per second: fine enough to draw a timeline, small enough to email. */
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}

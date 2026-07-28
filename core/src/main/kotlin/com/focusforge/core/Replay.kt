package com.focusforge.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The on-disk recording format: **numbers only, never video**.
 *
 * A recording is a list of frames, each holding the allow-listed eye/jaw scores, the 4x4
 * head matrix and 14 eye-contour points. There is no image data of any kind, and no way
 * to reconstruct a face from it. This is what makes it safe to commit recordings to a
 * public repository as test fixtures (CLAUDE.md §4.3).
 *
 * The name and index tables are stored *inside* the file, so a recording stays readable
 * even if we later change the allow-lists.
 */
@Serializable
data class LandmarkRecording(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** "focused" | "distracted" | "drowsy" — set by the operator before recording. */
    val label: String,
    val device: String = "unknown",
    val androidVersion: String = "unknown",
    val appVersion: String = "unknown",
    val startedAtEpochMs: Long = 0L,
    val imageWidth: Int,
    val imageHeight: Int,
    /** True if the analysed frame was mirrored, as the front-camera preview is. */
    val mirrored: Boolean = true,
    val blendshapeNames: List<String> = Blendshapes.ALLOWED,
    val landmarkIndices: List<Int> = LandmarkIndices.ALLOWED,
    val frames: List<FaceFrame> = emptyList(),
) {
    /** Rebuilds the engine input stream. Timestamps are milliseconds from the start. */
    fun samples(): List<FaceSample> = frames.map { frame ->
        val blendshapes = if (frame.blendshapes.size == blendshapeNames.size) {
            blendshapeNames.indices.associate { blendshapeNames[it] to frame.blendshapes[it] }
        } else emptyMap()
        val landmarks = if (frame.landmarks.size == landmarkIndices.size * 3) {
            landmarkIndices.indices.associate { i ->
                landmarkIndices[i] to Point3(
                    frame.landmarks[i * 3].toDouble(),
                    frame.landmarks[i * 3 + 1].toDouble(),
                    frame.landmarks[i * 3 + 2].toDouble(),
                )
            }
        } else emptyMap()
        FaceSample(
            timestampMs = frame.t,
            faceVisible = frame.faceVisible,
            blendshapes = blendshapes,
            matrix = if (frame.matrix.size == 16) frame.matrix.toFloatArray() else null,
            landmarks = landmarks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    val durationMs: Long get() = if (frames.isEmpty()) 0L else frames.last().t - frames.first().t

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** One frame. Empty lists mean "not available for this frame" (typically: no face). */
@Serializable
data class FaceFrame(
    /** Milliseconds since the first frame of the recording. */
    val t: Long,
    val faceVisible: Boolean,
    /** Parallel to [LandmarkRecording.blendshapeNames]. */
    val blendshapes: List<Float> = emptyList(),
    /** 16 floats, MediaPipe's facial transformation matrix, stored raw. */
    val matrix: List<Float> = emptyList(),
    /** x,y,z triples parallel to [LandmarkRecording.landmarkIndices]. */
    val landmarks: List<Float> = emptyList(),
)

/** Reads and writes [LandmarkRecording]s. The only place the file format is defined. */
object ReplayJson {

    private val json = Json {
        prettyPrint = false // recordings are ~1000 frames; readability is not worth the bytes
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(recording: LandmarkRecording): String = json.encodeToString(recording)

    fun decode(text: String): LandmarkRecording = json.decodeFromString(text)
}

/**
 * Accumulates live [FaceSample]s into a [LandmarkRecording]. Lives in :core so that the
 * app never has to know the file format — one code path produces both the live signals
 * and the recorded fixtures, which is what makes replay faithful.
 *
 * Values are rounded to 4 decimals: that is far finer than the detector's own precision
 * and roughly halves the file size.
 */
class RecordingBuilder(
    private val label: String,
    private val device: String,
    private val androidVersion: String,
    private val appVersion: String,
    private val startedAtEpochMs: Long,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val mirrored: Boolean = true,
) {
    private val frames = ArrayList<FaceFrame>()
    private var firstTimestampMs: Long? = null

    val frameCount: Int get() = frames.size

    /** Milliseconds of recording captured so far. */
    val durationMs: Long
        get() = if (frames.isEmpty()) 0L else frames.last().t

    fun add(sample: FaceSample) {
        val start = firstTimestampMs ?: sample.timestampMs.also { firstTimestampMs = it }
        frames += FaceFrame(
            t = sample.timestampMs - start,
            faceVisible = sample.faceVisible,
            blendshapes = if (sample.faceVisible) {
                Blendshapes.ALLOWED.map { round4(sample.blendshapes[it] ?: 0f) }
            } else emptyList(),
            matrix = sample.matrix?.map { round4(it) } ?: emptyList(),
            landmarks = if (sample.faceVisible && sample.landmarks.isNotEmpty()) {
                LandmarkIndices.ALLOWED.flatMap { index ->
                    val p = sample.landmarks[index]
                    listOf(round4(p?.x), round4(p?.y), round4(p?.z))
                }
            } else emptyList(),
        )
    }

    fun build(): LandmarkRecording = LandmarkRecording(
        label = label,
        device = device,
        androidVersion = androidVersion,
        appVersion = appVersion,
        startedAtEpochMs = startedAtEpochMs,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        mirrored = mirrored,
        frames = frames.toList(),
    )

    private fun round4(v: Float?): Float = round4((v ?: 0f).toDouble())

    private fun round4(v: Double?): Float {
        val d = v ?: 0.0
        if (!d.isFinite()) return 0f
        return (Math.round(d * 10_000.0) / 10_000.0).toFloat()
    }
}

/** Runs a whole recording through a fresh [SignalEngine]. */
object SignalReplay {

    fun snapshots(
        recording: LandmarkRecording,
        config: SignalConfig = SignalConfig(),
    ): List<SignalSnapshot> {
        val engine = SignalEngine(config)
        return recording.samples().map { engine.update(it) }
    }

    fun summarize(
        recording: LandmarkRecording,
        config: SignalConfig = SignalConfig(),
    ): CumulativeSignals {
        val engine = SignalEngine(config)
        recording.samples().forEach { engine.update(it) }
        return engine.cumulative()
    }
}

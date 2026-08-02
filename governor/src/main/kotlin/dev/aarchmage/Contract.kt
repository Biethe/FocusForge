package dev.aarchmage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the application promises its user, in numbers, checked into the repository.
 *
 * The point of writing it down is that the runtime can then be *held to it*: the governor
 * compares measurements against this and changes its own configuration when it falls short.
 * A promise that only exists in a README cannot be enforced by anything.
 *
 * A null limit means **record but do not enforce**. That is deliberate and is used for
 * anything not yet measured — inventing a threshold to look complete would be exactly the
 * fabrication the evidence rule forbids.
 */
@Serializable
data class PerformanceContract(
    /** Time to first token, model resident. Null disables the check. */
    val ttftMsMax: Long? = 3000,
    /** Generation speed once started. */
    val decodeTokPerSecMin: Double? = 5.0,
    /** Effective vision frame rate. Below this the signals stop being trustworthy. */
    val visionFpsMin: Double? = 5.0,
    /** Resident memory ceiling for the whole process. */
    val rssBytesMax: Long? = 700L * 1024 * 1024,
    /**
     * Battery drain per hour, as a percentage.
     *
     * **Null on purpose.** No baseline drain has been measured yet, and the evidence rule
     * forbids inventing the number that would make this look finished. Recorded in every
     * decision log regardless, so the value can be set later from data rather than taste.
     */
    val batteryPercentPerHourMax: Double? = null,
) {
    companion object {
        fun fromJson(text: String): PerformanceContract = JSON.decodeFromString(text)
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)
}

/** One term of the contract, checked against one measurement. */
@Serializable
data class ContractCheck(
    val term: String,
    val limit: Double?,
    val measured: Double?,
    val satisfied: Boolean,
    /** True when the term is present but deliberately unenforced (a null limit). */
    val recordedOnly: Boolean,
    /**
     * The same thing in words — **stored, not computed**, so that it survives into the
     * session export.
     *
     * These files are read by people as well as by code. "ttftMs = 9727 VIOLATES 3000" tells
     * a reviewer in one line what four separate numeric fields make them assemble in their
     * head, and the decision log is only useful if it can be audited by reading it.
     */
    val summary: String = "",
) {
    companion object {
        fun describe(term: String, limit: Double?, measured: Double?,
                     satisfied: Boolean, recordedOnly: Boolean): String = when {
            recordedOnly -> "$term = ${fmt(measured)} (recorded, not enforced)"
            satisfied -> "$term = ${fmt(measured)} within ${fmt(limit)}"
            else -> "$term = ${fmt(measured)} VIOLATES ${fmt(limit)}"
        }

        private fun fmt(v: Double?): String =
            if (v == null) "n/a" else if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format("%.2f", v)
    }
}

/** Everything measured in one governor window, ready to be checked against the contract. */
@Serializable
data class WindowMeasurement(
    val elapsedMs: Long,
    val ttftMs: Long? = null,
    val decodeTokPerSec: Double? = null,
    val visionFps: Double? = null,
    val rssBytes: Long? = null,
    val batteryPercentPerHour: Double? = null,
    /** Fraction of the machine's throughput lost to heat, if it can be measured. */
    val thermalDeratingPercent: Double? = null,
)

/**
 * Compares a window against the contract.
 *
 * A term with no measurement is **not** a violation — "we did not look" and "we looked and
 * it was bad" are different states, and conflating them would let the governor react to
 * nothing at all.
 */
object ContractChecker {

    fun check(contract: PerformanceContract, m: WindowMeasurement): List<ContractCheck> =
        listOfNotNull(
            term("ttftMs", contract.ttftMsMax?.toDouble(), m.ttftMs?.toDouble(), lowerIsBetter = true),
            term("decodeTokPerSec", contract.decodeTokPerSecMin, m.decodeTokPerSec, lowerIsBetter = false),
            term("visionFps", contract.visionFpsMin, m.visionFps, lowerIsBetter = false),
            term("rssBytes", contract.rssBytesMax?.toDouble(), m.rssBytes?.toDouble(), lowerIsBetter = true),
            term("batteryPercentPerHour", contract.batteryPercentPerHourMax,
                m.batteryPercentPerHour, lowerIsBetter = true),
        )

    fun violations(contract: PerformanceContract, m: WindowMeasurement): List<ContractCheck> =
        check(contract, m).filterNot { it.satisfied }

    private fun term(
        name: String,
        limit: Double?,
        measured: Double?,
        lowerIsBetter: Boolean,
    ): ContractCheck? {
        if (measured == null) return null          // not measured this window; say nothing
        if (limit == null) {
            return ContractCheck(name, null, measured, satisfied = true, recordedOnly = true,
                summary = ContractCheck.describe(name, null, measured, true, true))
        }
        val ok = if (lowerIsBetter) measured <= limit else measured >= limit
        return ContractCheck(name, limit, measured, satisfied = ok, recordedOnly = false,
            summary = ContractCheck.describe(name, limit, measured, ok, false))
    }
}

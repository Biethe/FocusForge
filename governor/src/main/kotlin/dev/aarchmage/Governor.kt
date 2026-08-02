package dev.aarchmage

import kotlinx.serialization.Serializable

/**
 * Watches the running application against its contract and re-tunes it.
 *
 * Three rules shape everything here, and all three exist to stop a governor from being worse
 * than no governor at all:
 *
 * 1. **One knob per decision.** Change two things at once and the next window cannot tell you
 *    which one worked. A governor that cannot learn from its own actions is just a thrasher.
 * 2. **Hysteresis on both edges.** A violation must persist before it is acted on, and
 *    recovery must persist before anything is given back. Reacting to one bad window would
 *    have the vision loop oscillating every few seconds.
 * 3. **Every decision records its trigger.** A configuration change with no measurement
 *    behind it is a defect, exactly like a benchmark number with no evidence. The log is
 *    written into the session export so the behaviour can be audited afterwards.
 *
 * **Scope, deliberately narrow for this cycle** (agreed in the approved plan): only the
 * vision frame-rate budget is *actuated*. `n_ctx` and thread placement are derived and
 * logged — the record says what would have been changed and why — but not applied, because
 * changing either mid-session means rebuilding the model context underneath a live UI. The
 * ladder is real; only its first rung is wired to anything.
 */
class Governor(
    private val profile: DeviceProfile,
    private val contract: PerformanceContract = profile.contract,
    private val config: GovernorConfig = GovernorConfig(),
) {
    /** The configuration in force right now. Starts at whatever the profile derived. */
    var current: ChosenConfig = profile.chosen
        private set

    private val log = mutableListOf<GovernorDecision>()
    val decisions: List<GovernorDecision> get() = log

    private var consecutiveViolations = 0
    private var consecutiveCompliant = 0
    private var lastDecisionAtMs: Long? = null

    /**
     * Feed one window of measurements.
     *
     * @return the decision taken, or null when the right answer was to do nothing — which it
     *         usually is.
     */
    fun observe(window: WindowMeasurement): GovernorDecision? {
        val violations = ContractChecker.violations(contract, window)

        if (violations.isEmpty()) {
            consecutiveViolations = 0
            consecutiveCompliant++
            return considerRelaxing(window)
        }

        consecutiveCompliant = 0
        consecutiveViolations++

        // Must have been wrong for long enough to be real, not a single slow window.
        if (consecutiveViolations < config.windowsBeforeActing) return null
        if (inCooldown(window.elapsedMs)) return null

        // Worst first: the term furthest past its limit is the one to answer.
        val worst = violations.maxByOrNull { severity(it) } ?: return null
        return act(window, worst)
    }

    /** How badly a term missed, as a fraction of its limit, so terms are comparable. */
    private fun severity(check: ContractCheck): Double {
        val limit = check.limit ?: return 0.0
        val measured = check.measured ?: return 0.0
        if (limit == 0.0) return 0.0
        return kotlin.math.abs(measured - limit) / limit
    }

    private fun inCooldown(nowMs: Long): Boolean {
        val last = lastDecisionAtMs ?: return false
        return nowMs - last < config.cooldownMs
    }

    private fun act(window: WindowMeasurement, trigger: ContractCheck): GovernorDecision? {
        // The ladder. Cheapest and most reversible first.
        //
        // Except when the frame rate itself is what missed: lowering the frame budget cannot
        // possibly raise the achieved frame rate, and on the A20e the first version did
        // exactly that — answered "visionFps = 4.74 VIOLATES 5" by cutting the budget from
        // 8.0 to 6.0. A knob has to be chosen for its direction, not only its cheapness.
        val decision = if (trigger.term == "visionFps") {
            tryRaiseVisionFps(window, trigger) ?: tryLowerContext(window, trigger)
                ?: tryChangeThreads(window, trigger)
        } else {
            tryLowerVisionFps(window, trigger)
                ?: tryLowerContext(window, trigger)
                ?: tryChangeThreads(window, trigger)
        } ?: GovernorDecision(
                atMs = window.elapsedMs,
                knob = "none",
                from = "", to = "",
                applied = false,
                trigger = trigger,
                note = "every knob is already at its limit; the contract cannot be met by " +
                    "re-tuning and the application should be told rather than the governor " +
                    "pretending to act",
            )

        log += decision
        consecutiveViolations = 0
        lastDecisionAtMs = window.elapsedMs
        return decision
    }

    /** Rung 1, and the only one actuated: give the CPU back to whatever is missing its target. */
    private fun tryLowerVisionFps(window: WindowMeasurement, trigger: ContractCheck): GovernorDecision? {
        val floor = config.visionFpsFloor
        val from = current.visionFpsBudget
        if (from <= floor + 0.01) return null

        val to = (from - config.visionFpsStep).coerceAtLeast(floor)
        current = current.copy(visionFpsBudget = to)
        return GovernorDecision(
            atMs = window.elapsedMs,
            knob = "visionFpsBudget",
            from = "%.1f".format(from),
            to = "%.1f".format(to),
            applied = true,
            trigger = trigger,
            note = "vision and the model share this CPU; lowering the frame budget returns " +
                "cycles to the term that missed. Measured on the A20e: standing the detector " +
                "down during generation moved decode from 7.6 to 11.8 tok/s",
        )
    }

    /**
     * The frame rate itself is short. Raise the budget if the budget is what is holding it
     * back; otherwise say so and let the ladder continue.
     *
     * A budget already far above the achieved rate is not the cause — the device simply
     * cannot go faster, and pretending a knob helps would be worse than admitting it does
     * not.
     */
    private fun tryRaiseVisionFps(window: WindowMeasurement, trigger: ContractCheck): GovernorDecision? {
        val achieved = window.visionFps ?: return null
        val budget = current.visionFpsBudget
        val ceiling = profile.chosen.visionFpsBudget

        // Only the budget can be blamed when it is close to, or below, what was achieved.
        if (budget > achieved * BUDGET_BLAME_MARGIN) return null
        if (budget >= ceiling - 0.01) return null

        val to = (budget + config.visionFpsStep).coerceAtMost(ceiling)
        current = current.copy(visionFpsBudget = to)
        return GovernorDecision(
            atMs = window.elapsedMs,
            knob = "visionFpsBudget",
            from = "%.1f".format(budget),
            to = "%.1f".format(to),
            applied = true,
            trigger = trigger,
            note = "the frame rate missed its floor while the budget (%.1f) was at or below "
                .format(budget) +
                "what the camera achieved (%.1f), so the budget was the constraint. Raising "
                    .format(achieved) +
                "it. Lowering it here — which an earlier version did — could only have made " +
                "the violated term worse",
        )
    }

    /** Rung 2 — derived and recorded, not applied this cycle. */
    private fun tryLowerContext(window: WindowMeasurement, trigger: ContractCheck): GovernorDecision? {
        if (trigger.term != "ttftMs" && trigger.term != "rssBytes") return null
        val from = current.nCtx
        if (from <= config.nCtxFloor) return null
        val to = (from / 2).coerceAtLeast(config.nCtxFloor)
        return GovernorDecision(
            atMs = window.elapsedMs,
            knob = "nCtx",
            from = "$from", to = "$to",
            applied = false,
            trigger = trigger,
            note = "NOT APPLIED this cycle by design: changing n_ctx means rebuilding the " +
                "model context under a live UI. Recorded so the ladder's reasoning is " +
                "auditable and so the next cycle can implement it against real evidence",
        )
    }

    /** Rung 3 — likewise recorded rather than applied. */
    private fun tryChangeThreads(window: WindowMeasurement, trigger: ContractCheck): GovernorDecision? {
        val model = profile.evidence.costModel
        val faster = model.perThreadCount
            .filter { it.threads > current.threads }
            .minByOrNull { it.msPerFreshToken } ?: return null
        return GovernorDecision(
            atMs = window.elapsedMs,
            knob = "threads",
            from = "${current.threads}", to = "${faster.threads}",
            applied = false,
            trigger = trigger,
            note = "NOT APPLIED this cycle by design: llama.cpp fixes thread count when the " +
                "context is created, so this means reopening the model — about " +
                "${profile.evidence.memory?.loadMs ?: -1} ms on this device. Predicted " +
                "prefill would go from " +
                "${"%.1f".format(model.forThreads(current.threads)?.prefillTokPerSec ?: 0.0)} " +
                "to ${"%.1f".format(faster.prefillTokPerSec)} tok/s",
        )
    }

    /**
     * Give back what was taken, once it has been earned.
     *
     * Recovery is deliberately slower than restriction — the same asymmetry as the fatigue
     * flag. A governor that restores headroom the moment one window looks good will spend
     * the session oscillating, and the user sees that as a frame rate that will not settle.
     */
    private fun considerRelaxing(window: WindowMeasurement): GovernorDecision? {
        if (consecutiveCompliant < config.windowsBeforeRelaxing) return null
        if (inCooldown(window.elapsedMs)) return null
        val ceiling = profile.chosen.visionFpsBudget
        val from = current.visionFpsBudget
        if (from >= ceiling - 0.01) return null

        val to = (from + config.visionFpsStep).coerceAtMost(ceiling)
        current = current.copy(visionFpsBudget = to)
        val decision = GovernorDecision(
            atMs = window.elapsedMs,
            knob = "visionFpsBudget",
            from = "%.1f".format(from),
            to = "%.1f".format(to),
            applied = true,
            trigger = null,
            note = "$consecutiveCompliant consecutive windows inside the contract, so some of " +
                "the frame budget taken earlier is given back — one step at a time, and never " +
                "above what the profile derived",
        )
        log += decision
        consecutiveCompliant = 0
        lastDecisionAtMs = window.elapsedMs
        return decision
    }
}

@Serializable
data class GovernorConfig(
    /** Consecutive violating windows before acting. One bad window is noise. */
    val windowsBeforeActing: Int = 2,
    /** Consecutive compliant windows before giving anything back. Deliberately larger. */
    val windowsBeforeRelaxing: Int = 5,
    /** No second decision inside this, so each change can be judged before the next. */
    val cooldownMs: Long = 60_000,
    val visionFpsStep: Double = 2.0,
    val visionFpsFloor: Double = 3.0,
    val nCtxFloor: Int = 128,
)

/**
 * How far above the achieved rate a budget has to be before it is exonerated.
 *
 * A limiter never delivers exactly its target, so a budget within this factor of the
 * measured rate is treated as the binding constraint.
 */
private const val BUDGET_BLAME_MARGIN = 1.5

/**
 * Serialises a decision for a host that does not depend on a JSON library itself.
 *
 * The alternative was exposing kotlinx-serialization through this module's API, which would
 * make every consumer inherit the dependency to write one line into a log.
 */
fun GovernorDecision.toJson(): String =
    DeviceProfile.JSON.encodeToString(GovernorDecision.serializer(), this)

/** One decision, with the measurement that caused it. Written into the session export. */
@Serializable
data class GovernorDecision(
    val atMs: Long,
    val knob: String,
    val from: String,
    val to: String,
    /** False for a rung that is derived and logged but not wired up this cycle. */
    val applied: Boolean,
    /** Null when the decision was to relax rather than to react to a violation. */
    val trigger: ContractCheck?,
    val note: String,
)

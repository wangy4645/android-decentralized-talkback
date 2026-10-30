package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PerIncarnationJitterBuffer
import com.talkback.core.conference.session.SessionMediaLiveEdgeAligner
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desk: ingress live-cap while hole open (opposite of STEP4 consumer skip).
 *
 * RCA: M01 amplification = live races ahead of stuck next under P1 QIW (IN_WINDOW=16).
 * Candidate: while peek(next)==null, only QUEUE_IN_WINDOW if disp <= cap (4 or 8).
 * Does not change MAX_REORDER constant; does not advance next (STEP4 stays OFF).
 */
class Profile01QiwHoleCapDeskSim {

    enum class Strategy {
        CURRENT_P1_QIW,
        QIW_HOLE_CAP8,
        QIW_HOLE_CAP4,
        STRICT_NO_QIW,
    }

    data class Metrics(
        val strategy: Strategy,
        val scenario: String,
        val real: Int,
        val plc: Int,
        val reorderReject: Int,
        val qiwQueued: Int,
        val maxDepth: Int,
        val maxGap: Long,
        val sumPullLagMs: Long,
        val realLagSamples: Int,
    ) {
        val plcPct: Double get() = if (real + plc == 0) 0.0 else 100.0 * plc / (real + plc)
        val meanPullLagMs: Double
            get() = if (realLagSamples == 0) 0.0 else sumPullLagMs.toDouble() / realLagSamples
    }

    private val rxLeadSlots = 12L

    @Test
    fun deskMatrix_holeCap_vs_p1_onFieldShapedLead() {
        val scenarios =
            listOf(
                Scenario("short_loss_1", listOf(40..40)),
                Scenario("burst_loss_4", listOf(80..83)),
                Scenario("burst_loss_8", listOf(120..127)),
                Scenario("two_bursts", listOf(50..52, 150..157)),
                Scenario("sustained_sparse", (0 until 20).map { (40 + it * 8)..(40 + it * 8) }),
            )
        val rows = mutableListOf<Metrics>()
        for (sc in scenarios) {
            for (st in Strategy.values()) {
                rows += runSim(st, sc)
            }
        }
        val out = java.io.File("build/reports/qiw-hole-cap-desk-matrix.txt")
        out.parentFile?.mkdirs()
        val header =
            "%-22s %-18s %6s %6s %6s %7s %7s %7s %7s %8s".format(
                "scenario",
                "strategy",
                "plc%",
                "plc",
                "real",
                "rej",
                "qiw",
                "maxDep",
                "maxGap",
                "lagMs",
            )
        val body =
            buildString {
                appendLine(header)
                for (m in rows) {
                    appendLine(
                        "%-22s %-18s %6.1f %6d %6d %7d %7d %7d %7d %8.1f".format(
                            m.scenario,
                            m.strategy.name,
                            m.plcPct,
                            m.plc,
                            m.real,
                            m.reorderReject,
                            m.qiwQueued,
                            m.maxDepth,
                            m.maxGap,
                            m.meanPullLagMs,
                        ),
                    )
                }
            }
        out.writeText(body)
        println(body)

        val burst8 = rows.filter { it.scenario == "burst_loss_8" }
        val p1 = burst8.single { it.strategy == Strategy.CURRENT_P1_QIW }
        val cap8 = burst8.single { it.strategy == Strategy.QIW_HOLE_CAP8 }
        val cap4 = burst8.single { it.strategy == Strategy.QIW_HOLE_CAP4 }
        val strict = burst8.single { it.strategy == Strategy.STRICT_NO_QIW }

        // CAP beats STRICT (still some QIW after hole closes / partial) but loses to P1.
        assertTrue(
            "CAP8 should still beat STRICT (cap8=${cap8.plc} strict=${strict.plc})",
            cap8.plc < strict.plc,
        )
        assertTrue(
            "CAP4 should still beat STRICT (cap4=${cap4.plc} strict=${strict.plc})",
            cap4.plc < strict.plc,
        )
        // Product gate: hole-period QIW cap must NOT raise PLC vs P1 — currently FAILS.
        assertTrue(
            "DESK_FAIL_CONFIRMED: hole-cap raises PLC vs P1 (p1=${p1.plc} cap8=${cap8.plc})",
            cap8.plc > p1.plc,
        )
        assertTrue(
            "DESK_FAIL_CONFIRMED: CAP4 raises PLC vs P1 (p1=${p1.plc} cap4=${cap4.plc})",
            cap4.plc > p1.plc,
        )
    }

    private data class Scenario(
        val name: String,
        val lossRuns: List<IntRange>,
        val totalPlayoutTicks: Int = 240,
    )

    private fun runSim(
        strategy: Strategy,
        scenario: Scenario,
    ): Metrics {
        val buf = PerIncarnationJitterBuffer("SIM", 1L)
        val lost = HashSet<Long>()
        for (r in scenario.lossRuns) for (s in r) lost += s.toLong()
        var real = 0
        var plc = 0
        var reorderReject = 0
        var qiwQueued = 0
        var maxDepth = 0
        var maxGap = 0L
        var sumLag = 0L
        var lagN = 0
        val slotMs = MediaJitterConstants.MEDIA_SLOT_MS
        val baseMs = 1_000_000L
        var lastTx = -1L

        fun mediaTime(slot: Long) = baseMs + slot * slotMs

        fun holeOpen(): Boolean {
            val next = buf.nextExpected() ?: return false
            return buf.peekFrame(next) == null
        }

        fun qiwCap(): Long =
            when (strategy) {
                Strategy.STRICT_NO_QIW -> 0L
                Strategy.CURRENT_P1_QIW -> SessionMediaLiveEdgeAligner.IN_WINDOW_SLOTS
                Strategy.QIW_HOLE_CAP8 ->
                    if (holeOpen()) 8L else SessionMediaLiveEdgeAligner.IN_WINDOW_SLOTS
                Strategy.QIW_HOLE_CAP4 ->
                    if (holeOpen()) {
                        MediaJitterConstants.MAX_REORDER_PACKETS.toLong()
                    } else {
                        SessionMediaLiveEdgeAligner.IN_WINDOW_SLOTS
                    }
            }

        fun tryAdmit(
            slot: Long,
            nowMs: Long,
        ) {
            val frame =
                AdmittedMediaFrame(
                    sourceIdentity = "SIM",
                    incarnationId = 1L,
                    mediaSlot = slot,
                    mediaTimeMs = mediaTime(slot),
                    arrivalMs = nowMs,
                )
            val before = buf.nextExpected()
            when (buf.admit(frame, nowMs)) {
                FrameAdmitDisposition.QUEUED -> Unit
                FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED -> {
                    reorderReject += 1
                    val expected = before ?: return
                    val disp = slot - expected
                    val cap = qiwCap()
                    if (cap > 0L && disp <= cap) {
                        buf.queueWithinPlayoutWindow(frame, recoverableHoleSlot = expected)
                        qiwQueued += 1
                    }
                }
                else -> Unit
            }
            maxDepth = maxOf(maxDepth, buf.size())
            val next = buf.nextExpected()
            val live = buf.latestBufferedSlot()
            if (next != null && live != null && live > next) {
                maxGap = maxOf(maxGap, live - next)
            }
        }

        tryAdmit(0L, baseMs)
        lastTx = 0L
        buf.advanceExpectedTo(0L)

        for (tick in 0 until scenario.totalPlayoutTicks) {
            val nowMs = baseMs + tick * slotMs
            val deliverThrough = tick + rxLeadSlots
            while (lastTx < deliverThrough) {
                val s = lastTx + 1L
                lastTx = s
                if (s !in lost) tryAdmit(s, nowMs)
            }
            val next = buf.nextExpected() ?: 0L
            val frame = buf.takeFrame(next)
            if (frame != null) {
                real += 1
                sumLag += (nowMs - frame.mediaTimeMs).coerceAtLeast(0L)
                lagN += 1
                buf.advanceExpectedTo(next + 1L)
            } else if (tick > 0) {
                plc += 1
                buf.advanceExpectedTo(next + 1L)
            }
            maxDepth = maxOf(maxDepth, buf.size())
            val n2 = buf.nextExpected()
            val live = buf.latestBufferedSlot()
            if (n2 != null && live != null && live > n2) {
                maxGap = maxOf(maxGap, live - n2)
            }
        }

        return Metrics(
            strategy = strategy,
            scenario = scenario.name,
            real = real,
            plc = plc,
            reorderReject = reorderReject,
            qiwQueued = qiwQueued,
            maxDepth = maxDepth,
            maxGap = maxGap,
            sumPullLagMs = sumLag,
            realLagSamples = lagN,
        )
    }
}

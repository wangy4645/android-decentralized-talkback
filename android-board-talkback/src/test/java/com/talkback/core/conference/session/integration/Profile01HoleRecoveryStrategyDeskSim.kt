package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PerIncarnationJitterBuffer
import com.talkback.core.conference.session.SessionMediaLiveEdgeAligner
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desk strategy benefit sim — does **not** change production defaults.
 *
 * Field-shaped clock: RX lead ≈ 12 slots ahead of playout (matches observed maxDisp≈10–16
 * while hole open). True seq loss + live edge running ahead of nextExpected.
 *
 * Strategies (harness-only):
 * - STRICT_NO_QIW
 * - CURRENT_P1_QIW (QUEUE_IN_WINDOW ≤16)
 * - BOUNDED_CATCHUP_STEP4 / STEP8 (QIW + consumer skip toward live-MAX_REORDER)
 */
class Profile01HoleRecoveryStrategyDeskSim {

    enum class Strategy {
        STRICT_NO_QIW,
        CURRENT_P1_QIW,
        BOUNDED_CATCHUP_STEP4,
        BOUNDED_CATCHUP_STEP8,
    }

    data class Metrics(
        val strategy: Strategy,
        val scenario: String,
        val real: Int,
        val plc: Int,
        val skipped: Int,
        val reorderReject: Int,
        val qiwQueued: Int,
        val maxDepth: Int,
        val sumPullLagMs: Long,
        val realLagSamples: Int,
    ) {
        val plcPct: Double get() = if (real + plc == 0) 0.0 else 100.0 * plc / (real + plc)
        val meanPullLagMs: Double
            get() = if (realLagSamples == 0) 0.0 else sumPullLagMs.toDouble() / realLagSamples
    }

    private data class Acc(
        var real: Int = 0,
        var plc: Int = 0,
        var skipped: Int = 0,
        var reorderReject: Int = 0,
        var qiwQueued: Int = 0,
        var maxDepth: Int = 0,
        var sumPullLagMs: Long = 0,
        var realLagSamples: Int = 0,
    )

    /** RX delivers this many slots ahead of the playout cursor tick (field-like). */
    private val rxLeadSlots = 12L

    @Test
    fun deskMatrix_shortBurstReorderSustained_reportsBenefitTradeoff() {
        val scenarios =
            listOf(
                Scenario("short_loss_1", lossRuns = listOf(40..40)),
                Scenario("burst_loss_4", lossRuns = listOf(80..83)),
                Scenario("burst_loss_8", lossRuns = listOf(120..127)),
                Scenario("two_bursts", lossRuns = listOf(50..52, 150..157)),
                Scenario("sustained_sparse", lossRuns = (0 until 20).map { (40 + it * 8)..(40 + it * 8) }),
                Scenario("reorder_only_lead", lossRuns = emptyList()),
            )

        val rows = mutableListOf<Metrics>()
        for (sc in scenarios) {
            for (st in Strategy.values()) {
                rows += runSim(st, sc)
            }
        }

        val header =
            "%-22s %-22s %6s %6s %6s %7s %7s %7s %8s %10s".format(
                "scenario",
                "strategy",
                "plc%",
                "plc",
                "real",
                "skip",
                "rej",
                "qiw",
                "maxDep",
                "lagMs",
            )
        val out =
            java.io.File("build/reports/hole-recovery-strategy-desk-matrix.txt")
        out.parentFile?.mkdirs()
        val body =
            buildString {
                appendLine(header)
                for (m in rows) {
                    appendLine(
                        "%-22s %-22s %6.1f %6d %6d %7d %7d %7d %8d %10.1f".format(
                            m.scenario,
                            m.strategy.name,
                            m.plcPct,
                            m.plc,
                            m.real,
                            m.skipped,
                            m.reorderReject,
                            m.qiwQueued,
                            m.maxDepth,
                            m.meanPullLagMs,
                        ),
                    )
                }
            }
        out.writeText(body)
        println(body)
        println("MATRIX_WRITTEN ${out.absolutePath}")

        val burst8 = rows.filter { it.scenario == "burst_loss_8" }
        val base = burst8.single { it.strategy == Strategy.CURRENT_P1_QIW }
        val step4 = burst8.single { it.strategy == Strategy.BOUNDED_CATCHUP_STEP4 }
        val strict = burst8.single { it.strategy == Strategy.STRICT_NO_QIW }

        assertTrue(
            "field-shaped burst must create REORDER pressure (rej=${base.reorderReject} qiw=${base.qiwQueued})",
            base.reorderReject + base.qiwQueued > 0,
        )
        assertTrue(
            "CATCHUP_STEP4 should reduce PLC on burst_loss_8 (base=${base.plc} step4=${step4.plc})",
            step4.plc < base.plc,
        )
        assertTrue(
            "STRICT should not beat P1_QIW PLC (strict=${strict.plc} qiw=${base.plc})",
            strict.plc >= base.plc,
        )
        assertTrue(
            "CATCHUP_STEP4 lag regression (base=${base.meanPullLagMs} step4=${step4.meanPullLagMs})",
            step4.meanPullLagMs <= base.meanPullLagMs + 40.0,
        )
        assertTrue(
            "CATCHUP_STEP4 depth blow-up ${step4.maxDepth}",
            step4.maxDepth <= SessionMediaLiveEdgeAligner.IN_WINDOW_SLOTS + 8,
        )
        // Skip is the discontinuity cost — must be >0 if PLC dropped.
        assertTrue(
            "PLC cut without skip would be magic (skip=${step4.skipped} plcBase=${base.plc} plcStep4=${step4.plc})",
            step4.skipped > 0 || step4.plc == base.plc,
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
        for (r in scenario.lossRuns) {
            for (s in r) lost += s.toLong()
        }
        val acc = Acc()
        val slotMs = MediaJitterConstants.MEDIA_SLOT_MS
        val baseMs = 1_000_000L
        var lastAdmittedTx = -1L

        fun mediaTime(slot: Long) = baseMs + slot * slotMs

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
            when (val d = buf.admit(frame, nowMs)) {
                FrameAdmitDisposition.QUEUED -> Unit
                FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED -> {
                    acc.reorderReject += 1
                    if (strategy != Strategy.STRICT_NO_QIW) {
                        val expected = before ?: return
                        val disp = slot - expected
                        if (disp <= SessionMediaLiveEdgeAligner.IN_WINDOW_SLOTS) {
                            buf.queueWithinPlayoutWindow(frame, recoverableHoleSlot = expected)
                            acc.qiwQueued += 1
                        }
                    }
                }
                else -> Unit
            }
            acc.maxDepth = maxOf(acc.maxDepth, buf.size())
        }

        // Prime cursor.
        tryAdmit(0L, baseMs)
        lastAdmittedTx = 0L
        buf.advanceExpectedTo(0L)

        for (tick in 0 until scenario.totalPlayoutTicks) {
            val nowMs = baseMs + tick * slotMs
            val deliverThrough = tick + rxLeadSlots
            while (lastAdmittedTx < deliverThrough) {
                val s = lastAdmittedTx + 1L
                lastAdmittedTx = s
                if (s !in lost) {
                    tryAdmit(s, nowMs)
                }
            }

            var next = buf.nextExpected() ?: 0L
            val live = buf.latestBufferedSlot()
            val hole = buf.peekFrame(next) == null
            if (hole &&
                live != null &&
                live > next &&
                (strategy == Strategy.BOUNDED_CATCHUP_STEP4 || strategy == Strategy.BOUNDED_CATCHUP_STEP8)
            ) {
                val gap = live - next
                if (gap > MediaJitterConstants.MAX_REORDER_PACKETS) {
                    val step = if (strategy == Strategy.BOUNDED_CATCHUP_STEP4) 4L else 8L
                    val target =
                        minOf(
                            live - MediaJitterConstants.MAX_REORDER_PACKETS.toLong(),
                            next + step,
                        ).coerceAtLeast(next)
                    if (target > next) {
                        acc.skipped += (target - next).toInt()
                        buf.advanceExpectedTo(target)
                        next = target
                    }
                }
            }

            next = buf.nextExpected() ?: next
            val frame = buf.takeFrame(next)
            if (frame != null) {
                acc.real += 1
                acc.sumPullLagMs += (nowMs - frame.mediaTimeMs).coerceAtLeast(0L)
                acc.realLagSamples += 1
                buf.advanceExpectedTo(next + 1L)
            } else if (tick > 0) {
                acc.plc += 1
                buf.advanceExpectedTo(next + 1L)
            }
            acc.maxDepth = maxOf(acc.maxDepth, buf.size())
        }

        return Metrics(
            strategy = strategy,
            scenario = scenario.name,
            real = acc.real,
            plc = acc.plc,
            skipped = acc.skipped,
            reorderReject = acc.reorderReject,
            qiwQueued = acc.qiwQueued,
            maxDepth = acc.maxDepth,
            sumPullLagMs = acc.sumPullLagMs,
            realLagSamples = acc.realLagSamples,
        )
    }
}

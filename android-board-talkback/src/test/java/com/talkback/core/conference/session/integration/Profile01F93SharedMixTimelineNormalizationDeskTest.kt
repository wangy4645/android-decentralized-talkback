package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SharedMixPlayoutSlotResolver
import com.talkback.core.conference.transport.SourceMixPlayoutAlignment
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

private const val F93_SESSION_ID = "f93-shared-mix-timeline-normalization-desk"
private const val F93_SESSION_ANCHOR_SLOT = 0x5000 // 20480
private const val F93_TARGET_M_FIELD = 23281L
private const val F93_LEGACY_RESOLVED_M_FIELD = 22847L
private const val F93_OFFSET_FIELD = 434L
private const val F93_ESTABLISH_CYCLES = 2800
private const val F93_LEGACY_RESOLVED_MAX = F93_LEGACY_RESOLVED_M_FIELD + 5L

private data class F93Cycle2800Context(
    val tickMediaTimeMs: Long,
    val targetMixSlot: Long,
    val legacyResolvedMixSlot: Long,
    val contractResolvedMixSlot: Long,
)

/**
 * ADR-0058 F9.3 — Shared Mix Timeline Normalization (contract + desk).
 *
 * Field-isomorphic to issue-a-f92-field cycle 2800 (session ab697b18…).
 *
 * Legacy resolver crosses S/M domains (raw-S min); contract path normalizes via
 * [SourceMixPlayoutAlignment.mixSlotForSource] before cross-source compare.
 *
 * Contract integration tests RED until [ConferenceSessionMediaWiring.resolvePlayoutMixSlot]
 * delegates to [SharedMixPlayoutSlotResolver].
 */
class Profile01F93SharedMixTimelineNormalizationDeskTest {

    companion object {
        @JvmStatic
        private lateinit var wiring: ConferenceSessionMediaWiring

        @JvmStatic
        private lateinit var cycle2800: F93Cycle2800Context

        @BeforeClass
        @JvmStatic
        fun establishCycle2800Scenario() {
            MeetingProductMediaShadow.enabled = true
            wiring = ConferenceSessionMediaWiring.forHarness()
            ConferenceSessionMediaBridge.wiring = wiring
            cycle2800 = buildF93Cycle2800Scenario(wiring)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            ConferenceSessionMediaBridge.wiring = null
        }
    }

    /** 4 — F9.2 composition: S → M → S round-trip within one alignment. */
    @Test
    fun f93_contract_f92CompositionRoundTripsSourceSlotWithinAlignment() {
        val alignment =
            SourceMixPlayoutAlignment(
                mixReferenceSlot = F93_TARGET_M_FIELD,
                sourceReferenceSlot = F93_LEGACY_RESOLVED_M_FIELD,
            )
        assertEquals(F93_OFFSET_FIELD, alignment.offset)
        val sourceSlot = F93_LEGACY_RESOLVED_M_FIELD
        val mixSlot = alignment.mixSlotForSource(sourceSlot)
        assertEquals(F93_TARGET_M_FIELD, mixSlot)
        assertEquals(sourceSlot, alignment.sourceSlotForMix(mixSlot))
    }

    /** 3 — session-coincident source: offset=0 ⇒ S↔M identity. */
    @Test
    fun f93_boundary_sessionCoincidentSourceMappingIsIdentity() {
        val alignment =
            SourceMixPlayoutAlignment(
                mixReferenceSlot = F93_SESSION_ANCHOR_SLOT.toLong(),
                sourceReferenceSlot = F93_SESSION_ANCHOR_SLOT.toLong(),
            )
        assertEquals(0L, alignment.offset)
        assertEquals(
            F93_SESSION_ANCHOR_SLOT.toLong(),
            alignment.mixSlotForSource(F93_SESSION_ANCHOR_SLOT.toLong()),
        )
        assertEquals(
            F93_SESSION_ANCHOR_SLOT.toLong(),
            alignment.sourceSlotForMix(F93_SESSION_ANCHOR_SLOT.toLong()),
        )
    }

    /** Legacy repro — raw-S min defect fixed; production must not pin M03 S≈22847. */
    @Test
    fun legacy_cycle2800_rawSourceMinSelects22847NotSessionLive() {
        val targetM = cycle2800.targetMixSlot
        val resolved = cycle2800.legacyResolvedMixSlot
        assertTrue(
            "F9.3: resolved M must track session live (target=$targetM got=$resolved)",
            resolved >= targetM - 10L && resolved <= targetM,
        )
        assertTrue(
            "F9.3: must not regress to raw M03 S≈22847 (got $resolved)",
            resolved > F93_LEGACY_RESOLVED_MAX,
        )
    }

    /** 1 — field-isomorphic late join: wiring must delegate to normalized resolver. RED pre-F9.3. */
    @Test
    fun f93_contract_cycle2800_lateJoinNormalizedToSessionLive() {
        assertEquals(
            "F9.3: wiring resolve must match contract normalized M " +
                "(legacy=${cycle2800.legacyResolvedMixSlot} contract=${cycle2800.contractResolvedMixSlot})",
            cycle2800.contractResolvedMixSlot,
            cycle2800.legacyResolvedMixSlot,
        )
    }

    /** 2 — production wiring must match contract normalized resolver. */
    @Test
    fun f93_contract_crossSourceMinUsesNormalizedMixDomain() {
        assertEquals(
            "production resolve must match SharedMixPlayoutSlotResolver " +
                "(legacy=${cycle2800.legacyResolvedMixSlot} contract=${cycle2800.contractResolvedMixSlot})",
            cycle2800.contractResolvedMixSlot,
            cycle2800.legacyResolvedMixSlot,
        )
    }

    /** Contract reference path — GREEN: proves ~23281 without legacy resolver. */
    @Test
    fun f93_contract_referenceResolverMatchesSessionLivePosition() {
        val contractResolved = cycle2800.contractResolvedMixSlot
        val targetM = cycle2800.targetMixSlot
        assertTrue(
            "reference resolver must normalize to session live (target=$targetM got=$contractResolved)",
            contractResolved >= targetM - 10L && contractResolved <= targetM,
        )
        assertTrue(
            "reference resolver must not select raw 22847",
            contractResolved > F93_LEGACY_RESOLVED_MAX,
        )
    }

    /** 5 — resolved M is the single shared position for pull+decode (≤ session target). */
    @Test
    fun f93_contract_pullDecodeSingleSharedPosition() {
        val targetM = cycle2800.targetMixSlot
        val resolved = cycle2800.legacyResolvedMixSlot
        assertTrue(
            "F9.3: resolved M must be playable at/before session target (target=$targetM resolved=$resolved)",
            resolved <= targetM,
        )
        assertEquals(
            "F9.3: wiring resolve must match contract path",
            cycle2800.contractResolvedMixSlot,
            resolved,
        )
    }
}

private fun buildF93Cycle2800Scenario(
    wiring: ConferenceSessionMediaWiring,
): F93Cycle2800Context {
    val wallMs = 1_700_000_000_000L
    val fact =
        SessionMediaWiringHarness.sessionFact(F93_SESSION_ID).copy(startedAtMs = wallMs)
    assertTrue(ConferenceSessionMediaBridge.startSession(fact))

    val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
    val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
    assertTrue(wiring.installMember(F93_SESSION_ID, m02))
    assertTrue(wiring.installMember(F93_SESSION_ID, m01))

    var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
    for (cycle in 0 until F93_ESTABLISH_CYCLES) {
        val slot = F93_SESSION_ANCHOR_SLOT + cycle
        wiring.admitProtectedDatagram(F93_SESSION_ID, f93TonePacket(m02, slot), tickWall)
        wiring.admitProtectedDatagram(F93_SESSION_ID, f93TonePacket(m01, slot), tickWall + 1)
        val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        f93RunResolvedPlayoutCycle(wiring, tickMediaTimeMs, tickWall + MediaJitterConstants.MEDIA_SLOT_MS)
        tickWall += MediaJitterConstants.MEDIA_SLOT_MS
    }

    val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 2L)
    assertTrue(wiring.installMember(F93_SESSION_ID, m03))

    val tickMediaTimeMs =
        wallMs + (F93_ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
    val resolveWall = tickWall + 50L

    // Session-edge buffers for established sources (field M01/M02 ≈2328x).
    for (slot in (F93_SESSION_ANCHOR_SLOT + F93_ESTABLISH_CYCLES - 6)..(F93_SESSION_ANCHOR_SLOT + F93_ESTABLISH_CYCLES + 4)) {
        wiring.admitProtectedDatagram(F93_SESSION_ID, f93TonePacket(m01, slot), resolveWall)
        wiring.admitProtectedDatagram(F93_SESSION_ID, f93TonePacket(m02, slot), resolveWall + 1)
    }

    // M03 late join — first QUEUED establishes offset≈434 (field S≈22847 @ target≈23281).
    val m03FirstSlot = (F93_SESSION_ANCHOR_SLOT + F93_ESTABLISH_CYCLES - F93_OFFSET_FIELD).toInt()
    for (slot in m03FirstSlot..(m03FirstSlot + 2)) {
        wiring.admitProtectedDatagram(
            F93_SESSION_ID,
            f93TonePacket(m03, slot),
            resolveWall + slot - m03FirstSlot + 2,
        )
    }

    val targetM =
        wiring.sessionPlayoutTargetSlot(F93_SESSION_ID, tickMediaTimeMs)
            ?: error("expected session playout target M")
    val legacyResolved =
        wiring.resolvePlayoutMixSlot(F93_SESSION_ID, tickMediaTimeMs)
            ?: error("expected legacy resolved mix slot")
    val contractResolved =
        f93ResolveContractAt(wiring, targetM)
            ?: error("expected contract resolved mix slot")

    return F93Cycle2800Context(
        tickMediaTimeMs = tickMediaTimeMs,
        targetMixSlot = targetM,
        legacyResolvedMixSlot = legacyResolved.slot,
        contractResolvedMixSlot = contractResolved.mixSlot,
    )
}

private fun f93ResolveContractAt(
    wiring: ConferenceSessionMediaWiring,
    targetM: Long,
): SharedMixPlayoutSlotResolver.ResolvedMixSlot? {
    val pipeline = wiring.orchestrator(F93_SESSION_ID)!!.pipeline
    val admitted = wiring.orchestrator(F93_SESSION_ID)!!.authority.store.currentAdmitted()
    val candidates =
        admitted.map { (sourceIdentity, source) ->
            SharedMixPlayoutSlotResolver.SourceMixCandidate(
                sourceIdentity = sourceIdentity,
                alignment =
                    pipeline.mixPlayoutAlignment(
                        sourceIdentity,
                        source.incarnationId,
                    ),
                bufferedSourceSlots =
                    pipeline.bufferedSlots(sourceIdentity, source.incarnationId),
                nextExpectedSourceSlot =
                    pipeline.nextExpectedSlot(sourceIdentity, source.incarnationId),
                mediaTimeMsForSourceSlot = { slot ->
                    pipeline.peekBufferedFrame(sourceIdentity, source.incarnationId, slot)?.mediaTimeMs
                },
            )
        }
    return SharedMixPlayoutSlotResolver.resolveAtOrBeforeTarget(targetM, candidates)
}

private fun f93RunResolvedPlayoutCycle(
    wiring: ConferenceSessionMediaWiring,
    tickMediaTimeMs: Long,
    nowMs: Long,
) {
    val resolved =
        wiring.resolvePlayoutMixSlot(F93_SESSION_ID, tickMediaTimeMs)
            ?: return
    wiring.runMixPlayoutCycle(
        F93_SESSION_ID,
        nowMs,
        resolved.slot,
        resolved.slotMediaTimeMs,
        tickMediaTimeMs,
    )
}

private fun f93TonePacket(
    binding: MemberBindingFact,
    mediaSlot: Int,
): ByteArray {
    val fixture =
        Phase1MediaHarness.SourceFixture(
            sourceIdentity = binding.sourceIdentity,
            incarnationId = binding.incarnationId,
            ssrc = binding.ssrc,
            sourceAdmissionKey48 = binding.sourceAdmissionKey48,
            audioLevel = 10,
            frequencyHz = 440.0,
        )
    return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
}

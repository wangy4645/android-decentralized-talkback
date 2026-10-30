package com.talkback.core.conference.authority

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.ExecutionFenceState
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.wire.WireIngressResult
import com.talkback.core.conference.wire.WireOwningSeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2b-07 — P01 Fact/key wiring acceptance (A01–A05).
 * Does NOT claim cryptographic Fact verification PASS.
 */
class Profile01E2b07AuthorityWiringHarnessTest {
    @Test
    fun a01_packetP02P03_cannotCreateAuthorityOrDerivedCapability() {
        val rt = AuthorityWiringRuntime()
        // P03 observation without verified facts
        assertFalse(
            rt.selection.observeVoice(
                VoiceLevelObservation("ghost", 1L, true, 10),
            ),
        )
        assertNull(rt.store.derivedAdmitted("ghost"))
        assertNull(rt.store.derivedWire("ghost"))

        // P02 admit without derived capability
        val rejected =
            rt.admitWire(
                "ghost",
                byteArrayOf(0x90.toByte(), 0x6f, 0, 0),
            )
        assertTrue(rejected is WireIngressResult.Rejected)
        assertEquals(
            WireOwningSeam.Q3,
            (rejected as WireIngressResult.Rejected).owningSeam,
        )

        // Store still empty — no self-authorization
        assertTrue(rt.store.currentAdmitted().isEmpty())
        assertTrue(rt.store.currentWireCapabilities().isEmpty())
        assertTrue(rt.store.verifiedFactSeam().currentSources().isEmpty())
    }

    @Test
    fun a02_sourceRevoke_immediateFence_cannotReexecute() {
        val rt = AuthorityWiringRuntime()
        installEpochSource(rt, identity = "S1", incarnation = 7L, epoch = 7L)
        rt.syncAdmittedToRuntime("S1")
        rt.selection.observeVoice(VoiceLevelObservation("S1", 7L, true, 20))
        rt.selection.selectTopK(0L)
        assertTrue(rt.selection.isDecodeEligible("S1", 7L))
        assertNotNull(rt.store.derivedWire("S1"))

        assertTrue(rt.revokeSource("S1", 7L))

        assertNull(rt.store.derivedWire("S1"))
        assertNull(rt.store.derivedAdmitted("S1"))
        assertEquals(
            ExecutionFenceState.HARD_FENCED,
            rt.selection.registry.get("S1")!!.fence,
        )
        assertFalse(rt.selection.isDecodeEligible("S1", 7L))
        // Cannot regain executability without new verified facts
        assertNull(rt.wireIngressContext("S1"))
    }

    @Test
    fun a03_keyRetirement_oldWireAndRuntimeFenced() {
        val rt = AuthorityWiringRuntime()
        installEpochSource(rt, "S1", incarnation = 7L, epoch = 7L)
        rt.syncAdmittedToRuntime("S1")
        assertNotNull(rt.store.derivedWire("S1"))

        assertEquals(1, rt.retireMediaKeyEpoch(7L))

        assertNull(rt.store.derivedWire("S1"))
        assertFalse(
            rt.store.verifiedFactSeam().allKeys()[7L]!!.current,
        )
        assertEquals(
            ExecutionFenceState.HARD_FENCED,
            rt.selection.registry.get("S1")!!.fence,
        )
        assertFalse(rt.selection.isDecodeEligible("S1", 7L))
    }

    @Test
    fun a04_newEpoch_freshCapability_noOldStateInheritance() {
        val rt = AuthorityWiringRuntime()
        installEpochSource(rt, "S1", incarnation = 7L, epoch = 7L, keyByte = 0x07)
        rt.syncAdmittedToRuntime("S1")
        rt.selection.observeVoice(VoiceLevelObservation("S1", 7L, true, 20))
        rt.selection.selectTopK(0L)

        // Queue epoch-7 work then retire
        val base = 50_000L
        assertEquals(
            com.talkback.core.conference.runtime.FrameAdmitDisposition.QUEUED,
            rt.pipeline.admitFrame(
                AdmittedMediaFrame("S1", 7L, 0, base, base + 1),
                base + 1,
            ),
        )

        rt.retireMediaKeyEpoch(7L)
        assertEquals(
            ExecutionFenceState.HARD_FENCED,
            rt.selection.registry.get("S1")!!.fence,
        )

        // Fresh Epoch 8 — new incarnation, new key material
        installEpochSource(rt, "S1", incarnation = 8L, epoch = 8L, keyByte = 0x08)
        rt.syncAdmittedToRuntime("S1")
        val admitted8 = rt.currentAdmitted("S1")!!
        assertEquals(8L, admitted8.incarnationId)
        assertNotNull(rt.store.derivedWire("S1"))
        assertEquals(8L, rt.store.derivedWire("S1")!!.mediaKeyEpoch)
        // Key material differs from epoch 7
        assertEquals(0x08.toByte(), rt.store.derivedWire("S1")!!.key.masterKey[0])

        rt.selection.observeVoice(VoiceLevelObservation("S1", 8L, true, 20))
        rt.selection.selectTopK(0L)
        assertTrue(rt.selection.isDecodeEligible("S1", 8L))
        assertFalse(rt.selection.isDecodeEligible("S1", 7L))

        // Old queued epoch-7 work remains non-executable
        val d = rt.pipeline.pullSlot("S1", 7L, 0, base, base + 50)
        assertEquals(SlotPullDisposition.FENCED_SKIP, d)
        assertEquals(0, rt.pipeline.decodeCount)
    }

    @Test
    fun a05_revocationRace_queuedFrame_noDecodeNoPlc() {
        val rt = AuthorityWiringRuntime()
        installEpochSource(rt, "S1", incarnation = 7L, epoch = 7L)
        rt.syncAdmittedToRuntime("S1")
        rt.selection.observeVoice(VoiceLevelObservation("S1", 7L, true, 20))
        rt.selection.selectTopK(0L)

        val base = 60_000L
        assertEquals(
            com.talkback.core.conference.runtime.FrameAdmitDisposition.QUEUED,
            rt.pipeline.admitFrame(
                AdmittedMediaFrame("S1", 7L, 0, base, base + 1),
                base + 1,
            ),
        )
        assertEquals(1, rt.pipeline.jitterSize("S1", 7L))

        assertTrue(rt.revokeSource("S1", 7L))

        val now = base + 50
        val d = rt.pipeline.pullSlot("S1", 7L, 0, base, now)
        assertEquals(SlotPullDisposition.FENCED_SKIP, d)
        assertEquals(0, rt.pipeline.decodeCount)
        assertEquals(0, rt.pipeline.plcCount)

        // Past deadline still no PLC resurrection
        val late = base + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        assertEquals(
            SlotPullDisposition.FENCED_SKIP,
            rt.pipeline.pullSlot("S1", 7L, 0, base, late),
        )
        assertEquals(0, rt.pipeline.plcCount)
    }

    private fun installEpochSource(
        rt: AuthorityWiringRuntime,
        identity: String,
        incarnation: Long,
        epoch: Long,
        keyByte: Int = 0x01,
        ssrc: Int = 0x11223344,
    ) {
        val key =
            MediaKeyContextFact(
                mediaKeyEpoch = epoch,
                masterKey = ByteArray(16) { keyByte.toByte() },
                masterSalt = ByteArray(12) { 0x10 },
                keyContextHint64 = ByteArray(8) { 0xBB.toByte() },
                current = true,
            )
        val source =
            SourceAuthorizationFact(
                sourceIdentity = identity,
                incarnationId = incarnation,
                ssrc = ssrc,
                sourceAdmissionKey48 = byteArrayOf(0xfe.toByte(), 0x3e, 0x3a, 0x6c, 0xb2.toByte(), 0x14),
                mediaKeyEpoch = epoch,
                current = true,
            )
        rt.installNewEpochSource(source, key)
    }
}

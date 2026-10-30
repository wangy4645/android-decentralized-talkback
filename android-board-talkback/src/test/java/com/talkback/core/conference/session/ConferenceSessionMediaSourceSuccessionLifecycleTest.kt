package com.talkback.core.conference.session

import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.session.integration.ShadowHook
import com.talkback.core.conference.session.integration.ShadowOutcome
import com.talkback.core.conference.wire.WireIngressResult
import com.talkback.core.conference.wire.WireOwningSeam
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceSessionMediaSourceSuccessionLifecycleTest {
    private val sessionId = "sess-sr5-succession"
    private val conferenceId = sessionId
    private val moduleId = "M01"
    private val registry = ConferenceSessionMediaControlFactRegistry()
    private val port = ControlPlaneConferenceSessionMediaFactPort(registry)
    private val wiring = ConferenceSessionMediaWiring.forHarness()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        ConferenceSessionMediaBridge.wiring = wiring
        ConferenceSessionMediaCoordinatorDelegate.factPort = port

        val session =
            ConferenceMediaSessionControlFact(
                conferenceId = conferenceId,
                channelId = "ch-1",
                conferenceEpoch = 1L,
                membershipVersion = 1L,
                mediaKeyEpoch = 1L,
                endpoint = SessionMediaWiringHarness.sessionFact(sessionId).endpoint,
                networkInterfaceName = SessionMediaWiringHarness.sessionFact(sessionId).networkInterfaceName,
                masterKey = SessionMediaWiringHarness.sessionFact(sessionId).masterKey.copyOf(),
                masterSalt = SessionMediaWiringHarness.sessionFact(sessionId).masterSalt.copyOf(),
                keyContextHint64 = SessionMediaWiringHarness.sessionFact(sessionId).keyContextHint64.copyOf(),
                factGeneration = 1L,
            )
        registry.publishSession(session)
        val sessionFact = port.sessionFact(sessionId, "ch-1", rosterEpoch = 1L)!!
        assertTrue(wiring.startSession(sessionFact))
        Profile01ShadowRuntimeObservability.bindActiveSession(
            sessionId = sessionId,
            conferenceId = "ch-1",
            mediaKeyEpoch = 1L,
        )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.factPort = null
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun resolver_replacePending_defersRegardlessOfCatalog() {
        assertEquals(
            SourceSuccessionMediaReadyDecision.DEFER_REPLACE_PENDING,
            ConferenceSessionMediaSourceSuccessionLifecycle.resolveMediaReadyDecision(
                bindingIncarnation = 2L,
                catalogIncarnation = null,
                replacePending = true,
                bindingMediaKeyEpoch = 1L,
                sessionMediaKeyEpoch = 1L,
            ),
        )
    }

    @Test
    fun resolver_staleGeneration_rejects() {
        assertEquals(
            SourceSuccessionMediaReadyDecision.REJECT_STALE_GENERATION,
            ConferenceSessionMediaSourceSuccessionLifecycle.resolveMediaReadyDecision(
                bindingIncarnation = 1L,
                catalogIncarnation = 2L,
                replacePending = false,
                bindingMediaKeyEpoch = 1L,
                sessionMediaKeyEpoch = 1L,
            ),
        )
    }

    @Test
    fun resolver_sameGeneration_isAlreadyInstalled() {
        assertEquals(
            SourceSuccessionMediaReadyDecision.ALREADY_INSTALLED,
            ConferenceSessionMediaSourceSuccessionLifecycle.resolveMediaReadyDecision(
                bindingIncarnation = 2L,
                catalogIncarnation = 2L,
                replacePending = false,
                bindingMediaKeyEpoch = 1L,
                sessionMediaKeyEpoch = 1L,
            ),
        )
    }

    @Test
    fun g1Installed_g2Succession_replaceBeforeReady_revokesG1() {
        publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000101, keySuffix = 0x11)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        assertEquals(1L, catalogIncarnation())

        publishMember(incarnationId = 2L, factGeneration = 2L, ssrc = 0x22000102, keySuffix = 0x22)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        assertEquals(2L, catalogIncarnation())
        assertLastOutcome(ShadowHook.MEMBER_REPLACED, ShadowOutcome.APPLIED)
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, ShadowOutcome.APPLIED)
        assertFalse(oldGenerationExecutable(1L))
        assertTrue(newGenerationExecutable(2L))

        val stalePacket = memberPacket(incarnationId = 1L, ssrc = 0x22000101, keySuffix = 0x11)
        val reject = wiring.admitDatagram(sessionId, stalePacket)
        assertTrue(reject is WireIngressResult.Rejected)
        assertEquals(WireOwningSeam.Q3, (reject as WireIngressResult.Rejected).owningSeam)
    }

    @Test
    fun replacementPending_mediaReadyMustNotBypassInstall() {
        publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000111, keySuffix = 0x31)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        publishMember(incarnationId = 2L, factGeneration = 2L, ssrc = 0x22000112, keySuffix = 0x32)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        assertEquals(1L, catalogIncarnation())
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, ShadowOutcome.DEFERRED_REPLACE_PENDING)
        assertTrue(port.memberReplacePending(sessionId, moduleId))
    }

    @Test
    fun mediaReadyAfterReplace_isIdempotent() {
        publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000121, keySuffix = 0x41)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        publishMember(incarnationId = 2L, factGeneration = 2L, ssrc = 0x22000122, keySuffix = 0x42)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        assertEquals(2L, catalogIncarnation())
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, ShadowOutcome.APPLIED)
    }

    @Test
    fun sameGenerationReplay_doesNotTriggerReplace() {
        publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000131, keySuffix = 0x51)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        val republish = publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000131, keySuffix = 0x51)
        assertEquals(ControlFactPublishOutcome.IDEMPOTENT, republish)

        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        assertEquals(1L, catalogIncarnation())
        assertLastOutcome(ShadowHook.MEMBER_REPLACED, ShadowOutcome.DEFERRED_NO_REPLACE_PAIR)
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, ShadowOutcome.APPLIED)
    }

    @Test
    fun staleG1_mustNotOverwriteInstalledG2() {
        publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000141, keySuffix = 0x61)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        publishMember(incarnationId = 2L, factGeneration = 2L, ssrc = 0x22000142, keySuffix = 0x62)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)

        val stalePort =
            object : ConferenceSessionMediaFactPort by port {
                override fun memberBinding(
                    sessionId: String,
                    moduleId: String,
                ): MemberBindingFact? =
                    SessionMediaWiringHarness.memberBinding(
                        moduleId,
                        incarnationId = 1L,
                        ssrc = 0x22000141,
                        admissionKeySuffix = 0x61,
                    )

                override fun memberReplacePending(
                    sessionId: String,
                    moduleId: String,
                ): Boolean = false
            }
        ConferenceSessionMediaCoordinatorDelegate.factPort = stalePort
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        assertEquals(2L, catalogIncarnation())
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, ShadowOutcome.WIRING_REJECTED)
    }

    @Test
    fun replaceFailure_mustNotBypassViaPlainInstall() {
        publishMember(incarnationId = 1L, factGeneration = 1L, ssrc = 0x22000151, keySuffix = 0x71)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)
        publishMember(incarnationId = 2L, factGeneration = 2L, ssrc = 0x22000152, keySuffix = 0x72)

        val wrongOldPort =
            object : ConferenceSessionMediaFactPort by port {
                override fun memberReplaceBinding(
                    sessionId: String,
                    moduleId: String,
                ): Pair<MemberBindingFact, MemberBindingFact>? {
                    val installed = port.memberBinding(sessionId, moduleId)!!
                    val successor =
                        SessionMediaWiringHarness.memberBinding(
                            moduleId,
                            incarnationId = 2L,
                            ssrc = 0x22000152,
                            admissionKeySuffix = 0x72,
                        )
                    val wrongOld =
                        installed.copy(
                            incarnationId = 99L,
                            ssrc = 0x22009999,
                        )
                    registry.consumeMemberReplace(conferenceId, moduleId)
                    return Pair(wrongOld, successor)
                }
            }
        ConferenceSessionMediaCoordinatorDelegate.factPort = wrongOldPort
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberReplaced(sessionId, moduleId)
        ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady(sessionId, moduleId)

        assertEquals(1L, catalogIncarnation())
        assertLastOutcome(ShadowHook.MEMBER_REPLACED, ShadowOutcome.WIRING_REJECTED)
        assertLastOutcome(ShadowHook.MEMBER_MEDIA_READY, ShadowOutcome.DEFERRED_REPLACE_PENDING)
    }

    private fun publishMember(
        incarnationId: Long,
        factGeneration: Long,
        ssrc: Int,
        keySuffix: Int,
    ): ControlFactPublishOutcome {
        val key = SessionMediaWiringHarness.memberBinding(moduleId, admissionKeySuffix = keySuffix).sourceAdmissionKey48
        return registry.publishMember(
            ConferenceMediaMemberControlFact(
                conferenceId = conferenceId,
                moduleId = moduleId,
                membershipIncarnationId = incarnationId,
                ssrc = ssrc,
                sourceAdmissionKey48 = key.copyOf(),
                mediaKeyEpoch = 1L,
                membershipVersion = 1L,
                factGeneration = factGeneration,
            ),
        )
    }

    private fun catalogIncarnation(): Long? =
        wiring.catalog(sessionId)?.get(moduleId)?.incarnationId

    private fun oldGenerationExecutable(incarnationId: Long): Boolean {
        val orch = wiring.orchestrator(sessionId)!!
        return orch.selection.registry.isInstalledExecutable(moduleId, incarnationId)
    }

    private fun newGenerationExecutable(incarnationId: Long): Boolean = oldGenerationExecutable(incarnationId)

    private fun memberPacket(
        incarnationId: Long,
        ssrc: Int,
        keySuffix: Int,
    ): ByteArray =
        SessionMediaWiringHarness.protectedPacket(
            SessionMediaWiringHarness.memberBinding(
                moduleId,
                incarnationId = incarnationId,
                ssrc = ssrc,
                admissionKeySuffix = keySuffix,
            ),
        )

    private fun assertLastOutcome(
        hook: ShadowHook,
        outcome: ShadowOutcome,
    ) {
        val snap = MeetingProductMediaShadow.observability.snapshot()
        val recent = snap["recentEvents"] as Map<*, *>
        assertEquals(outcome.name, recent["$hook:$sessionId:$moduleId"])
    }
}

package com.talkback.app

import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.InMemorySignalingHub
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B0 invariant:
 *
 * For a given conference channel:
 *
 * 1. authorityBeliefCount <= 1
 * 2. floorOwnerCount <= 1
 *
 * This suite intentionally provides skeleton coverage only.
 * Scenarios will be implemented incrementally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceAuthorityInvariantTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val hub = InMemorySignalingHub()
    private val channelId = "CH-B0-INVARIANT"
    private lateinit var nodeM01: TestTalkbackNode
    private lateinit var nodeM02: TestTalkbackNode
    private lateinit var nodeM03: TestTalkbackNode

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val peers = TestTalkbackNode.allPeers(m01 to 50301, m02 to 50302, m03 to 50303)
        nodeM01 = TestTalkbackNode(context, m01, 50301, hub, peers)
        nodeM02 = TestTalkbackNode(context, m02, 50302, hub, peers)
        nodeM03 = TestTalkbackNode(context, m03, 50303, hub, peers)
        nodeM01.start()
        nodeM02.start()
        nodeM03.start()
    }

    @After
    fun tearDown() {
        nodeM01.stop()
        nodeM02.stop()
        nodeM03.stop()
    }

    @Ignore("B0 skeleton")
    @Test
    fun host_leave_authorityAndFloorInvariantsHold() {
        // TODO: trigger scenario
        assertAuthorityFloorInvariants(listOf(nodeM01, nodeM02, nodeM03), channelId)
    }

    @Ignore("B0 skeleton")
    @Test
    fun participant_leave_authorityAndFloorInvariantsHold() {
        // TODO: trigger scenario
        assertAuthorityFloorInvariants(listOf(nodeM01, nodeM02, nodeM03), channelId)
    }

    @Ignore("B0 skeleton")
    @Test
    fun wifi_flap_authorityAndFloorInvariantsHold() {
        // TODO: trigger scenario
        assertAuthorityFloorInvariants(listOf(nodeM01, nodeM02, nodeM03), channelId)
    }

    @Ignore("B0 skeleton")
    @Test
    fun conference_teardown_authorityAndFloorInvariantsHold() {
        // TODO: trigger scenario
        assertAuthorityFloorInvariants(listOf(nodeM01, nodeM02, nodeM03), channelId)
    }

    @Ignore("B0 skeleton")
    @Test
    fun meeting_to_group_authorityAndFloorInvariantsHold() {
        // TODO: trigger scenario
        assertAuthorityFloorInvariants(listOf(nodeM01, nodeM02, nodeM03), channelId)
    }

    @Ignore("B0 skeleton")
    @Test
    fun group_to_meeting_authorityAndFloorInvariantsHold() {
        // TODO: trigger scenario
        assertAuthorityFloorInvariants(listOf(nodeM01, nodeM02, nodeM03), channelId)
    }
}

private fun authorityBeliefCount(
    nodes: List<TestTalkbackNode>,
    channelId: String
): Int = nodes
    .mapNotNull { it.runtime.testAuthorityBeliefModuleId(channelId) }
    .distinct()
    .size

private fun floorOwnerCount(
    nodes: List<TestTalkbackNode>,
    channelId: String
): Int = nodes
    .mapNotNull { it.runtime.sessionSnapshotForChannel(channelId)?.protocolFloorOwnerKey }
    .distinct()
    .size

private fun assertAuthorityFloorInvariants(
    nodes: List<TestTalkbackNode>,
    channelId: String
) {
    val beliefs = nodes.mapNotNull { it.runtime.testAuthorityBeliefModuleId(channelId) }.distinct()
    val beliefCount = beliefs.size
    assertTrue(
        "authorityBeliefCount must be <= 1 but was $beliefCount: $beliefs",
        beliefCount <= 1
    )

    val floorOwners = nodes
        .mapNotNull { it.runtime.sessionSnapshotForChannel(channelId)?.protocolFloorOwnerKey }
        .distinct()
    val ownerCount = floorOwners.size
    assertTrue(
        "floorOwnerCount must be <= 1 but was $ownerCount: $floorOwners",
        ownerCount <= 1
    )
}

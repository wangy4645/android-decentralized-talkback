package com.talkback.core.session

/**
 * Phase 4-2 harness — 8-node controlled lifecycle (no production / no Meeting runtime).
 */
class EightNodeControlledLifecycleHarness(
    maxRecoverySlots: Int = 2
) {
    val simulator = EightNodeLifecycleSimulator(maxRecoverySlots = maxRecoverySlots)

    fun establish(): EightNodeLifecycleSnapshot = simulator.establishEightNodeConference()

    fun leave(moduleId: String) = simulator.participantLeave(moduleId)

    fun rejoin(moduleId: String) = simulator.participantRejoin(moduleId)

    fun recover(vararg remotes: String) = simulator.coordinateRecovery(*remotes)

    fun failover(newAnchor: String) = simulator.performAnchorFailover(newAnchor)

    fun applyScript(events: List<EightNodeLifecycleScriptEvent>) = simulator.applyScript(events)

    fun adversarialBaseScript(
        leaveTarget: String = "M05",
        rejoinTarget: String = "M05"
    ) = listOf(
        EightNodeLifecycleScriptEvent(EightNodeLifecycleScriptKind.LEAVE, leaveTarget),
        EightNodeLifecycleScriptEvent(EightNodeLifecycleScriptKind.REJOIN, rejoinTarget),
        EightNodeLifecycleScriptEvent(
            EightNodeLifecycleScriptKind.STALE_RECOVERY_FACT,
            leaveTarget,
            staleAnchorEpoch = 100L
        ),
        EightNodeLifecycleScriptEvent(
            EightNodeLifecycleScriptKind.OLD_ANCHOR_OBSERVATION,
            staleAnchorEpoch = 99L
        ),
        EightNodeLifecycleScriptEvent(EightNodeLifecycleScriptKind.RECOVERY_REQUEST, "M07")
    )
}

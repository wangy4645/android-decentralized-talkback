package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Anchor epoch semantics: monotonic authority version for primary/backup roles.
 * Stale primaries (lower epoch) must demote after partition heal.
 */
object AnchorAuthority {
    data class View(
        val epoch: Long,
        val primary: ModuleId?,
        val backup: ModuleId?
    )

    fun shouldAcceptRemoteEpoch(localEpoch: Long, remoteEpoch: Long): Boolean =
        remoteEpoch >= localEpoch

    fun isStalePrimary(localModuleId: ModuleId, view: View): Boolean =
        view.primary == localModuleId && view.epoch > 0L

    fun shouldDemote(
        localModuleId: ModuleId,
        localEpoch: Long,
        localPrimary: ModuleId?,
        remoteEpoch: Long,
        remotePrimary: ModuleId?
    ): Boolean {
        if (remoteEpoch > localEpoch) return true
        if (remoteEpoch < localEpoch) return false
        if (remotePrimary == null || localPrimary == null) return false
        if (localModuleId != localPrimary) return false
        return remotePrimary != localPrimary
    }

    fun nextEpochAfterFailover(currentEpoch: Long): Long =
        (currentEpoch + 1L).coerceAtLeast(AnchorRanking.INITIAL_ANCHOR_EPOCH)

    fun mergeCanonical(
        localEpoch: Long,
        localPrimary: ModuleId?,
        localBackup: ModuleId?,
        remoteEpoch: Long,
        remotePrimary: ModuleId?,
        remoteBackup: ModuleId?
    ): View {
        if (remoteEpoch > localEpoch) {
            return View(remoteEpoch, remotePrimary, remoteBackup)
        }
        if (remoteEpoch < localEpoch) {
            return View(localEpoch, localPrimary, localBackup)
        }
        return View(
            localEpoch,
            remotePrimary ?: localPrimary,
            remoteBackup ?: localBackup
        )
    }
}

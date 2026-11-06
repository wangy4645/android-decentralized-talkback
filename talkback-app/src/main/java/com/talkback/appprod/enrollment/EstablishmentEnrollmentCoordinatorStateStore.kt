package com.talkback.appprod.enrollment

import java.io.File
import java.util.Properties

class EstablishmentEnrollmentCoordinatorStateStore(
    private val stateFile: File,
) {
    data class Snapshot(
        val phase: EnrollmentCoordinatorPhase,
        val lastTaskProfileRevision: Long?,
        val lastFailureCode: String?,
        val lastFailureDetail: String?,
    )

    fun load(): Snapshot? {
        if (!stateFile.isFile) return null
        val props = Properties()
        stateFile.inputStream().use { props.load(it) }
        val phase =
            runCatching {
                EnrollmentCoordinatorPhase.valueOf(props.getProperty("phase", ""))
            }.getOrNull() ?: return null
        val revision = props.getProperty("lastTaskProfileRevision")?.toLongOrNull()
        return Snapshot(
            phase = phase,
            lastTaskProfileRevision = revision,
            lastFailureCode = props.getProperty("lastFailureCode")?.takeIf { it.isNotBlank() },
            lastFailureDetail = props.getProperty("lastFailureDetail")?.takeIf { it.isNotBlank() },
        )
    }

    fun save(snapshot: Snapshot) {
        stateFile.parentFile?.mkdirs()
        val props =
            Properties().apply {
                setProperty("phase", snapshot.phase.name)
                snapshot.lastTaskProfileRevision?.let { setProperty("lastTaskProfileRevision", it.toString()) }
                snapshot.lastFailureCode?.let { setProperty("lastFailureCode", it) }
                snapshot.lastFailureDetail?.let { setProperty("lastFailureDetail", it) }
            }
        stateFile.outputStream().use { props.store(it, "enrollment-coordinator-state") }
    }
}

enum class EnrollmentCoordinatorPhase {
    LOCAL_IDENTITY_PENDING,
    AWAITING_ENROLLMENT,
    ENROLLMENT_SUBMITTED,
    LOCAL_ESTABLISHMENT_READY,
    ENROLLMENT_REJECTED,
    LOCAL_IDENTITY_FAILED,
}

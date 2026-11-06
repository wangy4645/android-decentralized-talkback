package com.talkback.appprod.enrollment

import android.content.Context
import com.talkback.core.session.gbc.trust.profile.establishment.LocalEstablishmentKeystoreIdentityStore

/**
 * App-scoped enrollment coordinator (recreated on each [com.talkback.appprod.runtime.TalkbackRuntimeManager.start]).
 */
object EstablishmentEnrollmentServices {
    @Volatile
    var coordinator: EstablishmentEnrollmentCoordinator? = null
        private set

    @Volatile
    var readinessGate: EstablishmentReadinessGate? = null
        private set

    fun attachCoordinator(coordinator: EstablishmentEnrollmentCoordinator) {
        this.coordinator = coordinator
        this.readinessGate = coordinator.readinessGateOrNull()
    }

    fun clear() {
        coordinator = null
        readinessGate = null
    }

    fun createCoordinator(
        context: Context,
        moduleId: String,
        keystoreStore: LocalEstablishmentKeystoreIdentityStore =
            EstablishmentEnrollmentRuntimeFactory.keystoreStore(),
    ): EstablishmentEnrollmentCoordinator =
        EstablishmentEnrollmentCoordinator(
            context = context.applicationContext,
            moduleId = moduleId,
            keystoreStore = keystoreStore,
        )
}

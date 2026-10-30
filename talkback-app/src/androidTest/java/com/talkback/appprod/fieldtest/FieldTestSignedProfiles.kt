package com.talkback.appprod.fieldtest

import androidx.test.platform.app.InstrumentationRegistry

object FieldTestSignedProfiles {
    fun loadSignedRevision(localModuleId: String): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val assetName = "ftph-signed-profiles/$localModuleId.bin"
        return context.assets.open(assetName).use { it.readBytes() }
    }
}

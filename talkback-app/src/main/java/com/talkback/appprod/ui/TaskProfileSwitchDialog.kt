package com.talkback.appprod.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.talkback.appprod.R
import com.talkback.appprod.data.TaskProfile

object TaskProfileSwitchDialog {
    fun show(
        context: Context,
        target: TaskProfile,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.task_profile_switch_confirm_title)
            .setMessage(
                context.getString(
                    R.string.task_profile_switch_confirm_message,
                    target.name
                )
            )
            .setPositiveButton(R.string.task_profile_switch) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

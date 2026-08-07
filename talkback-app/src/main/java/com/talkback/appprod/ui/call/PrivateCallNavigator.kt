package com.talkback.appprod.ui.call

import com.talkback.appprod.ui.MainActivity

/**
 * Single entry for outbound Private Call from any UI source.
 */
object PrivateCallNavigator {
    fun start(activity: MainActivity, launch: CallLaunchContext) {
        activity.startPrivateCall(launch)
    }
}

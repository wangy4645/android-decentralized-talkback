package com.talkback.core.runtime

import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.TalkbackSession

fun TalkbackSession.isForegroundSuspended(): Boolean =
    disposition == SessionDisposition.SUSPENDED || disposition == SessionDisposition.RESUMING

fun TalkbackSession.isForegroundActive(): Boolean =
    accepted && !isForegroundSuspended() && disposition != SessionDisposition.TERMINATED

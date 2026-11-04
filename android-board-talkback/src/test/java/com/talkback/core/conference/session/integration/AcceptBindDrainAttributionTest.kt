package com.talkback.core.conference.session.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptBindDrainAttributionTest {
    @Test
    fun scopedSpansAndDuplicateDetection() {
        val logs = mutableListOf<String>()
        AcceptBindDrainAttribution.resetForTest { logs.add(it) }
        AcceptBindDrainAttribution.scoped("sess-1", "trace-a") {
            AcceptBindDrainAttribution.span("DETACH", obligation = AcceptBindDrainAttribution.Obligation.RETENTION) {
                Thread.sleep(1)
            }
            assertFalse(
                AcceptBindDrainAttribution.noteItemKey(
                    AcceptBindDrainAttribution.itemKey(9, "abc", "M01", 1L, 2L),
                ),
            )
            assertTrue(
                AcceptBindDrainAttribution.noteItemKey(
                    AcceptBindDrainAttribution.itemKey(9, "abc", "M01", 1L, 2L),
                ),
            )
            AcceptBindDrainAttribution.onNestedDrainEnter(setOf(11))
        }
        AcceptBindDrainAttribution.resetForTest()
        assertTrue(logs.any { it.contains("ACCEPT_BIND_DRAIN_SPAN stage=DETACH") && it.contains("execMs=") })
        assertTrue(logs.any { it.contains("ACCEPT_BIND_DRAIN_DUP") })
        assertTrue(logs.any { it.contains("NESTED_DRAIN_ENTER") && it.contains("factTypes=11") })
        assertTrue(logs.any { it.contains("ACCEPT_BIND_DRAIN_SUMMARY") && it.contains("duplicateItems=1") })
        assertFalse(AcceptBindDrainAttribution.inAcceptBindDrain())
    }
}

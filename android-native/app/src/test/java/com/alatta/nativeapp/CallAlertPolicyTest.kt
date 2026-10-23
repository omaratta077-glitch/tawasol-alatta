package com.alatta.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAlertPolicyTest {
    @Test fun existingBackgroundNotificationIsNotPostedTwice() {
        assertEquals(CallAlertDecision.KEEP, callAlertDecision("call-a", "", "", true))
        assertEquals(CallAlertDecision.KEEP, callAlertDecision("call-a", "call-a", "", true))
    }
    @Test fun repostingACancelledBackgroundNotificationRemainsSilent() {
        assertEquals(CallAlertDecision.SILENT, callAlertDecision("call-a", "call-a", "", false))
    }
    @Test fun firstBackgroundInvitationRingsAndDuplicatesStayQuiet() {
        assertEquals(CallAlertDecision.RING, callAlertDecision("call-a", "", "", false))
        assertEquals(CallAlertDecision.KEEP, callAlertDecision("call-a", "call-a", "", true))
        assertEquals(CallAlertDecision.SILENT, callAlertDecision("call-a", "call-a", "", false))
    }
    @Test fun endedCallNeverRingsButANewCallDoes() {
        assertEquals(CallAlertDecision.IGNORE, callAlertDecision("call-a", "call-a", "call-a", false))
        assertEquals(CallAlertDecision.IGNORE, callAlertDecision("call-a", "call-a", "call-a", true))
        assertEquals(CallAlertDecision.RING, callAlertDecision("call-b", "call-a", "call-a", false))
    }
}

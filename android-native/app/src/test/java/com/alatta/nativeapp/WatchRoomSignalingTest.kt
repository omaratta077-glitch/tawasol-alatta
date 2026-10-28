package com.alatta.nativeapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WatchRoomSignalingTest {
    @Test fun completedPacketsCanBeAcknowledgedButFailedOrTimedOutPacketsStayRetryable() = runBlocking {
        assertTrue(watchRoomSignalAttempt { })
        assertFalse(watchRoomSignalAttempt { error("temporary SDP failure") })
        assertFalse(watchRoomSignalAttempt(timeoutMs = 20) { awaitCancellation() })
        assertTrue("The room coroutine still accepts the next packet", currentCoroutineContext().isActive)
        assertTrue(watchRoomSignalAttempt { })
    }
    @Test fun leavingCancelsPendingSignalWorkInsteadOfTreatingItAsARetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            watchRoomSignalAttempt { started.complete(Unit); awaitCancellation() }
            returned = true
        }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }
    @Test fun anOuterDeadlineIsNotSwallowedByThePacketDeadline() = runBlocking {
        var returned = false
        try {
            withTimeout(20) { watchRoomSignalAttempt { awaitCancellation() }; returned = true }
            fail("The enclosing deadline must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertFalse(returned)
    }
}

package com.alatta.nativeapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A packet deadline is retryable; leaving the room must still cancel its HTTP/SDP work immediately. */
internal suspend fun watchRoomSignalAttempt(timeoutMs: Long = 12000, action: suspend () -> Unit): Boolean = try {
    withTimeout(timeoutMs) { action() }
    true
} catch (_: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    false
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

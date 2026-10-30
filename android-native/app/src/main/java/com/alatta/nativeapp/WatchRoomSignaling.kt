package com.alatta.nativeapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** يمكن إعادة محاولة انتهاء مهلة الحزمة؛ ويجب أن تظل مغادرة الغرفة قادرة على إلغاء عمل HTTP/SDP فورًا. */
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

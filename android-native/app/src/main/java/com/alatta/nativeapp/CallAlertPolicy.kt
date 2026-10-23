package com.alatta.nativeapp

internal enum class CallAlertDecision { IGNORE, KEEP, SILENT, RING }

/** Deduplicate background call notifications; the visible prompt owns its local v145 ringtone. */
internal fun callAlertDecision(id: String, lastAlerted: String, ended: String, notificationPresent: Boolean): CallAlertDecision = when {
    id == ended -> CallAlertDecision.IGNORE
    notificationPresent -> CallAlertDecision.KEEP
    id == lastAlerted -> CallAlertDecision.SILENT
    else -> CallAlertDecision.RING
}

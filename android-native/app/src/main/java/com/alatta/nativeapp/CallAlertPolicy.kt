package com.alatta.nativeapp

internal enum class CallAlertDecision { IGNORE, KEEP, SILENT, RING }

/** The OS notification is the sole ringtone owner, including after an Activity launch. */
internal fun callAlertDecision(id: String, lastAlerted: String, ended: String, notificationPresent: Boolean): CallAlertDecision = when {
    id == ended -> CallAlertDecision.IGNORE
    notificationPresent -> CallAlertDecision.KEEP
    id == lastAlerted -> CallAlertDecision.SILENT
    else -> CallAlertDecision.RING
}

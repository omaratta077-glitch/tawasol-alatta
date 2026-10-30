package com.alatta.nativeapp

internal enum class CallAlertDecision { IGNORE, KEEP, SILENT, RING }

/** امنع تكرار إشعارات المكالمات في الخلفية؛ ويتولى مربع الاتصال الظاهر تشغيل نغمته المحلية v145. */
internal fun callAlertDecision(id: String, lastAlerted: String, ended: String, notificationPresent: Boolean): CallAlertDecision = when {
    id == ended -> CallAlertDecision.IGNORE
    notificationPresent -> CallAlertDecision.KEEP
    id == lastAlerted -> CallAlertDecision.SILENT
    else -> CallAlertDecision.RING
}

package com.alatta.nativeapp

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import org.json.JSONArray
import org.json.JSONObject

/** تسميات أخطاء ثابتة فقط: لا تسلسل نص الاستثناءات أو عناوين URL أو الترويسات أو أجسام الردود. */
internal data class SafeMediaFailure(val kinds: List<String>, val httpStatus: Int?, val ioReason: Int?, val dnsHost: String? = null)

/** أسماء DNS عامة فقط. ارفض عناوين URL ومعلومات المستخدم وعناوين IP والأسماء المحلية أو الخاصة. */
internal fun safeMediaHost(value: String?): String? {
    val host = value?.lowercase(java.util.Locale.ROOT)?.removeSuffix(".") ?: return null
    if (host.length !in 3..253 || !host.matches(Regex("""(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"""))) return null
    if (listOf("localhost", "local", "internal", "invalid", "test").any { host == it || host.endsWith(".$it") }) return null
    return host
}

private fun dnsFailureHost(message: String?): String? {
    val text = message ?: return null
    val quoted = Regex("""Unable to resolve host ["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
    val bare = Regex("^([A-Za-z0-9.-]+)(?::.*)?$", RegexOption.DOT_MATCHES_ALL).matchEntire(text)?.groupValues?.get(1)
    return safeMediaHost(quoted ?: bare)
}

/** Parse only the hostname from Media3's cleartext policy error; never copy its URL or query. */
internal fun blockedCleartextHost(message: String?): String? {
    val text = message ?: return null
    val url = Regex("""(?i)https?://[a-z0-9.-]+(?::[0-9]{1,5})?""").find(text)?.value ?: return null
    return safeMediaHost(runCatching { java.net.URI(url).host }.getOrNull())
}

internal fun safeMediaFailure(error: Throwable): SafeMediaFailure {
    val kinds = mutableListOf<String>()
    var status: Int? = null
    var reason: Int? = null
    var dnsHost: String? = null
    var cause: Throwable? = error
    repeat(10) {
        val value = cause ?: return@repeat
        if (value is HttpDataSource.InvalidResponseCodeException) status = value.responseCode
        if (value is DataSourceException && reason == null) reason = value.reason
        if (value is java.net.UnknownHostException && dnsHost == null) dnsHost = dnsFailureHost(value.message)
        if (value is HttpDataSource.CleartextNotPermittedException && dnsHost == null) dnsHost = blockedCleartextHost(value.message)
        val kind = when (value) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP_STATUS"
            is HttpDataSource.InvalidContentTypeException -> "HTTP_CONTENT_TYPE"
            is HttpDataSource.CleartextNotPermittedException -> "CLEARTEXT_POLICY"
            is java.net.UnknownHostException -> "DNS"
            is javax.net.ssl.SSLPeerUnverifiedException, is java.security.cert.CertificateException -> "TLS_CERTIFICATE"
            is javax.net.ssl.SSLHandshakeException -> "TLS_HANDSHAKE"
            is javax.net.ssl.SSLException -> "TLS"
            is java.net.SocketTimeoutException -> "TIMEOUT"
            is java.net.ConnectException -> "CONNECT"
            is java.net.SocketException -> "SOCKET"
            is java.io.EOFException -> "EOF"
            is ParserException -> "PARSER"
            is HttpDataSource.HttpDataSourceException -> "HTTP_IO"
            is java.io.InterruptedIOException -> "INTERRUPTED_IO"
            is java.io.IOException -> "IO"
            else -> "OTHER"
        }
        if (kind !in kinds) kinds.add(kind)
        cause = value.cause
    }
    return SafeMediaFailure(kinds, status, reason, dnsHost)
}

internal data class MediaLoadTrace(val event: String, val dataType: Int, val bytesLoaded: Long,
    val loadDurationMs: Long, val wasCanceled: Boolean = false, val failure: SafeMediaFailure? = null,
    val requestHost: String? = null) {
    fun json(): JSONObject = JSONObject().put("event", event).put("dataType", dataType)
        .put("dataKind", when (dataType) {
            C.DATA_TYPE_MANIFEST -> "manifest"
            C.DATA_TYPE_MEDIA -> "media"
            C.DATA_TYPE_MEDIA_INITIALIZATION -> "initialization"
            else -> "other"
        }).put("bytesLoaded", bytesLoaded).put("loadDurationMs", loadDurationMs)
        .put("wasCanceled", wasCanceled).put("httpStatus", failure?.httpStatus ?: JSONObject.NULL)
        .put("ioReason", failure?.ioReason ?: JSONObject.NULL)
        .put("requestHost", safeMediaHost(requestHost) ?: JSONObject.NULL)
        .put("dnsHost", safeMediaHost(failure?.dnsHost) ?: JSONObject.NULL)
        .put("errorKinds", JSONArray(failure?.kinds ?: emptyList<String>()))
}

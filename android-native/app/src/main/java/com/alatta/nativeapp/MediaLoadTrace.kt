package com.alatta.nativeapp

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import org.json.JSONArray
import org.json.JSONObject

/** Fixed error labels only: never serialize exception text, URLs, headers or response bodies. */
internal data class SafeMediaFailure(val kinds: List<String>, val httpStatus: Int?, val ioReason: Int?)

internal fun safeMediaFailure(error: Throwable): SafeMediaFailure {
    val kinds = mutableListOf<String>()
    var status: Int? = null
    var reason: Int? = null
    var cause: Throwable? = error
    repeat(10) {
        val value = cause ?: return@repeat
        if (value is HttpDataSource.InvalidResponseCodeException) status = value.responseCode
        if (value is DataSourceException && reason == null) reason = value.reason
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
    return SafeMediaFailure(kinds, status, reason)
}

internal data class MediaLoadTrace(val event: String, val dataType: Int, val bytesLoaded: Long,
    val loadDurationMs: Long, val wasCanceled: Boolean = false, val failure: SafeMediaFailure? = null) {
    fun json(): JSONObject = JSONObject().put("event", event).put("dataType", dataType)
        .put("dataKind", when (dataType) {
            C.DATA_TYPE_MANIFEST -> "manifest"
            C.DATA_TYPE_MEDIA -> "media"
            C.DATA_TYPE_MEDIA_INITIALIZATION -> "initialization"
            else -> "other"
        }).put("bytesLoaded", bytesLoaded).put("loadDurationMs", loadDurationMs)
        .put("wasCanceled", wasCanceled).put("httpStatus", failure?.httpStatus ?: JSONObject.NULL)
        .put("ioReason", failure?.ioReason ?: JSONObject.NULL)
        .put("errorKinds", JSONArray(failure?.kinds ?: emptyList<String>()))
}

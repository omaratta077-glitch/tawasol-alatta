package com.alatta.nativeapp

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONObject

internal data class JsonHttpResponse(val code: Int, val data: JSONObject)
internal class ResponseTooLarge : IOException("حجم البيانات كبير؛ حمّل المنشورات على دفعات أصغر")

/** مجموعة اتصال واحدة لطلبات HTTP العادية؛ لا تُعِد إرسال كتابة بعد انقطاع ملتبس. */
internal object AttaHttp {
    val apiClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    val mediaClient = apiClient.newBuilder().callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()

    fun json(url: String, payload: JSONObject? = null, client: OkHttpClient = apiClient,
        maxBytes: Long = 32L * 1024 * 1024): JsonHttpResponse {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (payload != null) post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
            .build()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        var attempt = 0
        while (true) {
            val call = client.newCall(request)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("انتهت مهلة الاتصال")
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            try {
                return call.execute().use { response ->
                    val body = response.body
                    if (body != null && body.contentLength() > maxBytes) throw ResponseTooLarge()
                    val buffer = Buffer()
                    if (body != null) {
                        val source = body.source()
                        while (source.read(buffer, 64 * 1024L) >= 0) {
                            if (buffer.size > maxBytes) throw ResponseTooLarge()
                        }
                    }
                    val text = buffer.readUtf8()
                    val data = try { JSONObject(text.ifBlank { "{}" }) }
                    catch (failure: org.json.JSONException) {
                        if (response.isSuccessful) throw IOException("استجابة غير مفهومة من الخادم", failure)
                        JSONObject().put("error", "الخادم غير متاح مؤقتًا (HTTP ${response.code})")
                    }
                    JsonHttpResponse(response.code, data)
                }
            } catch (failure: IOException) {
                val transient = failure is SocketException || failure is EOFException || failure is SocketTimeoutException ||
                    failure.message.orEmpty().contains("unexpected end of stream", ignoreCase = true)
                if (payload != null || attempt++ >= 1 || !transient || System.nanoTime() >= deadline) throw failure
                // اتصال جديد لطلب GET الآمن؛ بلا محاولات متداخلة ولا إعادة إرسال لطلب POST.
                Thread.sleep(100)
            }
        }
    }
}

internal fun loadingFailureMessage(failure: Throwable): String = when (failure) {
    is SocketException, is EOFException -> "انقطع الاتصال أثناء تحميل البيانات. اضغط إعادة المحاولة."
    is SocketTimeoutException -> "الاتصال بطيء أو الخادم تأخر في الرد. اضغط إعادة المحاولة."
    is ResponseTooLarge -> failure.message.orEmpty()
    is IOException -> "تعذر الاتصال بالخادم مؤقتًا. تحقق من الاتصال وأعد المحاولة."
    else -> failure.message?.takeIf { it.isNotBlank() } ?: "تعذر تحميل البيانات. أعد المحاولة."
}

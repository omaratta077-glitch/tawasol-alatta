package com.alatta.nativeapp

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val ADHAN_MAX_BYTES = 20 * 1024 * 1024

/** لا يجوز أن يحل تسجيل مبتور أو متغيّر محل الصوت المحفوظ المحدد حاليًا. */
internal fun copyVerifiedAdhan(input: InputStream, output: OutputStream, size: Long, sha256: String, checkCancelled: () -> Unit = {}) {
    require(size in 1..ADHAN_MAX_BYTES.toLong() && sha256.matches(Regex("[a-f0-9]{64}"))) { "بيانات التسجيل غير صحيحة" }
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    val buffer = ByteArray(32 * 1024)
    while (true) {
        checkCancelled()
        val count = input.read(buffer); if (count < 0) break
        total += count
        require(total <= size) { "حجم التسجيل غير صحيح" }
        digest.update(buffer, 0, count); output.write(buffer, 0, count)
    }
    require(total == size && digest.digest().joinToString("") { "%02x".format(it) } == sha256) { "لم يكتمل التسجيل بصورة صحيحة؛ أعد المحاولة" }
}

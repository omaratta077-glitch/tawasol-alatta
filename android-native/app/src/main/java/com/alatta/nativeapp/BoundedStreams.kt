package com.alatta.nativeapp

import java.io.InputStream
import java.io.OutputStream

/** Constant memory for posts, chat and shared Quran PDFs, including unknown-length providers. */
internal fun copyMediaBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Upload cancelled")
        val n = input.read(buffer)
        if (n < 0) return total
        require(n.toLong() <= maxBytes - total) { "حجم الملف يتجاوز 50 ميجابايت" }
        output.write(buffer, 0, n)
        total += n
    }
}

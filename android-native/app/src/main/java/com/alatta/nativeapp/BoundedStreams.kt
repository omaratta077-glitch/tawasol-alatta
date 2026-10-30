package com.alatta.nativeapp

import java.io.InputStream
import java.io.OutputStream

/** استخدام ثابت للذاكرة في المنشورات والمحادثات وملفات PDF للمصحف، حتى مع مزوّد لا يعلن حجم الملف. */
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

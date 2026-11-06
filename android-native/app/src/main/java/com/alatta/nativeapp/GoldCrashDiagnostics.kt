package com.alatta.nativeapp

import android.app.Application
import android.content.Context
import java.io.File

/**
 * Crash-only diagnostics for the isolated :gold subprocess.
 * Writes ONLY throwable class names and stack method locations, never messages,
 * stream links, Google credentials, tokens, or other potentially private data.
 * File is private to Media's data directory and can be read after :gold exits.
 */
internal object GoldCrashDiagnostics {
    private const val FILE_NAME = "gold_engine_failure.txt"
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous === installedHandler) return
        val replacement = Thread.UncaughtExceptionHandler { thread, cause ->
            try {
                val lines = buildList {
                    add("Gold process fatal error:")
                    var current: Throwable? = cause
                    var depth = 0
                    while (current != null && depth < 4) {
                        add(current.javaClass.name)
                        current.stackTrace.take(8).forEach {
                            add("at " + it.className + "." + it.methodName + ":" + it.lineNumber)
                        }
                        current = current.cause
                        depth++
                    }
                }
                File(application.filesDir, FILE_NAME).writeText(lines.joinToString("\n").take(3500))
            } catch (_: Exception) {
                // Never mask the original Android crash or bypass its handler.
            }
            previous?.uncaughtException(thread, cause)
        }
        installedHandler = replacement
        Thread.setDefaultUncaughtExceptionHandler(replacement)
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    fun lastFailure(context: Context): String? =
        runCatching {
            File(context.filesDir, FILE_NAME).takeIf { it.isFile }
                ?.readText()?.take(3500)?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

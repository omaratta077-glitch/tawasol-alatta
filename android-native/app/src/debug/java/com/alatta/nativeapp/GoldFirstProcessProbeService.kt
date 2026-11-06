package com.alatta.nativeapp

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger

/** Debug-only IPC probe: report Application from a real remote :media process. */
class GoldFirstProcessProbeService : Service() {
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { request ->
        val response = android.os.Message.obtain().apply {
            what = 1
            data = Bundle().apply {
                putString("application", application.javaClass.name)
                putString("process", if (Build.VERSION.SDK_INT >= 28)
                    Application.getProcessName() else "unsupported")
            }
        }
        runCatching { request.replyTo?.send(response) }
        true
    })

    override fun onBind(intent: Intent?): IBinder = receiver.binder
}

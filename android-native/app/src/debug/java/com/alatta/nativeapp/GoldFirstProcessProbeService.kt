package com.alatta.nativeapp

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder

/** Debug-only: confirms Android created Media's Application in isolated :media. */
class GoldFirstProcessProbeService : Service() {
    inner class ProbeBinder : Binder() {
        fun appClass(): String = application.javaClass.name
        fun processName(): String =
            if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "unsupported"
    }
    private val binder = ProbeBinder()
    override fun onBind(intent: Intent?): IBinder = binder
}

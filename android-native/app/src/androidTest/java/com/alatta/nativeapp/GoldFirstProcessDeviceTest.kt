package com.alatta.nativeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies isolated Media application factory on Android (API 28+).
 * Gold player binary is NOT included in public CI; A/V is not tested here.
 */
@RunWith(AndroidJUnit4::class)
class GoldFirstProcessDeviceTest {
    @Test fun isolatedMediaProcessCreatesOriginalMediaApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val finished = CountDownLatch(1)
        val app = AtomicReference<String?>()
        val process = AtomicReference<String?>()
        val failures = AtomicReference<String?>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            app.set(message.data.getString("application"))
            process.set(message.data.getString("process"))
            finished.countDown()
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    Messenger(binder).send(Message.obtain().apply {
                        what = 1
                        replyTo = reply
                    })
                } catch (error: Exception) {
                    failures.set(error.javaClass.simpleName)
                    finished.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onNullBinding(name: ComponentName?) {
                failures.set("null binding")
                finished.countDown()
            }
            override fun onBindingDied(name: ComponentName?) {
                failures.set("binding died")
                finished.countDown()
            }
        }
        val bound = context.bindService(
            Intent(context, GoldFirstProcessProbeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        assertTrue("Probe service could not bind", bound)
        try {
            assertTrue("No reply from isolated :media process", finished.await(20, TimeUnit.SECONDS))
            assertNull("Service probe failed: " + failures.get(), failures.get())
            assertEquals("com.alatta.nativeapp.AttaApplication", app.get())
            assertEquals(context.packageName + ":media", process.get())
        } finally {
            context.unbindService(connection)
        }
    }
}

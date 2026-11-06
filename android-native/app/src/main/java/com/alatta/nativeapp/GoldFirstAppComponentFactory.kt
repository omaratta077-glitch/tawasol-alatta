package com.alatta.nativeapp

import android.app.Application
import android.os.Build
import androidx.core.app.CoreComponentFactory

/**
 * Gold-first prototype: original YtvApp remains the Application in the base
 * process. All Media components in :media get their original AttaApplication.
 *
 * Activated only by the PRIVATE Gold-base merged Manifest; the standard
 * Media application/launch settings are unchanged.
 * Requires Android API 28+ to select an Application via AppComponentFactory.
 */
class GoldFirstAppComponentFactory : CoreComponentFactory() {
    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        val mediaProcess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            Application.getProcessName().endsWith(":media")
        if (!mediaProcess) return super.instantiateApplication(cl, className)
        return try {
            cl.loadClass("com.alatta.nativeapp.AttaApplication")
                .asSubclass(Application::class.java)
                .getDeclaredConstructor().newInstance()
        } catch (e: ReflectiveOperationException) {
            throw InstantiationException("Media Application unavailable in :media").apply { initCause(e) }
        } catch (e: LinkageError) {
            throw InstantiationException("Media library conflict in :media").apply { initCause(e) }
        }
    }
}

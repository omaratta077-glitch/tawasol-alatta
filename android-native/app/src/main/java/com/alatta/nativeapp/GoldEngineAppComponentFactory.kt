package com.alatta.nativeapp

import android.app.Application
import android.os.Build
import androidx.core.app.CoreComponentFactory

/**
 * Only the optional :gold process may instantiate the bundled Gold Application.
 * The main AL-ATTA Media process continues to use AttaApplication unchanged.
 *
 * Activate this factory ONLY once original Gold classes and resources have
 * actually been relocated into the signed Media APK. Gold Activities must be
 * declared android:process=":gold" and android:exported="false".
 * Android <28 does not support AppComponentFactory.
 */
class GoldEngineAppComponentFactory : CoreComponentFactory() {
    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":gold")) {
            try {
                // Let Android perform attachBaseContext() and onCreate() on
                // the original YtvApp, not an unattached second Application.
                return cl.loadClass("dev.google.ytvplayer.YtvApp")
                    .asSubclass(Application::class.java)
                    .getDeclaredConstructor().newInstance()
            } catch (error: ReflectiveOperationException) {
                throw InstantiationException("Bundled Gold Application missing").apply { initCause(error) }
            } catch (error: LinkageError) {
                throw InstantiationException("Bundled Gold dependencies incompatible").apply { initCause(error) }
            }
        }
        return super.instantiateApplication(cl, className)
    }
}

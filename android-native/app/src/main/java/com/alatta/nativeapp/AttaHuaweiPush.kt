package com.alatta.nativeapp

import android.content.Context
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.Executors

/** HMS is a transport adapter; account ownership and notification UI stay shared. */
internal object AttaHuaweiPush {
    private val io = Executors.newSingleThreadExecutor()
    fun register(context: Context, session: String, user: String) {
        if (!BuildConfig.HMS_CONFIGURED || !AttaPush.canNotify(context)) return
        io.execute { runCatching {
            context.assets.open("agconnect-services.json").use {
                com.huawei.agconnect.config.AGConnectServicesConfig.fromContext(context).overlayWith(it)
            }
            val token = HmsInstanceId.getInstance(context).getToken(BuildConfig.HMS_APP_ID, "HCM")
            if (!token.isNullOrBlank()) AttaPush.registerDevice(context, session, user, token, "hms")
        }.onFailure { android.util.Log.w("AttaPush", "Huawei Push unavailable; check AppGallery configuration") } }
    }
    fun stop(context: Context) {
        if (!BuildConfig.HMS_CONFIGURED) return
        io.execute { runCatching { HmsInstanceId.getInstance(context).deleteToken(BuildConfig.HMS_APP_ID, "HCM") } }
    }
}

class AttaHuaweiMessagingService : HmsMessageService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (!AttaPush.canNotify(this)) return
        val values = getSharedPreferences("alatta_native", 0)
        AttaPush.registerDevice(this, values.getString("token", "").orEmpty(),
            values.getString("username", "").orEmpty(), token, "hms")
    }
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val payload = runCatching { JSONObject(message.data) }.getOrNull() ?: return
        AttaPush.receive(this, payload.keys().asSequence().associateWith { payload.optString(it) })
    }
}

package com.alatta.nativeapp

internal fun choosePushProvider(preferHuawei: Boolean, googleAvailable: Boolean, huaweiAvailable: Boolean): String =
    when {
        preferHuawei && huaweiAvailable -> "hms"
        googleAvailable -> "fcm"
        huaweiAvailable -> "hms"
        else -> "none"
    }

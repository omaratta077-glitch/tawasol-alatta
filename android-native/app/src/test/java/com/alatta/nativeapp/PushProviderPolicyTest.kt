package com.alatta.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class PushProviderPolicyTest {
    @Test fun selectsAnAvailableProviderForAllDeviceCombinations() {
        for (preferHuawei in listOf(false,true)) {
            assertEquals("none",choosePushProvider(preferHuawei,false,false))
            assertEquals("fcm",choosePushProvider(preferHuawei,true,false))
            assertEquals("hms",choosePushProvider(preferHuawei,false,true))
            assertEquals(if(preferHuawei) "hms" else "fcm",choosePushProvider(preferHuawei,true,true))
        }
    }
}

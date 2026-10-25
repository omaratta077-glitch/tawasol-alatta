package com.alatta.nativeapp

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Check Android's effective policy, not just the XML declaration. No network requests. */
@RunWith(AndroidJUnit4::class)
class ProviderNetworkPolicyDeviceTest {
    @Test fun externalPlayerHandoffKeepsHttpDeniedForEveryHost() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse("HTTP is denied by default", policy.isCleartextTrafficPermitted)
        for (host in listOf("def001.ycnapi.com", "ycnapi.com", "sub.def001.ycnapi.com", "def001.ycnapi.com.example.org",
            "deft.yacinelive.com", "def.yacinelive.com", "youtube.com", "example.org", "127.0.0.1")) {
            assertFalse("HTTP must stay denied for $host", policy.isCleartextTrafficPermitted(host))
        }
    }
}

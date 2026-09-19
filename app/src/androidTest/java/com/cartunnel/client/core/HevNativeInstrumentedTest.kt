package com.cartunnel.client.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HevNativeInstrumentedTest {
    @Test
    fun jniRegistrationTableMatchesKotlinClass() {
        System.loadLibrary("hev-socks5-tunnel")
        assertFalse(HevNative.TProxyIsRunning())
    }
}

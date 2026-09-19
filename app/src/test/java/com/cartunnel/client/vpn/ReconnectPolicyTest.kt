package com.cartunnel.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun usesBoundedBackoff() { val p = ReconnectPolicy(); assertEquals(1_000, p.delay(0)); assertEquals(2_000, p.delay(1)); assertEquals(5_000, p.delay(2)); assertEquals(10_000, p.delay(3)); assertEquals(30_000, p.delay(4)); assertEquals(30_000, p.delay(99)) }
}

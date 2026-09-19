package com.cartunnel.client.diag

import org.junit.Assert.assertFalse
import org.junit.Test

class RedactingLogTest {
    @Test fun removesUriUuidAndQueryCredentials() { RedactingLog.clear(); RedactingLog.write("vmess://base64-secret password=bad uuid=00000000-0000-4000-8000-000000000000"); val result = RedactingLog.dump(); assertFalse(result.contains("base64-secret")); assertFalse(result.contains("bad")); assertFalse(result.contains("00000000-0000-4000-8000-000000000000")) }
}

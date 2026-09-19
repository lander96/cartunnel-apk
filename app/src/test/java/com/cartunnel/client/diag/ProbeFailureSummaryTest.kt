package com.cartunnel.client.diag

import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFailureSummaryTest {
    @Test fun identifiesNetworkFailureCategories() {
        assertTrue(ProbeFailureSummary.describe(UnknownHostException("node")).contains("category=DNS"))
        assertTrue(ProbeFailureSummary.describe(NoRouteToHostException("blocked")).contains("category=NO_ROUTE"))
        assertTrue(ProbeFailureSummary.describe(SocketTimeoutException("slow")).contains("category=TIMEOUT"))
    }

    @Test fun doesNotPrintNativeMessagesOrCredentials() {
        val summary = ProbeFailureSummary.describe(RuntimeException("HTTP handshake password=secret uuid=private"))
        assertTrue(summary.contains("category=HANDSHAKE"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("private"))
    }
}

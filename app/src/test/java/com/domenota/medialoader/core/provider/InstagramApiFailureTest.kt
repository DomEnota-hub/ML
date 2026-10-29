package com.domenota.medialoader.core.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramApiFailureTest {
    @Test fun geoBlockedPostIsReportedAsUnavailableInsteadOfTemporaryFailure() {
        val response = """{"message":"geoblock_required","gating_type":"unappealable","status":"fail"}"""

        val error = InstagramApiFailure.geoBlockedPublication(400, response)

        assertEquals(ProviderException.Reason.UNSUPPORTED, error?.reason)
        assertEquals("Публикация недоступна для этого аккаунта или региона.", error?.message)
        assertNull(InstagramApiFailure.geoBlockedPublication(400, """{"message":"other"}"""))
        assertNull(InstagramApiFailure.geoBlockedPublication(500, response))
    }

    @Test fun spamFlagMeansRateLimitEvenOnHttp400() {
        assertTrue(InstagramApiFailure.isRateLimited(400,
            """{"message":"","spam":true,"status":"fail"}"""))
        assertTrue(InstagramApiFailure.isRateLimited(429, ""))
        assertFalse(InstagramApiFailure.isRateLimited(400, """{"status":"fail"}"""))
    }
    @Test fun longSensitiveContentResponseIsClassifiedBeforeLogTruncation() = kotlinx.coroutines.runBlocking {
        val body = """{"message":"geoblock_required","description":"You can manage your sensitive content preferences in Settings.","padding":"${"x".repeat(800)}","status":"fail"}"""
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val responder = kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val bytes = body.toByteArray()
                socket.getOutputStream().use {
                    it.write("HTTP/1.1 400 Bad Request\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    it.write(bytes)
                }
            }
        }
        try {
            try {
                fetchInstagramApi("http://127.0.0.1:${server.localPort}/media", "")
                org.junit.Assert.fail("Restriction should throw")
            } catch (error: ProviderException) {
                assertEquals(ProviderException.Reason.UNSUPPORTED, error.reason)
                assertTrue(error.message.orEmpty().contains("чувствительного контента"))
            }
        } finally { server.close(); responder.join(1000) }
    }

}

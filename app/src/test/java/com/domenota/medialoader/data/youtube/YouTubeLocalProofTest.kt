package com.domenota.medialoader.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLocalProofTest {
    @Test fun acceptsOneShortLivedProbeWithoutExposingValuesInToString() {
        YouTubeLocalProof.clear()
        assertTrue(YouTubeLocalProof.offer("visitor_data_123", "generated_token_123456", 1000))
        val proof = YouTubeLocalProof.claimProbe(2000)!!
        assertEquals("visitor_data_123", proof.visitorData)
        assertFalse(proof.toString().contains(proof.token))
        assertNull(YouTubeLocalProof.claimProbe(2001))
        YouTubeLocalProof.clear()
    }

    @Test fun rejectsMalformedAndExpiredProofs() {
        YouTubeLocalProof.clear()
        assertFalse(YouTubeLocalProof.offer("bad;argument", "generated_token_123456", 1000))
        assertFalse(YouTubeLocalProof.offer("visitor_data_123", "bad;argument", 1000))
        assertNull(YouTubeLocalProof.claimProbe(1001))
        assertTrue(YouTubeLocalProof.offer("visitor_data_123", "generated_token_123456", 1000))
        assertNull(YouTubeLocalProof.claimProbe(601_001))
        YouTubeLocalProof.clear()
    }

    @Test fun acceptsEncodedVisitorAndStandardBase64Token() {
        YouTubeLocalProof.clear()
        assertTrue(YouTubeLocalProof.offer("visitor%3Ddata%3D", "standard+/base64==", 1000))
        assertEquals("standard+/base64==", YouTubeLocalProof.claimProbe(1001)?.token)
        YouTubeLocalProof.clear()
    }

    @Test fun reportsOnlySafeRejectionCategories() {
        assertEquals("visitor-chars", YouTubeLocalProof.rejectionReason("bad;visitor", "standard+/base64=="))
        assertEquals("token-chars", YouTubeLocalProof.rejectionReason("visitor_data_123", "bad;argument123"))
        assertEquals(null, YouTubeLocalProof.rejectionReason("visitor%3Ddata%3D", "standard+/base64=="))
    }

    @Test fun retainsFreshProofForAnalysisAndDownload() {
        YouTubeLocalProof.clear()
        assertTrue(YouTubeLocalProof.offer("visitor_data_123", "generated_token_123456", 1000))
        assertEquals("generated_token_123456", YouTubeLocalProof.available(2000)?.token)
        assertEquals("generated_token_123456", YouTubeLocalProof.available(2001)?.token)
        assertNull(YouTubeLocalProof.available(601_001))
        YouTubeLocalProof.clear()
    }
}

package com.domenota.medialoader.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProofArgsTest {
    @Test fun usesWebClientsAndBrowserVisitorBinding() {
        val proof = YouTubeLocalProof.Value("visitor_data_123", "generated_token_123456", 1000)
        val args = YouTubeProofArgs.forWeb(proof)
        assertTrue(args.contains("player_client=web,web_safari"))
        assertTrue(args.contains("visitor_data=visitor_data_123"))
        assertTrue(args.contains("po_token=web.gvs+generated_token_123456"))
        assertTrue(args.contains("web_safari.gvs+generated_token_123456"))
        assertFalse(YouTubeProofArgs.safeLabel.contains(proof.token))
        assertEquals("browser-web-proof", YouTubeProofArgs.safeLabel)
    }
}

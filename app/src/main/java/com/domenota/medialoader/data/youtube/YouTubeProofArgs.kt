package com.domenota.medialoader.data.youtube

/** Bind a browser-created GVS proof to the matching Web players. Never log the result. */
object YouTubeProofArgs {
    const val safeLabel = "browser-web-proof"

    fun forWeb(proof: YouTubeLocalProof.Value): String {
        require(YouTubeLocalProof.rejectionReason(proof.visitorData, proof.token) == null)
        return "youtube:player_client=web,web_safari;visitor_data=${proof.visitorData};" +
            "po_token=web.gvs+${proof.token},web_safari.gvs+${proof.token}"
    }
}

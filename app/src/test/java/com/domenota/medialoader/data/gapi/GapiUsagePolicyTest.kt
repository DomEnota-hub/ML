package com.domenota.medialoader.data.gapi

import org.junit.Assert.assertEquals
import org.junit.Test

class GapiUsagePolicyTest {
    @Test fun costsFollowActualEndpointAndHttpResult() {
        for (status in listOf(200, 201, 404)) {
            assertEquals(1, GapiUsagePolicy.cost("/v2/vk?url=video", status))
            assertEquals(1, GapiUsagePolicy.cost("/v2/instagram/media?url=album", status))
            assertEquals(1, GapiUsagePolicy.cost("/v2/youtube/info?url=video", status))
            assertEquals(8, GapiUsagePolicy.cost("/v2/youtube?url=video", status))
        }
        for (status in listOf(0, 400, 401, 429, 502))
            assertEquals(0, GapiUsagePolicy.cost("/v2/vk?url=video", status))
    }
}

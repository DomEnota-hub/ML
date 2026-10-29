package com.domenota.medialoader.data.vkcommunity

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class VkCommunityApiTest {
    @Test fun parsesCommunityNameAndAvatarInBothVkResponseShapes() {
        val group = JSONObject().put("name", "Юмор Шрёдингера")
            .put("photo_200", "https://sun1-1.userapi.com/avatar.jpg")
        val array = JSONArray().put(group)
        assertEquals(VkCommunityInfo("Юмор Шрёдингера",
            "https://sun1-1.userapi.com/avatar.jpg"), VkCommunityApi.parseCommunityInfo(array))
        assertEquals(VkCommunityInfo("Юмор Шрёдингера",
            "https://sun1-1.userapi.com/avatar.jpg"),
            VkCommunityApi.parseCommunityInfo(JSONObject().put("groups", array)))
    }

    @Test fun acceptsCommunityWallWithoutResolvingScreenName() {
        val wall = JSONObject("""{"count":1,"items":[{"id":3,"owner_id":-42}]}""")
        VkCommunityApi.verifyCommunityWall(VkCommunityLink.Domain("schrodinger_humor"), wall, 0)
    }

    @Test fun rejectsPersonalWallEvenWhenAddressLooksLikeACommunity() {
        val wall = JSONObject("""{"count":1,"items":[{"id":3,"owner_id":42}]}""")
        try {
            VkCommunityApi.verifyCommunityWall(VkCommunityLink.Domain("some_name"), wall, 0)
            fail("A personal profile must not be scanned")
        } catch (error: VkApiFailure) {
            assertEquals(VkApiFailure.Kind.NOT_COMMUNITY, error.kind)
        }
    }

    @Test fun reportsEmptyDomainWallAsUnverified() {
        val wall = JSONObject("""{"count":0,"items":[]}""")
        try {
            VkCommunityApi.verifyCommunityWall(VkCommunityLink.Domain("some_name"), wall, 0)
            fail("An empty wall cannot establish the owner type")
        } catch (error: VkApiFailure) {
            assertEquals(VkApiFailure.Kind.EMPTY_WALL, error.kind)
        }
    }
}

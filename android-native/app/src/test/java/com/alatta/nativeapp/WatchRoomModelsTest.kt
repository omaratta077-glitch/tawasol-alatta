package com.alatta.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchRoomModelsTest {
    @Test fun codesNormalizeWithoutAcceptingUrlsOrUnboundedInput() {
        assertEquals("A1B2C3D4E5F6", watchRoomCode("a1b2-c3d4 e5f6"))
        for (bad in listOf("", "1234", "https://example.test", "A1B2C3D4E5FG", "1".repeat(100))) assertNull(watchRoomCode(bad))
    }
    @Test fun roomMembershipAcceptsTwoThroughSixAndRequiresTheAuthenticatedPeer() {
        for (count in 2..6) {
            val rows = JSONArray()
            repeat(count) { rows.put(JSONObject().put("peerId", "peer-$it").put("username", "user$it")
                .put("name", "اسم $it").put("cameraOn", it % 2 == 0).put("micOn", it != 1)) }
            val json = JSONObject().put("code", "A1B2C3D4E5F6").put("myPeerId", "peer-0").put("host", "user0")
                .put("channelId", "saved-test").put("channelName", "Test Sports").put("members", rows)
            val result = WatchRoom.parse(json)
            assertEquals(count, result.members.size)
            assertFalse(result.members[1].micOn)
            json.put("myPeerId", "outsider")
            assertThrows(IllegalArgumentException::class.java) { WatchRoom.parse(json) }
        }
    }
}

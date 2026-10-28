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
        assertEquals("ABC", watchRoomCode("a-b c"))
        for (bad in listOf("", "123", "A1C", "ABCD", "https://example.test", "1".repeat(100))) assertNull(watchRoomCode(bad))
        assertEquals("ABC", watchRoomInviteCode("انضم لغرفة المشاهدة في العطا. الكود: abc"))
        assertEquals("XYZ", watchRoomInviteCode("xyz"))
        for (bad in listOf("watch the match", "الكود: ABCD", "الكود: AB1", "https://example.test/ABC")) assertNull(watchRoomInviteCode(bad))
    }
    @Test fun roomMembershipAcceptsTwoThroughSixAndRequiresTheAuthenticatedPeer() {
        for (count in 2..6) {
            val rows = JSONArray()
            repeat(count) { rows.put(JSONObject().put("peerId", "peer-$it").put("username", "user$it")
                .put("name", "اسم $it").put("cameraOn", it % 2 == 0).put("micOn", it != 1)) }
            val json = JSONObject().put("code", "ABC").put("myPeerId", "peer-0").put("host", "user0")
                .put("channelId", "saved-test").put("channelName", "Test Sports").put("members", rows)
            val result = WatchRoom.parse(json)
            assertEquals(count, result.members.size)
            assertFalse(result.members[1].micOn)
            json.put("myPeerId", "outsider")
            assertThrows(IllegalArgumentException::class.java) { WatchRoom.parse(json) }
        }
    }
}

package app.lumo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectHistoryMergeTest {
    @Test fun equalTimestampMessagesUseStableIdTieBreaker() {
        val laterId=Msg(
            id="ffffffff-ffff-4fff-8fff-ffffffffffff",
            from="a",to="b",text="later-id",
            createdAt="2026-09-25T10:00:00.123456Z"
        )
        val earlierId=Msg(
            id="00000000-0000-4000-8000-000000000001",
            from="a",to="b",text="earlier-id",
            createdAt="2026-09-25T10:00:00.123456Z"
        )

        val merged=mergeChatMessages(listOf(laterId),listOf(earlierId))

        assertEquals(listOf(earlierId.id,laterId.id),merged.map{it.id})
    }

    @Test fun newerMutationMetadataWinsWithoutLosingReceipts() {
        val original=Msg(
            id="00000000-0000-4000-8000-000000000010",
            from="a",to="b",text="old",
            createdAt="2026-09-25T10:00:00Z",
            deliveredAt="2026-09-25T10:00:01Z"
        )
        val edited=original.copy(
            text="new",
            deliveredAt="",
            editedAt="2026-09-25T10:00:02Z"
        )

        val merged=mergeChatMessages(listOf(original),listOf(edited)).single()

        assertEquals("new",merged.text)
        assertEquals("2026-09-25T10:00:01Z",merged.deliveredAt)
        assertTrue(merged.editedAt.isNotBlank())
    }
}

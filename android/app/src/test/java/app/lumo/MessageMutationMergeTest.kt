package app.lumo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMutationMergeTest {
    private fun base(
        text:String,
        editedAt:String="",
        deletedAt:String="",
        deliveredAt:String="",
        readAt:String=""
    )=Msg(
        id="00000000-0000-4000-8000-000000000001",
        from="00000000-0000-4000-8000-000000000002",
        to="00000000-0000-4000-8000-000000000003",
        text=text,
        createdAt="2026-09-24T08:00:00.000Z",
        deliveredAt=deliveredAt,
        readAt=readAt,
        editedAt=editedAt,
        deletedAt=deletedAt
    )

    @Test fun staleHistoryCannotResurrectDeletedText() {
        val deleted=base(
            text="Сообщение удалено",
            deletedAt="2026-09-24T08:05:00.000Z"
        )
        val stale=base(
            text="секретный старый текст",
            deliveredAt="2026-09-24T08:06:00.000Z",
            readAt="2026-09-24T08:07:00.000Z"
        )
        val merged=mergeChatMessages(listOf(deleted),listOf(stale)).single()
        assertEquals("Сообщение удалено",merged.text)
        assertEquals(deleted.deletedAt,merged.deletedAt)
        assertEquals(stale.readAt,merged.readAt)
    }

    @Test fun staleHistoryCannotUndoAnEdit() {
        val edited=base(
            text="новая версия",
            editedAt="2026-09-24T08:05:00.000Z"
        )
        val stale=base(text="старая версия")
        assertEquals(
            "новая версия",
            mergeChatMessages(listOf(edited),listOf(stale)).single().text
        )
    }

    @Test fun newerServerEditWinsAndDeletionWinsOverEdits() {
        val first=base(text="v1",editedAt="2026-09-24T08:02:00.000Z")
        val newer=base(text="v2",editedAt="2026-09-24T08:03:00.000Z")
        assertEquals("v2",mergeChatMessages(listOf(first),listOf(newer)).single().text)

        val deleted=base(
            text="Сообщение удалено",
            editedAt=newer.editedAt,
            deletedAt="2026-09-24T08:04:00.000Z"
        )
        val final=mergeChatMessages(listOf(newer),listOf(deleted)).single()
        assertTrue(final.deletedAt.isNotBlank())
        assertEquals("Сообщение удалено",final.text)
    }
}

# Lumo — sender-owned message edits, deletion tombstones and in-chat search

This stacked branch upgrades the current direct-message screen with server-backed edit/delete/search while preserving linked replies, private attachments, encrypted offline history and older-server compatibility.

## Edit

- Only the authenticated original sender can edit a direct message.
- Empty text and text over 4,000 characters are rejected server-side.
- The server stores only the latest text plus `edited_at`; this milestone intentionally does **not** keep an edit-history copy of the old text.
- Android shows `изменено` and protects newer edit state from stale WebSocket/history responses.
- Attachment messages can edit their caption/text without changing the private attachment object.

## Delete

- Only the authenticated original sender can delete their direct message.
- Deletion is a soft tombstone: message text becomes `Сообщение удалено` and `deleted_at` is set.
- The old text is not copied into a separate `original_text` audit column by this implementation.
- Deleted messages cannot receive new reactions or be used as new reply targets and are excluded from message search.
- Existing replies remain structurally intact; their preview resolves to the deletion tombstone rather than resurrecting old text.
- If a deleted message had a private attachment, the recipient can no longer obtain a new signed download URL for it. The uploader remains the storage owner until retention/object cleanup removes the object.

## Search

- Search is available only when `/api/messages/capabilities` advertises `messageSearch:true`.
- Query length is 2–100 characters and results are limited to 50.
- PostgreSQL scopes every result to exactly the authenticated user's direct conversation with the selected peer.
- `strpos(lower(text),lower(query))` treats `%` and `_` as ordinary characters rather than SQL wildcards.
- Deleted messages are omitted.

## Android/offline behavior

- Edit/delete/search controls stay hidden when connected to an older server that does not advertise them.
- Encrypted offline history stores `editedAt`/`deletedAt` so a cached tombstone does not revert to old text after restart.
- Merge logic gives newer edit/delete state precedence over delayed socket/history payloads while still accepting newer read/delivery receipts.
- Search is a focused chat mode; pending unsent messages are not mixed into server search results.

## Production gates

1. Two-device tests for live edit/delete propagation across separate server instances.
2. Offline tests where an edit/delete occurs on the other device before reconnect.
3. Attachment deletion tests against real private object storage and expired signed URLs.
4. Confirm retention policy for attachment objects after tombstoned messages/account deletion.
5. Accessibility/localization pass for edit/delete confirmation and search.
6. Group-message edits/deletes/search remain separate work; this milestone is direct messages only.
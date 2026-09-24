# Lumo Phase 20 — cursor pagination for long private-group history

This stacked branch adds bounded older-history loading to private groups without weakening the current membership-window privacy model.

## Server behavior

- Existing `GET /api/groups/:id/messages` remains backwards-compatible and continues returning the newest 100 visible messages.
- New `GET /api/groups/:id/messages/page` supports cursor pagination with `beforeCreatedAt`, `beforeId`, and a bounded page size from 10 to 100 (default 50).
- Cursor ordering uses the stable `(created_at,id)` tuple, so equal timestamps do not create duplicates or skipped messages.
- The response contains `messages` in chronological order and `next` only when more visible history exists.
- Every page is still restricted to the requester's **current group membership period**; leave/rejoin cannot page back into earlier history.
- Linked-reply previews on older pages use the same membership-window redaction as normal history.

## Android behavior

- `/api/capabilities` advertises `groupHistoryPagination`.
- The group room keeps the existing recent-message polling path for compatibility.
- When the newest legacy window reaches 100 messages and pagination is supported, a `Загрузить более ранние` control appears above the conversation.
- Older pages are merged by message ID and kept in chronological order.
- Once older history has been expanded, normal polling refreshes the newest 100 messages without discarding the already loaded older pages.
- Search mode remains separate and does not mix its results with history pagination.

## Reliability and privacy

- Page size and cursors are validated server-side.
- Non-members receive no paged history.
- Pagination cannot bypass late-join or rejoin history boundaries.
- No attachment bytes, storage object keys, private credentials, or deleted-message search data are added to the paging cursor.

## Still required before production

1. Physical-device testing with very large groups and thousands of messages.
2. Verify scroll position and memory behavior on low-RAM Android devices.
3. Add pagination to group search if search result volume later needs more than the current 50-result cap.
4. Consider server retention and archival limits before allowing unbounded historical storage.
5. Do not merge this stacked tip directly to `main`; review and stage its prerequisites first.
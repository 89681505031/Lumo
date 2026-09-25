# Direct-message history pagination

Lumo now exposes a stable, participant-scoped pagination API for one-to-one chat history.

## Endpoint

`GET /api/messages/:peerId/page?limit=50&beforeId=<message UUID>`

- Authentication is required.
- `limit` is optional and bounded to 1–100.
- `beforeId` is optional. It must identify a message in the exact direct conversation visible to the authenticated user.
- Responses contain messages in chronological order for easy prepending in Android.
- `next.id` is the UUID cursor for the next older page, or `null` when history is exhausted.
- The cursor timestamp is resolved inside PostgreSQL so microsecond precision is preserved. UUID is the tie-breaker for messages that share the exact same timestamp.

Example response:

```json
{
  "messages": [],
  "next": { "id": "00000000-0000-4000-8000-000000000000" }
}
```

## Compatibility

The legacy `GET /api/messages/:peerId` endpoint remains unchanged for Lumo 1.0.9 and older clients. New clients can detect pagination through `directPagination: true` in `/api/capabilities`.

## Database

Two conversation history indexes support both sender directions:

- `messages_sender_recipient_history_idx`
- `messages_recipient_sender_history_idx`

The integration test inserts 125 messages with an identical PostgreSQL timestamp and verifies 50 + 50 + 25 paging with no gaps or duplicates, plus conversation-scoped cursor rejection.

# Lumo Phase 11 — server-linked direct-message replies

This draft branch is stacked on profile-avatar PR #38. It upgrades quote-style replies into real server-linked reply metadata while remaining compatible with older Lumo servers.

## What works

- `messages.reply_to_message_id` is a nullable self-reference with `ON DELETE SET NULL` and an index.
- New servers expose authenticated `GET /api/messages/capabilities` with `linkedReplies:true`.
- Android checks that capability per direct chat. If supported, a reply sends the original message UUID separately from the new text.
- If the connected server is older or capability probing fails, Android keeps the previous visible quoted-text fallback instead of silently losing reply context.
- Server accepts a reply target only when that message belongs to the same two-party conversation. Knowing another message UUID is not enough to link to it.
- Message history and live WebSocket delivery include `replyToMessageId`, `replyPreviewText`, and `replyPreviewFrom` so the UI can render a compact reply card above the new message.
- Encrypted Android offline history and pending retry queues preserve reply metadata.
- The sender UUID + clientMessageId idempotency contract now also includes the reply target; retrying the same UUID with a different target returns a conflict instead of changing meaning.

## Privacy / compatibility

- Reply previews contain at most data already visible in that direct conversation. The API never fetches a reply target from a third user's conversation.
- Plain messages remain unchanged and additive clients can ignore all reply fields.
- If an original message is removed later by a future delete feature, the foreign key clears the link instead of deleting the reply.
- This milestone does not add automatic message export to AI, media forwarding, group replies, edit history, or E2E encryption.

## Tests / release gates

- PostgreSQL integration test covers capability discovery, same-chat linked reply, history preview, idempotent retry, reply-target conflict, malformed UUID and cross-conversation rejection.
- Android/server CI must pass on this stacked branch.
- Before production: verify two phones across WebSocket + HTTP reconnect, offline pending reply retry, old-server quote fallback, long Unicode preview rendering and original-message deletion after edit/delete work is integrated.
- Do not merge this stacked tip directly to `main`; review prerequisites and deploy to isolated staging first.
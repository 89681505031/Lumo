# Lumo Phase 17 — linked replies and search inside private groups

This stacked branch extends the current private-group backend/UI with group message replies and server-backed search while preserving membership-window privacy.

## Linked replies

- Group messages can carry an optional `replyToMessageId` linked to another message in the same group.
- A user may reply only to a message visible during that user's **current** membership period.
- The reply target is part of the idempotency contract: reusing the same client message UUID with another reply target returns a conflict.
- Android stores reply metadata in the existing durable single pending group-send slot so an uncertain retry preserves the same meaning.
- When connected to an older server that does not advertise `groupLinkedReplies`, Android falls back to a visible quoted-text reply instead of silently dropping context.

## Membership privacy

- A late-joining or rejoining member can see a new reply message without automatically gaining the old message it references.
- History/search queries include reply preview text only when the referenced message is within the requester's current membership window.
- Same-instance WebSocket delivery calculates preview visibility per recipient. If the target predates that recipient's current membership, the live event contains the link ID but no preview text/from identity.
- This prevents replies from becoming a side-channel for old group history.

## Group search

- `GET /api/groups/:id/messages/search?q=...` requires current membership.
- Query length is 2–100 characters and results are capped at 50 newest matches.
- Search is restricted to messages at or after the current membership `joined_at`, so leaving/rejoining cannot recover prior history.
- Android exposes a cosmic in-group search panel only when `groupSearch` is advertised by server capabilities.

## Android UX

- Tapping a group message prepares a reply.
- The composer shows the selected reply context and lets the user cancel it before sending.
- Reply bubbles show a compact neon preview. If the referenced message is outside this membership period, the UI says the source is unavailable instead of showing hidden content.
- Search mode hides unrelated pending-send presentation and returns server-scoped results.

## Still required before production

1. Multi-device QA with users joining at different times, leaving/rejoining and replying across those boundaries.
2. Group message pagination; search currently runs over stored group history with a 50-result cap.
3. Group reactions, edit/delete, attachments and richer forwarded-message attribution remain future work.
4. Accessibility/long-Unicode layout testing for reply cards and search results.
5. Do not merge the stacked tip directly to `main`; review prerequisites and deploy in isolated staging first.
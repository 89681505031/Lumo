# Lumo message actions — compatibility-first staging milestone

This draft branch builds on the still-unmerged cosmic groups + local privacy PR #32. All three requested messaging actions are explicitly separated by backend readiness.

## Available with existing text message API (no server migration)

- **Reply** from the three-dot menu on any direct-message bubble. A reply preview appears above the composer with a cancel button; sending prepends a short, clearly visible quoted extract to the outgoing text. The existing per-chat pending queue, HTTP retries and WebSocket transport remain intact. **This is text quoting, not a database-linked/threaded reply**: another recipient can edit or forward the quoted text, and deleting an original does not remove the quote.
- **Forward text** from the same menu. The user explicitly selects a different contact from the server's authenticated user directory before any content is sent. This action copies the exact visible message text into a new message marked as forwarded by a text label. Attachments and expiring media links are deliberately excluded. The forwarding message and its single generated UUID are stored **before** HTTP send in the destination's existing on-device pending queue. Lost-response or offline retry reuses the same idempotency key; if delivery is not confirmed, open the destination chat to retry. The pending queue is local app data and **not encrypted offline history**.

## Reactions: server-gated staging only

- The Android chat checks authenticated `GET /api/reactions/capabilities` before displaying reaction controls. An older or flag-disabled production server returns no reaction UI. Normal quotes, forwards and text chat continue.
- With `LUMO_REACTIONS_ENABLED=true` and a working PostgreSQL database on a reviewed **staging server**, participants in a direct chat can add/remove six allowed emojis. Reactions are stored in a new, idempotently migrated `message_reactions` table with `(message_id,user_id,emoji)` as a unique key.
- `PUT /api/reactions/:messageId` and `DELETE /api/reactions/:messageId` take `{"emoji":"❤️"}`, validate a strict emoji whitelist, enforce session authentication and check that the caller is a sender or recipient of that specific message. `GET /api/reactions/with/:peerId` returns at most 500 newest reactions for the authenticated two-party conversation and does not expose another conversation's reactions. The Android UI polls while an eligible chat is visible and refreshes immediately after changes.
- API responses are private `Cache-Control: no-store`. All write endpoints are IP-rate-limited; a shared distributed limiter is a further production hardening item.
- Reactions are **not** enabled on current production. No unseen remote message text, signed attachment URL, secret or private reply metadata is placed in reaction requests.

## Validation and release gates

1. CI Android build and PostgreSQL integration tests must pass, including replay-safe reaction PUT/DELETE, third-party denial, same emoji from both peers, invalid inputs and revoked sessions.
2. Confirm regular text, quote reply, forwarding and pending queue on two physical phones with both old production and future staging backend.
3. Verify sender/recipient-only reactions on separate server instances and after app restart, plus feature flag OFF with no migration failure.
4. Review privacy safeguards from prerequisite draft branches (#2, #14–#16) before integrating any unmerged message edit/delete or attachments. Group reactions, full threaded replies, forwarded attachment assets and E2E encryption are **not implemented by this milestone**.
5. Do not deploy this stacked design branch into production or assume the known old-production conversation-list SQL deployment issue is resolved by these changes.

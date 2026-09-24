# Lumo Phase 15 — explicit private attachment forwarding

This stacked branch lets a user forward an existing private attachment to another direct chat without downloading it into a public location or duplicating the stored object.

## Privacy model

- Forwarding is always an explicit user action from the message menu.
- The object remains in the same private S3-compatible bucket under the original random object key.
- The server never returns that object key to Android. The new recipient receives access only because a new direct message links the same attachment UUID to that recipient.
- Signed download authorization now checks all non-deleted messages that reference the attachment. Unrelated authenticated users still receive no signed URL.
- Deleting a forwarded message removes that recipient's future access when no other non-deleted message grants it. The storage owner keeps access until normal retention cleanup.
- Forwarding does not make the attachment public and does not create a permanent share URL.

## Reliability

- Each forward uses a stable `clientMessageId`. Repeating the same request with the same recipient, attachment and caption is idempotent.
- Reusing the same client ID with a different recipient or message meaning returns a conflict instead of silently changing the destination.
- Text-only forwarding keeps the existing encrypted/offline pending-message retry path.
- Attachment forwarding does not silently queue raw file bytes on-device; if the network call fails, the dialog offers retry with the same idempotency ID.

## Server behavior

- `POST /api/media/:id/forward` requires an authenticated participant of an existing non-deleted message that references the attachment.
- The new recipient must be a different valid Lumo account.
- The server inserts a new direct message referencing the existing `media_id`; no bucket copy is required.
- Signed downloads authorize the storage owner or participants of any current non-deleted message referencing that asset.

## Production gates

1. Keep private-media storage disabled in production until the Phase 12 storage/CORS/scanning/retention gates are satisfied.
2. Verify two-device forwarding across image, video, voice, PDF, TXT and OpenXML documents.
3. Verify delete/revoke behavior when the same attachment has multiple forwards.
4. Add product-level reporting/moderation rules for forwarded user content before broad rollout.
5. Add object garbage collection that removes storage only when retention rules allow and no active references remain.
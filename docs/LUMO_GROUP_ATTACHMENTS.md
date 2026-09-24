# Lumo Phase 19 — private attachments inside groups

This stacked branch extends the private attachment storage model to private groups without making files public and without weakening the existing group membership-window privacy rules.

## Supported group attachments

- Photos: JPEG, PNG and WebP.
- Video: MP4.
- Voice messages: AAC in an M4A/MP4 container, recorded only after the user explicitly taps the group voice control and grants Android microphone permission.
- Documents: PDF, TXT, DOCX, XLSX and PPTX using the same strict server allowlist and size limits as direct messages.

## Storage and authorization model

- Group media remains disabled unless private media storage is explicitly enabled on the server.
- A group upload gets a random private object key. Android receives only a short-lived presigned upload request, never bucket credentials.
- A group member cannot download an uploaded file until that file has been committed into a group message.
- Download authorization requires either the storage owner or a **current group membership** whose `joined_at` is at or before the attachment message timestamp.
- Leaving/removal immediately removes group access. Rejoining later does not reopen attachments from the prior membership period.
- Late joiners cannot open older group attachments, matching the existing group-history privacy boundary.
- Deleted group messages no longer grant participant download access. The uploader may retain owner access until normal retention/object cleanup.

## Reliability

- Group media messages use the existing stable client-message UUID approach. Repeating an uncertain send with the same asset/caption returns the same group message instead of creating a duplicate.
- The exact pending asset/message ID is saved on Android before final send confirmation so retry keeps the same message identity.
- The database distinguishes a direct-message media target from a group target and enforces exactly one target scope per asset.
- Claimed group media is linked back to its group message with safe `ON DELETE SET NULL` behavior.

## Android UX

- The existing group room gets a real attachment composer only when the server advertises `groupAttachments`.
- Photo/video uses Android Photo Picker; documents use the system Open Document picker.
- Group voice recording stops if the app is backgrounded and cleans up temporary microphone resources/files.
- Opening a group attachment reuses the same authenticated short-lived viewer flow as direct messages.

## Still required before production

1. Keep private media storage disabled in production until storage CORS, malware/quarantine, retention and quota gates are completed.
2. Physical two-/three-device tests across join/leave/rejoin boundaries and unstable networks.
3. Server-side object garbage collection for abandoned and retention-expired uploads.
4. Moderation/reporting rules for media shared into groups.
5. Large-group bandwidth/cost testing and rate limits at the storage/CDN layer.
6. This does not add E2E encryption; transport/storage privacy remains server-controlled.
# Lumo Phase 10 — explicit cross-device profile avatar staging

This branch is stacked on cross-device bio PR #37. It adds authenticated profile-photo synchronization while preserving the existing local-only picker behavior and requiring a separate user action before a local photo becomes visible to other Lumo users.

## Privacy / user control

- Choosing a photo with Android Photo Picker still saves only a compressed app-private JPEG on the phone.
- **Nothing is uploaded automatically.** A separate `Синхронизировать фото` action appears with a notice that the image will become visible to other authenticated Lumo users.
- The user can independently remove the public account photo and/or remove the local device copy.
- Reading another user's avatar requires a valid Lumo session. There is no anonymous public avatar URL.
- This phase does not upload camera EXIF/original bytes: Android decodes and recompresses the selected JPEG/PNG/WebP to a bounded JPEG first.

## Server bounds

- PostgreSQL stores at most one avatar per account in `avatar_bytes`, with `avatar_mime` and `avatar_updated_at` metadata.
- Upload endpoint accepts **JPEG only**, maximum raw request size 384 KiB.
- Server validates SOI/EOI JPEG structure plus a SOF dimensions marker. Dimensions must be at least 32×32 and at most 1024×1024 / 1,048,576 pixels.
- Android targets <=350 KiB and <=512 px edge before upload.
- Public user JSON exposes only `hasAvatar` and `avatarVersion`; normal user searches/conversation queries deliberately do not select avatar bytes.
- `GET /api/users/:id/avatar` is authenticated, sends `X-Content-Type-Options: nosniff`, and uses private cache metadata. Removing an avatar changes `avatarVersion` so clients can invalidate old images.

## Android behavior

- People list, chat list, direct-chat header and incoming-avatar bubble can render authenticated remote avatars.
- A small in-memory LRU avoids repeatedly decoding the same version during one app process.
- Profile prefers a locally selected image while it is unsynced; once local copy is removed, the synchronized account avatar is fetched from the server.
- If an old server does not implement avatar endpoints, existing local avatar behavior remains usable; upload/removal simply fails without affecting direct messages.

## Tests / production gates

- PostgreSQL integration test covers session-required upload/read, wrong MIME, malformed JPEG, public metadata visibility, authenticated cross-user image retrieval, missing image, deletion and cache-version invalidation.
- Android + server CI must pass on this stacked branch.
- Before production: test real JPEG/PNG/WebP selections, rotation/EXIF behavior, low-memory devices, account switching, deletion on two phones and server storage growth.
- This stores user-uploaded profile imagery in PostgreSQL. Before a broad production rollout, define moderation/reporting, retention/deletion expectations, backup policy and storage quotas.
- Do not merge the stacked branch directly to `main`; review its prerequisites and deploy first to isolated staging.
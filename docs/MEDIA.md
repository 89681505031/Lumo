# Private media foundation — photos, short videos and voice attachments

This branch is a **server-side integration layer** for private direct-message media. It is deliberately disabled until a private S3-compatible storage bucket and runtime credentials are configured. Android attachment pickers, recording/playback UI, malware scanning and a production bucket are separate follow-up work. Do not describe this branch as a released media feature.

## Required environment (server only)

- `MEDIA_BUCKET`: existing private S3 bucket, with public listing, public ACLs and public object access disabled.
- `MEDIA_REGION`: bucket region.
- `MEDIA_ACCESS_KEY_ID`, `MEDIA_SECRET_ACCESS_KEY`: narrowly scoped server-only credentials for this bucket's private prefix. Set as Vercel/hosting environment secrets, never commit them or send them through chat.
- Optional `MEDIA_ENDPOINT`: HTTPS S3-compatible endpoint. Default uses the AWS region endpoint. `MEDIA_TEST_ALLOW_HTTP_LOCAL` is honored **only** with `NODE_ENV=test` and localhost endpoints.

New uploads require **both** the storage settings above and explicit `MEDIA_ENABLE_UPLOADS=true` after bucket permissions, quarantine scanning and production security checks are complete. Otherwise `GET /api/capabilities` reports `{"mediaReady":false}` and the upload API returns 503. The normal text chat remains usable.

## Server flow

1. Authenticated sender calls `POST /api/media/init` with `{to,mime,bytes,filename}`. Allowed: JPEG, PNG, WebP (8 MiB); MP4/AAC/OGG/MP3 audio (10 MiB); short MP4 video (25 MiB). Server verifies recipient/block state, sanitizes filenames, creates a random object key and returns a short-lived **signed POST upload policy** with server-side media-type and content-length conditions. Uploads are written directly to private S3, not Vercel or PostgreSQL.
2. Client uploads the selected bytes using the returned URL and **all** signed form fields. Call `POST /api/media/:id/complete` afterward. Server performs authenticated S3 HEAD and refuses mismatched MIME/byte lengths. Do not trust caller-provided `uploaded=true`.
3. Client calls `POST /api/media/:id/send` with a stable `clientMessageId` (UUID) and an optional caption (up to 1000 chars). Within one PostgreSQL transaction, server verifies media ownership, upload completion, recipient block state and that the asset was not already claimed, then writes the direct message and claims the asset. Stable IDs prevent retry duplicates. Legacy clients can still read the placeholder text and will ignore the optional `attachmentId` until their media UI is updated.
4. Authenticated `GET /api/media/:id/download` issues a **90-second signed download URL** only to its uploader or its recipient **after the asset has been sent**. No public listing or permanently reusable URLs.

`media_assets` contains only access metadata. Media bytes remain in private object storage, never Base64 inside the database. Existing chat messages and read receipts remain compatible with optional attachment IDs.

## Security and rollout requirements

- Restrict S3 permissions to exactly the configured bucket/prefix. Enable bucket access logs, encryption at rest, restricted CORS/origin policies as needed and lifecycle cleanup of expired, unclaimed objects and metadata. Upload form policies limit sizes, but cloud storage can still incur costs without provider-level per-account limits.
- **Metadata checks are not file-content verification.** Before public launch, perform server-side MIME magic-byte validation and automated safety scanning in a quarantine bucket, then promote scanned immutable objects to a delivery bucket. Presigned uploads may be replaced within their validity window unless the provider enforces additional object-immutability rules.
- Do not auto-open downloaded media in embedded browser content. Download URLs can be forwarded during their short validity period. This design is **not** end-to-end encrypted.
- Abuse prevention needs per-user distributed rate limits and a report/block moderation flow; current in-process rate limits are only a development guard.
- A production rollout requires configuring private storage, validating the signed POST with the **actual** provider, integrating Android's Photo Picker/SAF and microphone permission, verifying real-device playback, and a consistent release signature for APK updates.

## Automated coverage

`server/test/media.integration.test.js` starts the Lumo API with a local mock S3 storage server. It checks upload policy conditions, size/type limits, authorization, blocked recipients, HEAD confirmation, send idempotency, persistence, signed-link access before/after sending and unauthorized reads. It cannot substitute for a real S3/CORS or malware-scanning acceptance test.

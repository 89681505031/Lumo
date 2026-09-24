# Lumo Phase 12 — private documents and attachment storage

This branch is stacked on linked-replies PR #39. It connects the existing Android photo/video/voice composer to a private S3-compatible staging backend and expands it with a deliberately small document allowlist.

## Supported attachment types

- Images: JPEG, PNG, WebP — up to 8 MiB.
- Video: MP4 — up to 25 MiB.
- Voice/audio: M4A/MP4 — up to 10 MiB.
- Documents: PDF and DOCX/XLSX — up to 15 MiB, PPTX — up to 20 MiB, TXT — up to 2 MiB.
- ZIP/RAR/APK/EXE, legacy Office binaries and macro-enabled Office formats are intentionally not accepted by this milestone.

## Privacy and storage model

- Media support stays disabled unless the server has private storage credentials **and** `MEDIA_ENABLE_UPLOADS=true`.
- Android never receives long-lived bucket credentials. The server creates a random object key and a five-minute presigned POST with exact MIME and size policy.
- The bucket must be private. Sender/recipient receive only 90-second signed GET URLs after authorization.
- A recipient cannot obtain a download link before the sender has committed the uploaded asset into a message. Unrelated authenticated users never receive a signed URL.
- Normal message/history queries return only the attachment UUID; they do not expose object keys or storage credentials.
- Server filenames are normalized and forced to a canonical allowlisted extension when the supplied name/extension does not match the MIME.
- Download responses are signed with `Content-Disposition: attachment`. Android documents are handed to a user-selected external viewer/browser only after the user taps the attachment; Lumo does not execute document contents.

## Android behavior

- Photo/video still uses Android Photo Picker and voice recording still requires explicit microphone permission.
- A new `Документ` action uses Android's system Open Document picker and requests only PDF/TXT/DOCX/XLSX/PPTX MIME types.
- The client checks the content provider MIME and known file size before upload. The server repeats authoritative MIME/size validation.
- Exact attachment/message IDs are persisted before the send confirmation, so a lost response can be retried without creating a duplicate message.
- The actual document bytes are not copied into the encrypted offline chat-history cache. Offline history keeps only the attachment ID and message caption/placeholder.

## Staging requirements before production

1. Configure a dedicated private S3-compatible staging bucket and least-privilege service credentials outside GitHub/Android.
2. Verify bucket CORS allows only the required signed POST/GET behavior and no anonymous reads/listing.
3. Test PDF/DOCX/XLSX/PPTX/TXT plus image/video/voice on two physical Android phones and independent networks.
4. Verify expired upload/download signatures, interrupted uploads, retry idempotency and storage cleanup for abandoned unclaimed objects.
5. Add malware/content scanning or quarantine before broad document rollout. This milestone uses a strict type allowlist and external viewers but does **not** claim server-side malware scanning.
6. Define retention/deletion/storage-quota policy and ensure account/message deletion eventually removes the corresponding private objects.
7. Keep production disabled until these staging checks pass.
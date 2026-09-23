# Lumo Android: photo/video and voice-message composer

This is an Android **development branch** stacked on private-media backend PR #15. The UI is deliberately hidden until the production server returns `{"mediaReady":true}` at `GET /api/capabilities`; backend uploads are disabled by default. Do not hand an APK from this branch to users as a working media release yet.

## User interface

- Inside an existing direct chat, Photo Picker lets a user select a photo or short video; it requests no blanket read permission. The selection is capped to the exact type/size supported by the server.
- The voice button requests `RECORD_AUDIO` permission only after an explicit tap. The user can stop or cancel recording; recording is capped at 2 minutes (and the configured 10 MiB audio limit). The recorder uses AAC audio in an MP4 (`.m4a`) file. Unsent recordings live only in the app cache and are deleted when dismissed or when leaving the chat.
- The selected photo/video/audio is uploaded directly to a private S3-compatible bucket through a server-signed temporary POST form. The server then verifies the metadata via HEAD and binds the asset to a direct message in one PostgreSQL transaction.
- After the file is confirmed, the app persists its asset ID and a single UUID before sending the message. If the network response is lost, a visible retry button reuses the same ID, so repeating the operation does not create a duplicate message.
- Incoming attachment messages show a button which retrieves an authenticated short-lived read URL; recorded audio is played in-app through `MediaPlayer`. Photo/video are now displayed **inside Lumo** (images are decoded with dimension/byte limits; MP4 videos use an in-app VideoView), avoiding disclosure of short-lived signed URLs to an unrelated external viewer.

## Privacy and security gates before release

1. Production requires a real private bucket, provider-specific verification of POST signatures, strict object ACL/CORS, malware scanning and content-signature validation. The current server checks storage MIME/size metadata, **not** the file contents. Keep `MEDIA_ENABLE_UPLOADS` unset until these checks are complete.
2. Grant microphone permission only while recording. Test denial and later revocation. Verify the recorder releases the microphone when leaving a chat, canceling recording and when Android backgrounds the app. Do not auto-play recordings.
3. Image and video viewing stays in Lumo instead of forwarding signed URLs to external apps. The storage provider, Android platform media stack and the client process still receive the signed URL during its validity; do not expose it in logs or UI. Verify playback, cancellation and lifecycle on real Android devices.
4. Verify Android MediaProvider MIME types. Providers may not report exact file size; the UI rejects unknown sizes. Test image/jpeg/png/webp, mp4 video and m4a voice, over-limit files, corrupt files and files that change during upload.
5. Check expired signatures, disconnected networks during upload/completion/send, original message retries, blocked recipients, login/logout between attempts and attachment access from a third unrelated account.
6. Verify that existing plain-text messages still work before and after this feature is deployed. The feature should never make an established chat fail to load when storage is not configured.

## Acceptance checklist

- Android PR CI compiles successfully.
- Real signed-release APK is installed over a previous **same-key** version on two physical phones without deleting app data.
- Photos, voice notes and videos work both directions on independent mobile networks; playback survives screen rotation and interrupted connections.
- A nonparticipant cannot retrieve a signed download URL; an unsent attachment is available only to its owner. A blocked user cannot send a new attachment.
- Mobile OS privacy indicators and microphone permission controls behave as expected. Voice recording stops when canceled and when leaving chat.
- Server smoke test stays healthy; `GET /api/capabilities` remains false until the storage/security release gate is deliberately enabled.

This implementation does **not** claim E2E encryption or completed malware scanning.

# Lumo cosmic media integration — development only

This branch `feature/lumo-cosmic-media` is stacked on `feature/lumo-cosmic-personalization` (which itself depends on draft PR #27). It integrates the existing, successfully CI-built, but **not production-deployed** prototype from draft PR #16. It does **not** merge/deploy the separate private-media server PR #15 or any of the prerequisites under PR #2.

## What's actually wired up

- The live neon direct-message view retains existing text-chat network and retry logic.
- Each `Msg` can parse and merge an optional `attachmentId`. Old text messages remain compatible.
- The voice + visual composer is **hidden** until the selected server's `GET /api/capabilities` says `mediaReady:true`. A normal server that lacks this endpoint leaves text messaging unchanged.
- When enabled on a properly configured staging server, Photo Picker selects supported JPEG/PNG/WebP images and MP4 videos. This requires no broad photo-library permission. Voice recording requires an explicit button tap plus Android RECORD_AUDIO consent.
- Signed private POST upload, server completion and idempotent message send are reused from PR #16. An uploaded-but-not-confirmed attachment can be retried using a saved asset ID and client message UUID.
- An attached message offers authenticated on-demand playback in Lumo: decoded bounded photo, in-app video dialog and opt-in audio playback. Messages with ordinary text still display normally.
- A partly uploaded file and voice recording are **not** available offline as server-backed media; this version makes no such claim.

## Gates that must pass before switching the feature on

1. Merge/review prerequisite backend PR #2 and draft private-media PR #15 **in their proper order**, with PostgreSQL integration tests and access-control tests.
2. Provide **private** S3-compatible object storage and confirm POST signing, object ACL, MIME/signature validation, content scanning and expired/unauthorized link denial. Current media backend prototype does not scan uploaded file content. Until these gates pass keep MEDIA_ENABLE_UPLOADS off.
3. Test two physically separate Android phones on different networks with authorized, blocked and unauthorized users. Voice recording must stop on cancel/leave/background. No recording starts automatically.
4. Confirm mixed-version old/new clients still send, read and retry text messages; verify screenshots match the approved cosmic UI.
5. Only after the above, configure the staging `mediaReady` capability for testing; separately approve any production rollout.

The user-requested general document picker is **not** implemented by this integration because the private-media backend currently only accepts exact-listed image/video/audio MIME types. Do not add a visible 'all files' picker until server validation, safe download and malware scanning are available.

The known production conversation-list SQL issue is separate from these UI/media changes.

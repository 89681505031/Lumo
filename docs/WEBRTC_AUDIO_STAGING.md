# Lumo: staged, opt-in WebRTC audio prototype

**DRAFT ONLY.** Stacked on PR #18 → PR #17 → PR #2. Nothing here is merged or deployed. This branch builds an **experimental audio transport** inside the Android debug-only call lab. Video invitations remain UI-only: there is no camera or video transmission. Do not distribute the debug APK as a public update.

## Privacy & consent

- The microphone is **never** opened for a ringing or merely accepted call. After both parties accept the invitation, each must separately tap **Включить тест аудио**. Android then prompts for `RECORD_AUDIO` as needed.
- The Android debug manifest alone declares microphone/audio-route permissions; the release manifest does not request them.
- Capturing audio is stopped, WebRTC's peer connection is disposed, and the original audio-manager settings are restored when the test is stopped, the call ends or the user navigates away.
- ICE is `RELAY`-only, requires a privately provisioned TURN server and never falls back to an arbitrary third-party STUN service. TURN relays reduce direct exposure of users' network addresses to call peers but are not a complete anonymity solution.
- TURN credentials come from an authenticated, participant-only, accepted-call endpoint. They are generated using coturn's time-limited REST/HMAC mechanism; credentials and the `LUMO_TURN_SECRET` **must not be committed** or put in Android app configuration.

## Test server setup (human action required)

On an isolated staging deployment with database migrations and PR #17 code deployed:
- `LUMO_CALL_SIGNALING_ENABLED=true`.
- `LUMO_TURN_URLS` — 1–3 comma-separated `turn:` or `turns:` URLs for **your own** private coturn service, e.g. `turns:your-relay.example:5349?transport=tcp`. Prefer TLS relay where supported. Never paste real credentials into chat or Git.
- `LUMO_TURN_SECRET` — private random coturn REST API static authentication secret (minimum 32 characters); configure the matching secret on coturn. Do not use a permanent user/password pair in the Android client.
- Verify network reachability and TURN relay permissions. Secrets should be provisioned only in the staging hosting provider's private environment.

`GET /api/calls/:id/ice-config` returns up to 3 TURN servers, an expiring per-user, per-call username and HMAC credential **only after the call is accepted**. Nonparticipants cannot fetch it; blocked and inactive calls are rejected. If TURN is not configured the endpoint fails closed with `turn_unavailable`, and Android never starts recording.

## How to validate

1. Run server CI (two-process PostgreSQL integration tests check per-participant TURN credentials, HMAC derivation, early/late/unauthorized rejection). Run Android CI to verify Kotlin/WebRTC dependency and debug APK compilation.
2. On **two sacrificial test phones**, use two test accounts and the staging server (not the production database). Open the debug call tab on both devices.
3. Initiate an audio invitation, accept it, and explicitly enable the microphone on **both** phones. Confirm Android's microphone indicator activates only after each user's action.
4. Check SDP/ICE ordering, audio in both directions, speaker/earpiece route, permission denial, TURN TLS, different networks, reconnect, forced call end, and immediate microphone shutdown on navigation away. Confirm media **fails closed** if TURN is unavailable.
5. Also verify that no test tab or `RECORD_AUDIO` permission is present in a normal release manifest; do **not** replace any installed release with a debug APK.

## Known blockers

- Media transfer and device behavior are **not verified** just because Gradle/CI succeeds.
- No background ring/push wakeup, video streams, Bluetooth device selector, network handover, robust reconnection, recording or E2EE architecture is provided. WebRTC transports support DTLS-SRTP, but Lumo does **not** offer independently reviewed application-level end-to-end encryption.
- Signaling uses PostgreSQL-backed polling every ~1.4 seconds, not push; the caller and callee must both keep the debug lab open.
- Call lifetime is at most 30 minutes after acceptance and TURN credentials may last up to 35 minutes. An audit should cover metadata retention, TURN monitoring and operational cleanup before any user-facing release.
- The WebRTC AAR is currently a regular dependency in the draft branch; assess release APK size and production feature gating before merging.

Until the app passes real two-device tests and these blockers are resolved, **no public calling feature or APK update should be announced**.

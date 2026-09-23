# Lumo cosmic calls + push lab — staging-only milestone

This branch is stacked on the still-draft message-actions PR #33 and therefore inherits its unmerged design, groups, media and privacy prerequisites. It is **not a production deployment**.

## Call invitations

The Android app now has a cosmic **Звонки** laboratory reachable from Profile. It reuses the authenticated call-signaling contract designed in the existing draft call chain (#17 → #18 → #19 → #20):

- list current ringing/accepted calls;
- invite a selected Lumo user for an audio or video call;
- accept, decline or end the server-side call state;
- keep SDP/ICE client methods available for later reviewed WebRTC wiring.

The screen itself **does not open the microphone or camera and does not create a WebRTC media session**. It clearly says this in the UI. A server without `/api/calls` is treated as unsupported and the user sees an unavailable state rather than fake controls.

Real audio currently exists only in the separate debug WebRTC prototype branch and still requires explicit microphone consent plus private TURN configuration. Real video media is not implemented. Do not describe an accepted invitation as a working voice/video call until those media gates pass on two physical devices.

## Push notifications

The privacy-first notification client from the existing draft push chain (#13 → #21 → #22 → #23 → #24 → #25 → #26) is integrated as an **isolated debug staging path**:

- release builds compile a no-op `PushSettings` and contain no Firebase UI;
- a normal CI debug build has Firebase classes available for compilation, but `LUMO_FCM_CONFIGURED=false`, Firebase auto-init is disabled in the debug manifest, and no token is fetched;
- an owner-controlled staging build requires **both** `-PlumoEnableFcm=true`, a different HTTPS `-PlumoStagingUrl=...`, and a local `android/app/google-services.json`;
- when explicitly configured, Android asks for notification permission only after the user taps the Profile control;
- the server registration is session-scoped, local opt-out is immediate, and offline revocation is retried by WorkManager;
- incoming FCM must be data-only `kind=lumo_message`; the client always renders the generic text **“Lumo — Новое сообщение”**, never sender name or message content;
- app-wide/channel permission withdrawal is treated as opt-out and schedules authenticated revoke;
- logout clears the local opt-in state.

The staging push backend, Firebase project, service credentials and cron dispatcher are **not** included or enabled by this branch. They remain in the separate draft server chain. The normal public APK built by CI cannot activate FCM on its own.

## Build isolation

`BuildConfig.LUMO_HTTP_BASE` and `LUMO_WS_BASE` default to the existing production addresses. An explicit staging Gradle property changes the debug application ID to `app.lumo.staging` so it cannot silently overwrite the production package while testing experimental infrastructure.

Android CI runs debug/release unit tests plus `assembleDebug`. Server CI continues to exercise the reaction PostgreSQL tests inherited from PR #33. No call/push server deploy occurs.

## Acceptance before any production merge

1. Review the prerequisite stacked PRs instead of merging this branch directly to `main`.
2. Deploy call and push backends only to a separate staging hostname/database first.
3. Test two Android phones on independent networks: call invitation state, accept/decline/end, session expiry, blocked users and repeated requests.
4. For audio, use the separate TURN/WebRTC branch with explicit mic permission and verify microphone release on cancel/background. Video requires new work.
5. For push, use a separate Firebase project for `app.lumo.staging`; verify generic lock-screen content, app/channel permission withdrawal, token rotation, offline disable, logout and invalid-token cleanup.
6. Verify release build has no notification opt-in UI and cannot initialize Firebase.
7. Do not confuse this milestone with the separate old-production `/api/conversations` deployment issue.

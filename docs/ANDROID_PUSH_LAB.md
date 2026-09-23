# Android FCM opt-in lab — staging only

**Status: debug-only engineering preview.** Stacked on PR #21 → PR #13 → PR #2. No Firebase project, deploy, real phone verification, permission grant or production push delivery has been configured by this change. Do **not** distribute the debug APK as a public app update.

## Safe build variants

- The normal release variant has no Firebase Messaging dependency, no FCM service, no notification toggle, and `BuildConfig.LUMO_FCM_CONFIGURED=false`.
- The normal CI debug APK builds without `google-services.json`. Its Profile displays **"Firebase ... не настроен"**, cannot request notification permission or fetch a token, and uses the existing production API without enabling push.
- A specially configured **staging** debug build uses a dedicated HTTPS API, `wss:` websocket, a separate Android application ID `app.lumo.staging`, and the explicit `Lumo Debug` app label. This prevents accidentally overwriting the real app or reusing its local session/preferences.
- Firebase auto token generation is disabled at manifest level. Neither token retrieval nor the Android 13+ `POST_NOTIFICATIONS` permission request happens without the user's explicit action in Profile. Token registration uses the logged-in staging session and is never logged or added to a URL.

## Owner setup (private, not performed automatically)

1. Deploy PR #21's **server dependencies** to a dedicated staging hostname and database only after migrations/tests. Create a separate Firebase project and add Android application **`app.lumo.staging`**. Download that project's `google-services.json` locally to `android/app/google-services.json` (gitignored). It contains app identifiers, not a service-account private key; nevertheless, keep the staging configuration out of this Git branch.
2. To assemble only on the owner's machine or a protected staging CI job, run from `android/`:

   `gradle assembleDebug -PlumoStagingUrl=https://YOUR-STAGING-HOST -PlumoEnableFcm=true`

   Without both explicit properties, or without `google-services.json`, token registration is unavailable. Do not substitute the production API as a staging endpoint. A clean Firebase-enabled staging build must verify that the downloaded config matches package `app.lumo.staging`.
3. For the staging backend only, set a Firebase Admin service identity using Application Default Credentials, `FIREBASE_PROJECT_ID`, and an unguessable `CRON_SECRET` in **private hosting environment variables**. Do not check credentials into Git or paste them in chat.
4. Configure an authenticated external HTTPS scheduler to call `GET /internal/push-dispatch` using `Authorization: Bearer <CRON_SECRET>`. Turn on `LUMO_PUSH_DELIVERY_ENABLED=true` only after device opt-in and provider credentials have been tested; the worker is off by default.
5. Sign into the staging Android app on two devices. On the receiver's Profile tab, tap **Включить тестовые уведомления**. The app requests runtime permission on Android 13+, then explicitly enables Firebase, obtains a token, registers it via `POST /api/devices/push` with the current staging login session, and only then saves the user/account/session-scoped local preference. On failure, automatic token creation is disabled again.
6. Send a message from the other test account. Verify the receiver's lock screen contains **only** “Lumo — Новое сообщение” without sender or message content. Test foreground and background behavior on both phones with Google Play services. Test revoked Android permission, invalid tokens, user blocks, already-read messages, token rotations, simultaneous sign-outs, offline disables, and repeated staging server restarts.
7. Disable through Profile: Android first revokes the server registration, then clears local consent, disables auto initialization and requests local token deletion. If the server is offline, **disabling cannot be confirmed remotely**; the UI shows the error and retains the preference until the request succeeds. Users can also revoke the Android notification permission directly.
8. Successful server logout revokes the session and cascades the stored FCM token + pending jobs. Local-only logout synchronously drops app consent and attempts token deletion, but **cannot guarantee immediate remote revocation while offline**. Do not regard local-only logout as a production privacy-safe replacement for authenticated server logout.

## Engineering acceptance gates

- Verify normal debug Android CI builds **without** staging Firebase JSON.
- Compile the release Kotlin variant and verify its merged manifest contains **neither** `POST_NOTIFICATIONS` nor `LumoDebugFirebaseService`.
- A separate protected CI job may later inject a dedicated staging Firebase config and build with the two explicit Gradle properties; do not add credentials to public pull-request workflow contexts.
- Test background notifications on actual Google Play-enabled Android devices, including what happens when a user turns OS permission off. Existing FCM notification payloads are generic and receive **no end-to-end encrypted message content**.
- Do not merge into `main` until backend and client acceptance tests, a reviewed signed release process and security audit pass.

Up-to-date platform guides: the official Firebase Android setup and FCM Android getting-started documentation.

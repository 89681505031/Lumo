# Android FCM opt-in lab — staging only

**Status: debug-only engineering preview.** Stacked on PR #21 → PR #13 → PR #2. No Firebase project, deploy, real phone verification, permission grant or production push delivery has been configured by this change. Do **not** distribute the debug APK as a public app update.

## Safe build variants

- The normal release variant has no Firebase Messaging dependency, no FCM service, no notification toggle, and `BuildConfig.LUMO_FCM_CONFIGURED=false`.
- The normal CI debug APK builds without `google-services.json`. Its Profile displays **"Firebase ... не настроен"**, cannot request notification permission or fetch a token, and uses the existing production API without enabling push.
- A specially configured **staging** debug build uses a dedicated HTTPS API, `wss:` websocket, a separate Android application ID `app.lumo.staging`, and the explicit `Lumo Debug` app label. This prevents accidentally overwriting the real app or reusing its local session/preferences.
- A debug-only Android **WorkManager** reconciliation job (network-connected, exponential backoff) resumes pending push revocations after offline failures, app restarts and network recovery, and registers the latest token after consented token rotation. It never places Firebase or login tokens in WorkManager's persisted input data; it reads the CURRENT session from existing private app preferences. It cannot revoke a registration if the user locally deletes their session while offline, force-stops/uninstalls the app, or never reconnects.
- Generic notifications now share one cancellable notification ID. Disabling push or logging out immediately removes the existing generic notification from the notification tray, not just future notifications.
- Firebase auto token generation is disabled at manifest level. Neither token retrieval nor the Android 13+ `POST_NOTIFICATIONS` permission request happens without the user's explicit action in Profile.
- **New safety rule:** the staging sender uses only a data-only FCM envelope (`kind=lumo_message`), never an FCM `notification` payload. Android's notification payloads can bypass the app when backgrounded; data-only messages are handled by our service, which first re-checks the local account's opt-in and Android permission. Unknown message types are ignored. Token registration uses the logged-in staging session and is never logged or added to a URL.

## Owner setup (private, not performed automatically)

1. Deploy PR #21's **server dependencies** to a dedicated staging hostname and database only after migrations/tests. Create a separate Firebase project and add Android application **`app.lumo.staging`**. Download that project's `google-services.json` locally to `android/app/google-services.json` (gitignored). It contains app identifiers, not a service-account private key; nevertheless, keep the staging configuration out of this Git branch.
2. To assemble only on the owner's machine or a protected staging CI job, run from `android/`:

   `gradle assembleDebug -PlumoStagingUrl=https://YOUR-STAGING-HOST -PlumoEnableFcm=true`

   Without both explicit properties, or without `google-services.json`, token registration is unavailable. Do not substitute the production API as a staging endpoint. A clean Firebase-enabled staging build must verify that the downloaded config matches package `app.lumo.staging`.
3. For the staging backend only, set a Firebase Admin service identity using Application Default Credentials, `FIREBASE_PROJECT_ID`, and an unguessable `CRON_SECRET` in **private hosting environment variables**. Do not check credentials into Git or paste them in chat.
4. Configure an authenticated external HTTPS scheduler to call `GET /internal/push-dispatch` using `Authorization: Bearer <CRON_SECRET>`. Turn on `LUMO_PUSH_DELIVERY_ENABLED=true` only after device opt-in and provider credentials have been tested; the worker is off by default.
5. Sign into the staging Android app on two devices. On the receiver's Profile tab, tap **Включить тестовые уведомления**. The app requests runtime permission on Android 13+, then explicitly enables Firebase, obtains a token, registers it via `POST /api/devices/push` with the current staging login session, and only then saves the user/account/session-scoped local preference. On failure, automatic token creation is disabled again.
6. Send a message from the other test account. Verify the receiver's lock screen contains **only** “Lumo — Новое сообщение” without sender or message content. Test foreground and background behavior on both phones with Google Play services. Test revoked Android permission, invalid tokens, user blocks, already-read messages, token rotations, simultaneous sign-outs, offline disables, and repeated staging server restarts.
7. Disable through Profile: Android **immediately** disables local consent, auto-init and existing generic notification. The explicit DELETE is serialized with ongoing token registration. If offline, a session-scoped pending revoke is stored, and WorkManager schedules authenticated deletion when the device reconnects (manual **Повторить отключение** remains available). Server deletion is not guaranteed until an authenticated request succeeds. If Android permission is removed in Settings, the service independently refuses to post notifications.
8. Successful server logout revokes the session and cascades the stored FCM token + pending jobs. Local-only logout synchronously drops app consent and attempts token deletion, but **cannot guarantee immediate remote revocation while offline**. Do not regard local-only logout as a production privacy-safe replacement for authenticated server logout.

## Engineering acceptance gates

- Verify normal debug Android CI builds **without** staging Firebase JSON.
- Run `testDebugUnitTest` and `testReleaseUnitTest`; exercise the revoke-before-register decision table. Compile the synthetic FCM staging variant with WorkManager included; the release APK must not contain Firebase or WorkManager.
- Compile the release Kotlin variant and verify its merged manifest contains **neither** `POST_NOTIFICATIONS` nor `LumoDebugFirebaseService`.
- A public pull-request CI job uses a deliberately **synthetic, nonworking** Firebase `google-services.json` solely to verify that the explicit staging Gradle path compiles and its debug manifest includes the debug service/permission. This cannot verify real FCM token generation, connectivity or delivery. A separate protected job may later use the real staging Firebase configuration (never on untrusted public PRs).
- Test background notifications on actual Google Play-enabled Android devices, including permission revocation and offline opt-out. The new data-only payload includes no message content or sender ID. With normal FCM priority it **can be delayed in Android Doze**, and OS force-stop may prevent delivery until the user opens the app. This is best-effort, not guaranteed real-time push.
- Do not merge into `main` until backend and client acceptance tests, a reviewed signed release process and security audit pass.

Up-to-date platform guides: the official Firebase Android setup and FCM Android getting-started documentation.

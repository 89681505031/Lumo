# Lumo Phase 21 — privacy-first push on the current stack

This stacked branch reconnects the existing Android debug/staging push opt-in UI to the current Lumo backend and extends the same privacy model to private groups.

## Payload privacy

- FCM receives one data-only payload: `{kind:"lumo_message"}`.
- The provider payload contains **no message text, sender identity, direct-chat peer, group ID, group title, message ID or attachment metadata**.
- There is no FCM `notification` payload. The debug Android service constructs the visible generic notification only after re-checking local account consent and Android notification permission/channel state.
- The visible text remains only **“Lumo — Новое сообщение”** for both direct and group activity.

## Consent and registration

- Android release builds still do not link Firebase or expose the staging opt-in UI.
- Debug Firebase is available only in a separately packaged staging app with explicit Gradle flags, a dedicated HTTPS staging backend and a locally supplied `google-services.json`.
- Device registration is session-scoped. Logging out deletes the session and cascades the push registration/outbox rows.
- Explicit local opt-out takes effect before networking. WorkManager retries authenticated server revocation if the device is temporarily offline.
- FCM token rotation is serialized and one physical token is moved away from an older Lumo session/account before the new registration commits.

## Direct-message queue rules

- A PostgreSQL trigger creates a content-free outbox job only for a recipient session that currently has a registered push device.
- Before provider dispatch, the worker re-checks the live session/device registration.
- A direct job is dropped when the message is already read, deleted, blocked in either direction, stale, or the device/session consent is gone.

## Private-group queue rules

- A group trigger enqueues only for current members whose `joined_at` is at or before the new group message and excludes the sender.
- Before provider dispatch, current membership is checked again.
- Removal immediately makes an already queued group job ineligible.
- Rejoining later does not resurrect an older queued group job because the new `joined_at` is later than that message.
- Deleted group messages do not dispatch.
- Group title/member/sender information never enters the FCM payload.

## Delivery worker

- Push delivery is **off by default**.
- `/internal/push-dispatch` returns unavailable unless `LUMO_PUSH_DELIVERY_ENABLED=true`, `FIREBASE_PROJECT_ID` is configured, PostgreSQL is available, and `CRON_SECRET` is at least 32 bytes.
- Firebase Admin uses Application Default Credentials. Service-account private keys are not stored in the repo or Android app.
- Jobs use a short lease, bounded retries, exponential backoff and fencing so a stale worker cannot erase a newer token registration.
- Definitively invalid FCM registrations are removed; temporary provider errors are retried.
- Queue metadata is deleted after one day and carries no message content.

## Production gates

1. Use a dedicated staging Firebase project/application and isolated staging backend/database first.
2. Test opt-in, background delivery, token rotation, offline opt-out, Android 13+ permission changes, app/channel switches and logout on physical Google Play-enabled devices.
3. Verify direct read-before-dispatch and group remove/rejoin behavior across multiple server instances.
4. Keep release Firebase disabled until security/privacy review and signed-release acceptance are complete.
5. Do not claim guaranteed real-time delivery: normal-priority data messages can be delayed by Android Doze or force-stop behavior.
6. This feature does not add E2E encryption and must not be presented as such.

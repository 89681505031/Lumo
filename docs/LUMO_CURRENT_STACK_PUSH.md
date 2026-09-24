# Lumo Phase 21 — privacy-first direct and group push on the current stack

This stacked branch connects Lumo's existing debug/staging Android push opt-in flow to the current server feature chain. Provider delivery remains disabled by default.

## Privacy model

- Android opt-in remains **explicit and session-scoped**. Normal release builds do not auto-enable Firebase.
- FCM payloads are data-only and contain only `{ kind: "lumo_message" }` plus the provider token needed by Firebase.
- Push payloads contain no message text, sender name, username, group title, attachment metadata, message ID or group ID.
- Android still renders only the generic notification `Lumo — Новое сообщение` after re-checking local consent and Android notification permission/channel state.
- Disabling notifications locally happens before network revocation; WorkManager retries the authenticated server revoke when connectivity returns.

## Server registration

- `POST /api/devices/push` binds one Android FCM token to the current authenticated session.
- The same physical token is serialized with a PostgreSQL advisory lock and transferred away from an older Lumo account/session instead of remaining registered twice.
- `DELETE /api/devices/push` removes only the caller's current session registration.
- Logout/session deletion cascades device registration and pending jobs for that session.

## Durable outbox

- PostgreSQL triggers enqueue jobs after new direct messages and new private-group messages.
- The outbox stores only database identifiers, recipient/session IDs and delivery metadata — never message/group content.
- Direct messages enqueue only for the direct recipient.
- Group messages enqueue only for current group members other than the sender.
- Every worker attempt re-checks the current session/device registration immediately before contacting Firebase.

## Stale-notification suppression

- Direct jobs are dropped if the message was read, deleted, either participant blocked the other, the session expired, or push registration was revoked.
- Group jobs are dropped if the message was deleted, the recipient is no longer a current member for that message's membership window, the sender/recipient block relation exists, the session expired, or registration was revoked.
- Jobs older than ten minutes are dropped.
- Invalid FCM registrations are removed with lease/attempt/device-row fencing so a slow worker cannot delete a newer replacement token.

## Delivery gate

- `/internal/push-dispatch` is unavailable unless all of these are explicitly configured: `LUMO_PUSH_DELIVERY_ENABLED=true`, `FIREBASE_PROJECT_ID`, database access, and a private `CRON_SECRET` of at least 32 bytes.
- Firebase Admin uses application-default credentials at runtime; no service-account key is committed to GitHub or shipped in the APK.
- The scheduled dispatcher processes bounded batches and exponential retry metadata. It never logs push tokens or provider payload bodies.

## Compatibility

- The migration can absorb the earlier experimental direct-only `push_outbox.message_id` schema by backfilling it into the unified direct/group outbox.
- The Android debug/staging push client already uses `/api/devices/push` and the same generic `lumo_message` payload kind, so no production Android behavior changes are required by this server milestone.

## Before production

1. Use a dedicated Firebase staging project and application-default credentials outside the repository.
2. Test explicit opt-in/opt-out, token rotation, account switching, logout, Android permission/channel disable and offline revoke on physical phones.
3. Verify generic direct and group notifications across Doze/background conditions without message/sender/group text leakage.
4. Add operational monitoring for outbox backlog, retries and invalid-token rates without logging tokens.
5. Define notification frequency/quiet-hour product policy before enabling group push broadly.
6. Keep release Firebase disabled until the staging privacy/permission review is complete.
# Lumo Phase 23 — private media quota and abandoned-upload cleanup

This stacked branch hardens the existing private S3-compatible attachment system against reservation spam and abandoned uploads. It does **not** change who can read a claimed attachment.

## Per-account outstanding reservation quota

- Before a new direct/group upload reservation is committed, PostgreSQL serializes the owner's quota check with a transaction-scoped advisory lock.
- Each account may have at most **8 active unclaimed reservations** at once.
- Active unclaimed reserved bytes are capped at **75 MiB** per account.
- A reservation stops counting after it is successfully linked to a direct/group message or after its database expiry passes.
- The server returns `429 media_quota_exceeded` rather than silently creating more private bucket reservations.

These limits cover only outstanding/unclaimed uploads. They are not a long-term account storage quota yet.

## Abandoned object cleanup

- `mediaStore.cleanupAbandoned()` finds only assets whose reservation expired and that have never been claimed by a direct or group message.
- The worker deletes the private bucket object with `DeleteObject`, then removes the metadata row only if it is still expired/unclaimed.
- S3 deletion is idempotent, so a reservation whose upload never happened can be cleaned safely as well.
- Claimed attachments are deliberately excluded. Their retention/deletion policy still needs a separate product decision.

## Scheduler endpoint

- `GET /internal/media-cleanup` is unavailable unless private media is enabled, storage credentials exist, and `CRON_SECRET` is at least 32 bytes.
- The endpoint requires a constant-time checked Bearer `CRON_SECRET` and processes at most 100 candidates per run.
- It returns only aggregate counts (`scanned`, `deleted`, `failed`), never file names, user IDs, object keys or message content.

## Tests

- Fake S3 integration coverage verifies unauthorized cleanup denial.
- Eight unclaimed reservations succeed, the ninth is rejected with 429.
- After those reservations are forced expired, the secret-protected cleanup removes the rows/objects and quota capacity becomes available again.
- Existing direct/group attachment authorization, idempotency and membership-window tests continue to run unchanged.

## Still required before production

1. Define long-term claimed-media retention and account storage quotas.
2. Add malware/quarantine scanning before broad document rollout.
3. Add metrics/alerts for cleanup failures, bucket size and per-account usage.
4. Run cleanup from an authenticated private scheduler in isolated staging.
5. Verify real provider DeleteObject behavior and IAM permissions against the chosen S3-compatible service.
6. Decide whether message/account deletion should trigger immediate object deletion or retention-window deletion.
# Lumo Phase 24 — safe claimed-media lifecycle and group object cleanup

This stacked branch closes two private-storage leak paths without enabling destructive retention by default.

## Claimed media garbage collection

- `/internal/media-cleanup` still removes expired unclaimed upload reservations exactly as before.
- It can now also remove **claimed** media only when every direct/group message reference is deleted and the object is older than an explicit retention window.
- Claimed-media deletion is **disabled by default**. Set `MEDIA_UNREFERENCED_RETENTION_DAYS` to an integer from 1 to 3650 only after a retention policy is approved.
- The cleanup re-checks eligibility inside a PostgreSQL transaction after deleting the S3 object, clears attachment IDs only from deleted message tombstones, and then removes the `media_assets` row.
- A non-deleted direct or group message always blocks claimed-media garbage collection.
- Existing cleanup response counters remain backwards-compatible; the new `unreferenced` section reports whether policy-driven cleanup was enabled.

## Group deletion

- Group media rows use a database cascade path that could otherwise erase metadata before S3 objects were removed.
- The group-delete API now checks ownership, counts private assets, deletes every group object from private storage, removes media rows, and only then deletes the group.
- If a group still has private objects but storage cleanup is unavailable, group deletion fails safely instead of leaking unreachable objects.
- S3 DeleteObject calls are retry-safe; if DB cleanup fails after an object delete, a later attempt can finish the metadata cleanup.

## Privacy and data-loss guardrails

- There is no automatic claimed-media retention change unless the operator explicitly configures one.
- Sender-deleted message tombstones remain in chat history; after the retention window their `attachmentId` is cleared because the private bytes no longer exist.
- Unrelated or still-live message references prevent deletion.
- Storage credentials remain server-only and the cleanup endpoint remains protected by `CRON_SECRET`.

## Tests

- Integration coverage verifies group deletion removes the backing private object before DB cascade.
- It verifies an old claimed attachment with only deleted message references is removed after the configured 30-day test window.
- It verifies the owner can access that attachment before retention cleanup and receives 404 afterward.
- Existing abandoned-reservation quota cleanup remains covered and backwards-compatible.

## Before production

1. Choose and document the real claimed-media retention period; leave `MEDIA_UNREFERENCED_RETENTION_DAYS` unset until then.
2. Confirm backups/legal retention expectations before enabling destructive cleanup.
3. Add storage/object-count monitoring and alerts for cleanup failures.
4. Run staging cleanup against disposable test objects before scheduling it in production.
5. Verify group deletion and multi-forward attachment deletion on physical devices and across multiple app-server instances.
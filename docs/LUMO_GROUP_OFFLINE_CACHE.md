# Lumo Phase 22 — encrypted offline private groups

This stacked branch extends the existing Android Keystore AES-256-GCM offline cache from direct chats to private groups without caching media bytes or signed URLs.

## Cached on the device

- Up to 100 recent private group summaries for the signed-in Lumo account.
- Up to 150 recent messages per group, including linked-reply preview metadata, edit/delete tombstones and attachment UUIDs.
- One pending group text send with its stable client message UUID and linked-reply metadata.
- One pending group attachment **metadata** record (asset UUID, client message UUID, caption and filename) so an uncertain final-send response can be retried.

## Never cached by this feature

- Session/login tokens.
- Raw photo/video/audio/document bytes.
- Presigned upload/download URLs.
- S3 credentials or object keys.
- AI prompts or provider secrets.

## Encryption / account isolation

- Group cache blobs use the same per-account Android Keystore AES-256-GCM key as the direct-chat offline cache.
- File names use hashes of account/group identifiers rather than raw usernames or group titles.
- Legacy group pending text/media metadata stored in SharedPreferences is migrated once into encrypted files and then removed from the legacy preference entry.
- `clearAccount` already removes every file under the account-specific encrypted prefix and deletes that account's Keystore key.

## Offline UX

- The group list opens from its encrypted cache when the server cannot be reached.
- A group room opens recent encrypted message history immediately when available, then refreshes from PostgreSQL every five seconds when connectivity returns.
- WebSocket edits/new messages and local edit/delete/send state update the encrypted history cache through the same `history` state.
- If the server positively confirms `group_not_found` (for example after removal), Lumo clears that group's history/pending cache and removes it from the cached group list instead of continuing to expose stale private history.
- Explicit leave and owner deletion also clear the cached group immediately.

## Attachment behavior

- Offline history can show that a message has an attachment UUID, but the file itself is not cached here.
- Opening an attachment still requires an authenticated short-lived signed download from the server.
- A pending already-uploaded group attachment can retain only the encrypted retry metadata needed to repeat the final commit; original file bytes are not copied into the offline cache.

## Production checks still required

1. Airplane-mode start, reconnect and account-switch tests on physical Android devices.
2. Keystore invalidation/corrupt-ciphertext recovery tests; a corrupt blob should delete only itself and never affect server history.
3. Leave/remove/rejoin tests proving old membership-period cache is cleared after confirmed server removal.
4. Storage/battery/performance tests with 100 groups × 150 cached messages.
5. Decide separately whether downloaded media should ever get an opt-in encrypted offline cache; this phase intentionally does not cache it.

This is device-at-rest encryption, **not end-to-end encryption**.
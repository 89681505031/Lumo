# Lumo Phase 7 — encrypted offline recent-history cache

This draft branch is stacked on the still-draft calls/push lab PR #34. It does not deploy a server or modify production data.

## What works in this milestone

- After a successful authenticated `/api/conversations` response, Android stores up to **100 recent direct-chat summaries** in app-private storage.
- After a successful authenticated direct-message history sync (and after local live updates), Android stores up to **150 recent messages per opened direct chat**.
- When the network/history endpoint is unavailable, Chats and Chat can show the most recently encrypted local copy instead of an empty screen.
- The UI clearly marks cached/offline content. A later successful authenticated sync replaces the cached view and refreshes the local ciphertext.
- Profile shows an **Офлайн-история** card with the number of encrypted cache files and a control to delete all cached history for the currently signed-in Lumo account.

## Encryption and privacy boundary

- Each Lumo account gets a separate **AES-256-GCM key generated inside Android Keystore**. The key is not written to files or preferences.
- Cache blobs are written atomically in the app-private files directory and include a random 12-byte GCM IV plus authenticated ciphertext.
- File/key names are derived from SHA-256 digests of Lumo user IDs rather than raw usernames or message text.
- The encrypted offline files never store the login/session token, Firebase token, signed media URL, downloaded image/video/audio bytes, call signaling secrets or push credentials.
- Cached message records may contain text, timestamps, delivery/read state, client message UUID and an attachment **ID** so the online viewer can request an authorized asset later.
- A corrupt/undecryptable blob fails closed and is deleted locally; PostgreSQL/server history remains authoritative.

This protects recent cached history **at rest on this Android device**. It is not end-to-end encryption, does not protect text while it is displayed on an unlocked device, and does not prevent a conversation peer or server from having their own copy.

## Important limitations

- Offline media playback is not implemented. An attachment ID can appear in cached history, but opening the media still requires the authenticated server.
- Search is limited to the already cached chat list and currently loaded conversation; there is no full local database/index yet.
- Existing per-chat unsent-message retries are migrated from the old local JSON preference into an encrypted per-peer AES-GCM file. Future queue updates prefer encrypted storage. If Android Keystore/storage itself fails, the client retains the legacy local queue as a reliability fallback instead of discarding an unsent message.
- Group history is not cached by this change.
- A user who clears Android app storage or uninstalls the app loses this local history/key. That does not delete server-side messages.

## Release checks before merge

1. Android debug/release unit tests and APK build must pass.
2. Test on a physical Android device: online sync → airplane mode → app restart → cached chat list/history → reconnect → authoritative refresh.
3. Verify two different Lumo accounts on the same phone do not read each other's cache and clearing one account does not remove another account's cache.
4. Test corrupt ciphertext/key invalidation and confirm the app falls back to server without crashing.
5. Verify media bytes and signed URLs are absent from files; only attachment IDs may remain.
6. Measure UI latency around first Android Keystore key generation and cache writes on a lower-end phone.
7. Do not merge this stacked branch directly to `main` before its prerequisite drafts are reviewed.
# Lumo local privacy controls

The group staging branch adds two independent, opt-in, device-specific privacy settings. They are **not encryption, server permission changes or moderation controls**.

- **Hide chat-list previews** replaces the latest text in the Chats list with a generic "Содержимое скрыто"; it does not hide text inside an opened conversation, notifications, system logs or network messages.
- **Screenshot protection** opts into Android WindowManager FLAG_SECURE when an account is signed in, which restricts screenshots/recording and obscures snapshots in recent apps on supported Android versions. It cannot stop photos from a second camera, all platform or hardware behavior, or server access.
- The settings persist in app-private SharedPreferences keyed by a SHA-256 digest of the signed-in user ID so separate accounts on the same phone do not inherit each other's settings.
- Both protections default to **off**. When signing out, screenshot protection clears automatically and the next account's setting applies on login.
- Turning on FLAG_SECURE prevents screenshots for visual feedback; disable temporarily with the user's consent when testing the UI.

These settings do not modify production infrastructure or transmit preference data to the backend. Group features remain server-gated until draft PostgreSQL backend prerequisites pass their own checks.

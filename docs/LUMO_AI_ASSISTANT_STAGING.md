# Lumo Phase 8 — opt-in AI assistant staging

This branch is stacked on the encrypted-offline-cache PR #35. It is a development/staging feature and does not enable an AI provider on current production.

## Privacy model

- Lumo AI is a **separate screen**. The Android client sends only text typed inside that AI screen plus up to 8 earlier AI-screen messages.
- Direct chats, group chats, contacts, offline-cache contents, media, camera, microphone, location and notification data are **not read or attached automatically**.
- The server integration test stores a sentinel normal-chat message and verifies that sentinel never reaches the configured AI provider when the user asks an unrelated AI question.
- The Lumo login session token authenticates only the request to the Lumo server and is not included in the upstream AI request.
- The external provider credential exists only as a private server environment variable and is never returned to Android.
- The current AI thread is kept only in the screen's Compose state. This milestone does not persist AI prompts into PostgreSQL or the encrypted offline chat cache.

## Server feature gate

The feature is ready only when all of these private server settings are present:

- `LUMO_AI_ENABLED=true`
- `LUMO_AI_PROVIDER_URL=https://...` — an owner-controlled OpenAI-compatible chat-completions endpoint
- `LUMO_AI_PROVIDER_KEY=...` — private bearer credential
- `LUMO_AI_MODEL=...`

The provider URL must use HTTPS. Plain HTTP is accepted only for loopback addresses when `NODE_ENV=test` so CI can use a fake provider without weakening staging/production validation.

`GET /api/ai/capabilities` requires a valid Lumo session and reports whether the provider is actually configured. `POST /api/ai/chat` also requires authentication, is rate-limited, accepts one explicit message up to 2000 characters and at most 8 previous AI-thread items / 6000 history characters. Only `user` and `assistant` history roles are accepted.

The server adds a short system instruction that explicitly tells the provider not to claim access to private Lumo resources unless the user supplied that information in the AI conversation. Lumo does not log prompt/response bodies in this feature code.

## Android behavior

- Profile contains a **Lumo AI** card that opens the cosmic assistant screen.
- If the server capability is off, Android shows that the feature is unavailable and does not expose a fake working chat.
- When enabled, user messages and assistant answers use the existing neon/cosmic message language.
- A privacy card is always visible above the AI thread explaining that ordinary chats and device resources are not automatically shared.
- `Очистить` clears only the current in-memory AI session.

## Still required before production

1. Choose and contract a provider, define data-retention/training terms, and configure it only on an isolated staging server first.
2. Review provider moderation/safety and age-appropriate behavior before exposing the assistant broadly.
3. Define cost/rate budgets and add distributed rate limiting before large-scale production.
4. Test timeouts, provider outages, 429s, long Unicode input and session expiration on physical Android devices.
5. Decide whether AI history should remain ephemeral or get a separate opt-in encrypted store. Do not mix private messenger chats into AI context automatically.
6. No AI credential or provider URL should be committed to GitHub, an APK, Android preferences or chat messages.
7. Do not merge this stacked branch directly to `main` before prerequisite drafts and staging tests are reviewed.
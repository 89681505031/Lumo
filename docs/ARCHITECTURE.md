# Lumo architecture

## MVP
Android client communicates with an HTTP API for account/profile/history and a WebSocket endpoint for live messages.

## Production direction
- Android client: Kotlin + Jetpack Compose
- API/WebSocket backend
- PostgreSQL for users, conversations and message metadata
- Object storage for media
- Push notifications
- Rate limiting, moderation/reporting and abuse prevention
- End-to-end encryption based on an established, reviewed protocol rather than custom cryptography

The current in-memory backend is intentionally a development scaffold. Restarting it clears users and messages.

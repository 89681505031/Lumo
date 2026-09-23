# Lumo

Lumo is a modern messaging application.

## MVP
- Basic user registration (secure login is not implemented yet)
- User profiles
- One-to-one conversations
- Real-time messaging over WebSocket
- Message history
- Delivery/read status foundation
- Android client foundation

## Repository structure
- `server/` — backend API and WebSocket server
- `android/` — Android client

## Automated checks
The server workflow runs syntax checks, smoke tests without a database, and integration tests against an ephemeral PostgreSQL service in GitHub Actions. The CI database is separate from production; successful CI does not configure Vercel's `DATABASE_URL`.

## Run backend
Requires Node.js 20+.

```bash
cd server
npm install
npm run dev
```

Server defaults to http://localhost:3000.

## Deployment
The current backend deployment target is Vercel. Keep Vercel as the application hosting platform unless the project owner explicitly changes this decision. PostgreSQL is required for persistent users, sessions, and messages. The production registration endpoint intentionally returns HTTP 503 until the database is configured.

To finish the Vercel database setup:
1. Provision a PostgreSQL database using a provider of your choice. Do not commit its connection string or passwords to GitHub.
2. In the Vercel project **lumo**, add `DATABASE_URL` to the **Production** environment variables using the provider's connection string. Configure TLS according to the provider's requirements; `server/src/db.js` currently enables TLS by default, and `DATABASE_SSL=false` disables it for local development only.
3. Redeploy the production deployment so the new environment variable is loaded. The server attempts to create its tables and indexes on startup.
4. Check `/live` for process liveness (HTTP 200) and `/health` for database readiness (HTTP 200 with `database.ok: true`). If `/health` returns 503, inspect Vercel runtime logs without posting credentials.

**Realtime note:** Vercel WebSockets are available in beta with Fluid Compute, but each connection is pinned to a single function instance. Lumo's in-memory socket registry cannot immediately forward events to another instance. While a chat is open, Android therefore reconciles message history and receipts every five seconds through PostgreSQL-backed HTTP, and sends queued messages over HTTP if WebSocket is disconnected. This fallback is not background push; production-grade cross-instance realtime messaging still requires shared pub/sub (for example, Redis) and deployment testing. See https://vercel.com/docs/functions/websockets.

> The current registration returns a session token but has no secure login or account recovery. Do not use this build for real private conversations or public launch until authentication, realtime hosting compatibility, and security have been validated.

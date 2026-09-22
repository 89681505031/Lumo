# Lumo

Lumo is a modern messaging application.

## MVP
- User registration and login
- User profiles
- One-to-one conversations
- Real-time messaging over WebSocket
- Message history
- Delivery/read status foundation
- Android client foundation

## Repository structure
- `server/` — backend API and WebSocket server
- `android/` — Android client (next milestone)

## Run backend
Requires Node.js 20+.

```bash
cd server
npm install
npm run dev
```

Server defaults to http://localhost:3000.

## Deployment
The current backend deployment target is Vercel. Keep Vercel as the application hosting platform unless the project owner explicitly changes this decision. PostgreSQL remains required for persistent users, sessions, and messages; configure it through `DATABASE_URL` in the Vercel project environment.

> MVP authentication and in-memory storage are for development only. Production will use a persistent database, secure authentication, abuse protection, and audited end-to-end encryption.

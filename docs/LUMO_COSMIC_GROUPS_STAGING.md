# Lumo cosmic groups — backend-gated staging integration

The Android group chat UI in `feature/lumo-cosmic-groups-staging` ports existing draft PR #14 into the current cosmic messenger hierarchy without merging any unreviewed group backend, SQL schema or optional staging-server configuration into production.

**What is implemented in this app branch:** authenticated server capability probe (`GET /api/groups`), group entry only if that endpoint succeeds; open/create private groups; owner/admin invitations and role controls from PR #14; offline retry for one pending group message using the original UUID; same-instance WebSocket group events and HTTP polling across instances; cosmic group cards/message bubbles. Navigation keeps the approved three-tab main navigation, exposing a Groups tile inside Chats only when the server demonstrably supports groups.

**What is not live:** current Lumo production does not have group API. Without backend support, the Groups tile stays hidden, and regular 1:1 chats continue working. No public group search, no E2E encryption, no group media, no reactions yet.

**Pre-release requirements:**
1. Review existing draft foundational PR #2 (private chat privacy/blocking and unread state).
2. Rebase/review draft group backend PR #14, whose group SQL migrations, authenticated API and two-server PostgreSQL integration tests must pass independently. Do not deploy development-only branch onto production accidentally.
3. In a configured staging deployment, create two test accounts and verify group invites, per-member access, promotion/removal, post-rejoin visibility, duplicate retries, and independent Vercel instances.
4. Build a signed update with the correct production key only after security review; validate cosmic screen rendering on a real Android device and preserve existing local data.

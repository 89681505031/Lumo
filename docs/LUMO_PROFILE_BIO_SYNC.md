# Lumo Phase 9 — cross-device profile bio

This branch is stacked on AI assistant PR #36. It adds one real cross-device profile field without touching avatar/media storage or production deployment.

## What changes

- PostgreSQL `users` gets a backwards-compatible `bio varchar(160) not null default ''` migration.
- Public Lumo user JSON now includes `bio`. Search, conversation peers, `/api/me`, registration and fresh login all return it.
- `PATCH /api/me` accepts `displayName` plus optional `bio`. Omitting `bio` preserves the existing value, so older Android clients cannot accidentally erase it.
- Android `User` carries `bio`; People cards can show one short bio line.
- Profile edit saves display name + bio together. If the connected server returns the new `bio` field, Android reports that it synchronized. If an older server ignores the new field, Lumo keeps the description locally and explicitly says it is local-only.
- Encrypted offline chat summaries preserve the public bio so cached People/chat metadata remains consistent.

## Privacy / compatibility

- Bio is a **public profile field** visible to authenticated Lumo users returned by the user directory and direct-conversation peer metadata. The UI should not imply it is private.
- Maximum length is 160 characters. Server-side validation is authoritative.
- Existing accounts migrate to an empty bio; existing clients continue working because the field is additive.
- Local avatar remains device-only. This phase does not upload photos or claim a cross-device avatar.
- No automatic upload of an old local bio occurs on app start. A local fallback reaches the server only when the user explicitly edits/saves Profile.

## Tests / release gates

- PostgreSQL integration test covers new-profile empty bio, update, public search visibility, older display-name-only PATCH preserving bio, over-160 rejection and persistence across logout/login.
- Database health requires the new `bio` column after migration.
- Android debug/release build must pass on the stacked branch.
- Before production: verify Unicode/newlines, account switching and People/Profile rendering on a real phone. The existing production deployment issue for `/api/conversations` is separate.
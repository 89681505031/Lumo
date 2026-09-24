# Lumo Phase 18 — group message edit/delete and participant reactions

This stacked branch adds sender-owned group message mutations and participant reactions on top of membership-safe group replies/search.

## Sender-owned edit/delete

- Only the current sender of a visible group message can edit or delete it.
- Edit replaces the text and records `editedAt`; the client shows an `изменено` marker.
- Delete produces a tombstone (`Сообщение удалено`) instead of removing the row, clears all reactions on that message and prevents new replies/reactions/search matches.
- Existing replies keep their link but show the tombstone preview after refresh/live reconciliation.
- A late/rejoined user cannot mutate a message that predates the current membership period.

## Group reactions

- Reactions use the same allowlist as direct messages: 👍 ❤️ 😂 😮 👏 🚀.
- Reactions remain opt-in behind `LUMO_REACTIONS_ENABLED=true`.
- A user can react only to a non-deleted group message visible in their current membership period.
- Reaction list queries are membership-scoped and capped; deleting a message cascades/removes its reactions.
- Android polls reaction state as the durable cross-instance fallback and also updates immediately after the user's own reaction action.

## Android UX

- Tapping a group message opens an action dialog rather than immediately committing a reply.
- Actions can include Reply, Edit, Delete and Reaction depending on server capabilities, sender ownership and message state.
- Deleted tombstones disable reply/edit/delete/reaction actions.
- Live `group_message` mutation events reconcile history, active search results, reply previews and local reaction badges.

## Privacy/reliability

- Membership-window checks are enforced in SQL, not only in Android.
- Search excludes deleted messages.
- Reply creation rejects deleted targets.
- Group message mutation capabilities are advertised from `/api/capabilities`; older servers simply keep the older group UI behavior.
- Reactions remain off unless explicitly enabled server-side.

## Still required before production

1. Multi-device QA for concurrent edit/delete/reaction changes across separate server instances.
2. Decide whether group admins should ever receive moderation-only delete powers; this phase intentionally keeps delete sender-owned.
3. Add audit/moderation policy before broad group rollout.
4. Add pagination and group push fan-out before larger deployments.
5. Group attachments and E2E encryption remain separate future work.
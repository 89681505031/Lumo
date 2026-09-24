# Lumo Phase 20 — private group history pagination

This stacked branch removes the latest-100-history ceiling for private groups without weakening membership-window privacy.

## Server paging contract

- Existing `GET /api/groups/:id/messages` remains unchanged for older Android builds and still returns the newest 100 visible messages.
- New clients can use `GET /api/groups/:id/messages/page` with a bounded `limit` from 1 to 100.
- Older pages use a stable two-part cursor: `beforeAt` (message timestamp) plus `beforeId` (UUID). Both fields are required together.
- PostgreSQL sorts by `(created_at DESC, id DESC)`, so multiple messages with the same timestamp are paged without duplicate/skip ambiguity.
- Page replies keep the same membership-window reply-preview redaction as normal history.

## Membership privacy

- Every page is scoped to the requester's **current** `chat_group_members.joined_at` value.
- A late joiner cannot paginate backward into messages created before joining.
- Leaving/removal makes the paged endpoint unavailable immediately.
- Rejoining creates a new history boundary and does not reopen pages from the previous membership period.
- Invalid/partial cursors and oversized page limits are rejected server-side before database access.

## Android behavior

- Pagination UI is capability-gated by `groupPagination`; old servers continue using the existing latest-history behavior.
- A `Показать ранние сообщения` control loads 50 older messages at a time above the current group history.
- Five-second live/poll refresh merges the newest server history with already loaded older pages instead of discarding them.
- Search remains a separate server-scoped mode and does not mix its results with loaded history pages.
- Confirmed membership loss clears the active group's loaded content. A transient network outage keeps already loaded messages visible and reports that the app is showing cached in-memory history.

## Test coverage

- Integration coverage inserts 125 same-timestamp messages to exercise the UUID tie-breaker across three pages (50 + 50 + 25).
- Tests reject partial cursors and limits above 100.
- Tests verify outsiders cannot page a group and late/rejoined members cannot page older membership history.

## Still required before production

1. Physical-device scroll/position QA with several thousand messages.
2. Add automatic near-top pagination after UX/performance validation; this milestone uses an explicit load-older control.
3. Add similar pagination to direct-message history and optionally group search if product needs larger result sets.
4. Monitor query latency/index usage on staging PostgreSQL with realistic group sizes.
5. Do not merge the stacked tip directly to `main`; deploy and validate prerequisites in order.
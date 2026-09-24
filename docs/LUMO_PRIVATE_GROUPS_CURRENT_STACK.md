# Lumo Phase 16 — private groups connected to the current stack

This stacked branch connects the existing cosmic Android Groups UI to durable PostgreSQL group APIs in the current Lumo feature chain.

## What works

- Private groups can be created with titles from 2 to 80 characters.
- Group list is private: users see only groups where they are current members.
- Owner/admin/member roles are enforced server-side.
- Owner can promote/demote admins and delete the group; admins can remove ordinary members; ordinary members can leave.
- Maximum 50 members per group and 100 groups returned per user list.
- Direct group history is limited to the most recent 100 messages visible to the current membership period.
- Members who leave and later rejoin do **not** regain access to messages from their prior membership period.
- Group sends use stable client message UUIDs so retrying an uncertain response does not duplicate a message.
- Same-instance WebSocket `group_message` pushes are supported; Android polling remains the durable cross-instance fallback.

## Privacy and authorization

- There is no public group directory or unauthenticated group lookup.
- Group details, member list and history require current membership.
- Invitation checks existing `user_blocks` in either direction between the inviter and invitee.
- Removing a member immediately removes API/history access.
- Deleting a group cascades through membership and group messages.
- HTTPS/WSS transport protection is **not** end-to-end encryption; this milestone does not claim E2E group messaging.

## Current limitations

- Group messages are text-only in this phase.
- Linked replies, reactions, edit/delete/search and private attachments are currently implemented only for direct messages.
- WebSocket delivery is process-local; polling PostgreSQL is the fallback across separate server instances.
- No push notification fan-out for group messages yet.

## Release gates

1. Two- and three-device physical QA with owner/admin/member accounts on different networks.
2. Verify removal/rejoin privacy and unstable-network retry behavior.
3. Add pagination before substantially larger histories.
4. Define group moderation/reporting and abuse controls before broad rollout.
5. Reconcile group behavior with direct-message blocking policy and future group attachments/reactions.
6. Do not merge this stacked tip directly to `main`; review and deploy prerequisites intentionally.
# Lumo — 12-feature implementation tracker (September 2026)

This tracker describes what exists in the stacked development branches. **None of the staging-only server capabilities should be described as production-ready until their own security/device/deployment gates pass.** The latest integration branch is `feature/lumo-current-stack-push`, continuing the stacked feature chain through group pagination and privacy-first generic direct/group push staging.

## Current implementation status

| # | Area | Implemented now | Still required before production |
| --- | --- | --- | --- |
| 1 | Living cosmic background | Native Compose starfield/planets, theme-aware glows, optional slow twinkle, low-power mode. | Physical-device battery/frame-time/accessibility checks. |
| 2 | Animated identity | Planet/orbit vector logo, launcher icon, optional slow orbit/glow animation. | True 3D asset/rendering is still optional future work; current mark is 2D vector animation. |
| 3 | Voice messages | Android consent-based AAC recorder + private signed media upload/viewer for direct chats and groups; group recording stops on background and is server-gated. | Private object storage configuration, security review, two-/three-device tests and production rollout. |
| 4 | Audio/video calls | Authenticated call invitations plus accepted-call WebRTC **audio** staging with explicit per-user microphone action, Android permission, TURN-only ICE, bounded/idempotent signaling and cleanup. | Real TURN deployment, two-device/NAT/background/audio-route QA and abuse/cost controls. **Video media transport is not implemented.** |
| 5 | Photo/video/files | Private photo/video/voice plus PDF/TXT/DOCX/XLSX/PPTX staging attachments for direct chats **and private groups**, using system pickers and authenticated short-lived signed downloads. | Dedicated staging bucket/CORS, malware/quarantine pipeline, retention/object cleanup, quotas and multi-device QA before broad production rollout. |
| 6 | Message actions | Direct-message linked replies/edit-delete/search/text+attachment forwarding/reactions plus private-group linked replies/search, sender-owned edit-delete, participant reactions and private attachments. | Richer forwarded-message attribution and final multi-device authorization/offline QA. |
| 7 | Themes/customization | Cosmos, Aurora, Violet, Minimal; animation and battery controls saved locally. | Real-device visual/accessibility checks. |
| 8 | Notifications | Privacy-first FCM debug/staging client plus current-stack session-scoped registration and durable generic direct/group outbox; payloads contain no sender/message/group text; offline revoke reconciliation remains explicit. | Dedicated staging Firebase/ADC/cron credentials, physical-device permission/Doze/token/account-switch tests and operational backlog monitoring. Release remains no-op. |
| 9 | Profiles | Display-name edit, cross-device public bio, local-first Photo Picker avatar, explicit authenticated avatar sync/removal, visual/privacy settings. | Real-device avatar sync/delete QA plus moderation/reporting, retention, backup and storage-quota policy before broad rollout. |
| 10 | Groups | Cosmic private group UI plus PostgreSQL membership/roles, removal/rejoin privacy, idempotent sends, WebSocket + polling fallback, replies/search/actions, private photo/video/voice/document attachments, stable history pagination, and generic privacy-preserving group push staging. | Multi-device concurrency/scroll/push QA, moderation/abuse controls, object cleanup and E2E work. |
| 11 | Offline experience | AES-256-GCM Android Keystore recent chat/history cache, cached-chat fallback, encrypted pending-message queue migration, per-account clear control. | Airplane-mode/reconnect/account-isolation/corrupt-key/performance tests; group/media offline cache remains separate work. |
| 12 | Lumo AI | Separate cosmic assistant screen; server-only provider key; authenticated capability gate; strict prompt/history limits; no automatic private-chat sharing; selected message can be copied only as an **unsent editable AI draft**. | Pick provider/retention terms, moderation/age-safety review, cost/distributed rate limits and isolated staging before production. |

## Integration / release sequence

1. Keep visual and local-only changes reviewable independently from backend migrations.
2. Review privacy/chat foundations before deploying group/media/reaction server features.
3. Configure storage, TURN, Firebase and AI credentials only in private staging environments first.
4. Run Android + PostgreSQL CI and then physical-device tests across two accounts/devices/networks.
5. Do not merge the stacked development tip straight to `main`; reconcile each dependency intentionally and preserve existing production signing/data.
6. The separate production `/api/conversations` deployment mismatch remains unrelated to these feature branches and must be resolved independently.

## Non-negotiable safety/privacy gates

- No hidden microphone/camera recording; recording/calls require visible user actions and Android permissions.
- No automatic private-chat export to AI. A selected message handoff stays unsent until the user presses Send in the AI screen.
- No message text/sender identity in push notifications.
- No provider/API secrets in APK, GitHub, Android preferences or chat payloads.
- No claim of E2E encryption: the offline cache is device-at-rest encryption only.
- No fake controls for unsupported server features; capability-gated UI must remain hidden/disabled when the backend is unavailable.
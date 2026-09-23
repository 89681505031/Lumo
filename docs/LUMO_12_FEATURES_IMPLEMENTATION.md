# Lumo — 12-feature implementation tracker (September 2026)

This document tracks the user's requested expansion of Lumo. The production application and the draft cosmic design branch must not be confused with experimental stacked branches. The 12 areas are **not all shipped**. A feature is "working" only after its relevant server tests, Android build, privacy review, end-to-end device tests, and production configuration have been completed.

## 12 areas and real implementation status

| # | Area | Current evidence | What is needed before user-ready release |
| --- | --- | --- | --- |
| 1 | Living cosmic background | `feature/lumo-cosmic-personalization`: theme-aware native starfield, optional slow twinkle, optional low-power mode; layered illustrations are Compose, not GIFs. | Validate frame time, battery use and reduced-motion expectations on Android. |
| 2 | Animated 3D identity | Existing design branch has a unique vector planet/orbit mark and Android launcher icon. This feature branch adds a small opt-in animated orbit tilt and glow. | True 3D rendering is **not** implemented; design and benchmark a real 3D asset only if needed. |
| 3 | Voice messages | Draft PR #15 (private media storage) and PR #16 (Android photo picker / voice recording / media viewer). | Configure private S3-compatible storage, verify consent and recording lifecycle, test on devices. No production voice upload yet. |
| 4 | Audio/video calls | Draft PRs #17–#20: authenticated signaling, Android call invitation lab, **debug-only audio** WebRTC with explicit mic consent and TURN controls. | Complete cross-device audio testing, configuration, call reliability and privacy review. **Video media path does not exist yet.** |
| 5 | Private photo, video and document attachments | Draft PRs #15–#16 establish private media backend and Android image/video picker; general document-picker path and broad file types need separate design. | Private object-store configuration, upload bounds, authorization checks, cache protection and Android download UX. |
| 6 | Replies, reactions, forwards and pins | Draft PR #2 has conversation pinning; draft PR #11 has authenticated edit/delete and in-chat search. | Reply threading, reactions and forwarding backend + UI, authorization and regression coverage remain to be built. |
| 7 | Themes and customization | `feature/lumo-cosmic-personalization` adds Cosmos, Aurora, Violet, Minimal, plus animation and battery controls stored only on device. | Phone screenshots, accessibility contrast checks, composable UI tests. |
| 8 | Smart notifications | Draft PR #13 and PRs #21–#26 include opt-in, session-scoped, privacy-first FCM infrastructure and **debug-only staging** client. | Verify server cron and Firebase credentials, device permission/channel revocation, monitoring and safe rollout. No production pushes claimed. |
| 9 | Full profiles | Cosmic design branch includes name edit and local-only bio. This branch also provides appearance customization. | Optional avatar upload and cross-device bio require authenticated new server APIs + storage + consent; no false global presence indicator. |
| 10 | Groups | Draft PR #14 has private membership/roles and Android group flow, stacked on PR #2. | Security review, Android integration with selected cosmic UI, data-migration and two-user tests before merge. |
| 11 | Offline experience | Existing Android client already persists outgoing pending direct messages and retries/synchronizes via HTTP. | Authenticated encrypted local history / offline read cache, storage lifetime, search and conflict tests. Do not store plaintext history in preferences. |
| 12 | Lumo AI assistant | Not implemented or connected. | Opt-in, separate assistant thread, consent before sharing any existing chat, server-held API key, abuse/rate limits, costs and data-retention policy. |

## Integration order

1. **Visual foundations and accessibility:** draft PR #27, then this feature branch. Confirm physical Android screenshots before accepting design parity. Low-power setting defaults to disabled; animation defaults to off.
2. **Private direct-message extensions:** draft PR #2, then #11; reconcile with the cosmetic screens carefully. Do not merge a branch based on another draft straight into `main` without its reviewed prerequisites.
3. **Storage and voice:** #15 → #16; configure private storage in staging and validate its permission/expiry checks. Do not add a fake microphone or photo button before this is truly functional.
4. **Groups and notifications:** #14; then #13 → #21 → #22 → #23 → #24 → #25 → #26. Respect opt-in; no message text in push payloads.
5. **Calls:** #17 → #18 → #19 → #20. Real video is new work after audio privacy/stability are verified.
6. **Remaining product features:** replies/reactions/forwarding, document files, authenticated profile uploads, encrypted local cache, and explicit-consent AI assistant.

## Release gates

- CI Android debug APK compiles; server tests pass with PostgreSQL.
- Device tests cover authentication, chat list, message send, retries and background/foreground lifecycle.
- Feature UI never claims functionality that only exists as backend prototype.
- Phone screenshots are reviewed against approved cosmic visual references.
- Do not overwrite production data, deploy draft staging stacks automatically, or merge unfinished dependent PRs.
- Separately: the production Vercel alias was observed serving a commit older than the `main` SQL-conversation fix. The design work does not modify this deployment; check the live server commit before attributing chat failures to an Android change.

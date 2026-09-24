# Lumo — TURN-only WebRTC audio staging

This stacked branch connects accepted **audio** call invitations to a real WebRTC audio transport for staging. It remains disabled unless the server explicitly enables call signaling and provides private TURN credentials.

## User-consent model

- Receiving or accepting a call never opens the microphone by itself.
- After an audio call is accepted, **each participant must separately tap the audio control**.
- Android runtime `RECORD_AUDIO` permission is requested only after that action.
- Navigation, hangup, lifecycle stop, permission revocation or a newer startup attempt cancels in-flight TURN/WebRTC startup before a stale microphone track can remain active.
- Only one Lumo audio session may be active in the call screen at a time.
- Video invitations still carry signaling state only; camera/video transport is not implemented or implied.

## Network/privacy model

- The peer connection uses `IceTransportsType.RELAY` and accepts only `turn:` / `turns:` URLs returned by the authenticated Lumo server.
- No public STUN service is configured by the Android client, so the staging design does not intentionally expose peer IP candidates directly to the other user.
- TURN usernames/credentials are short-lived Coturn REST credentials derived server-side from `LUMO_TURN_SECRET`; the shared secret never goes into the APK or API response.
- SDP/ICE messages are stored separately from chat history, are participant-only, bounded, idempotent by `clientSignalId`, and capped at 150 signals per call.
- Offer can be submitted only by the caller and answer only by the callee.
- Calls expire automatically; accepted sessions are capped at 30 minutes in this staging design.

## Server controls

- `LUMO_CALL_SIGNALING_ENABLED=true` is required; otherwise `/api/calls` returns feature unavailable.
- `LUMO_TURN_URLS` must contain 1–3 private TURN/TURNS URLs.
- `LUMO_TURN_SECRET` must be at least 32 characters.
- `CRON_SECRET` protects `/internal/call-cleanup`, which deletes old SDP/ICE and expired call rows.
- Database health requires call tables only when call signaling is explicitly enabled.

## Reliability measures

- Cross-instance invitations serialize both participants using PostgreSQL advisory transaction locks, preventing reciprocal/overlapping active calls.
- Signaling sequence allocation is serialized per call and retries with the same client signal UUID are idempotent.
- Android queues outbound WebRTC signals in a bounded channel and retries transient sends without changing the client signal ID.
- ICE candidates generated before local SDP publication and candidates received before remote SDP are buffered in bounded process memory until the corresponding description is ready.
- Ending the local audio session disables the track and disposes PeerConnection, source, track and factory resources, then restores the previous Android audio mode/route.

## Still required before production

1. A real private TURN service in an isolated staging environment; CI uses only a fake TURN URL to test credential/signaling logic.
2. Two physical Android phones on Wi‑Fi ↔ mobile data, mobile ↔ mobile and restrictive NAT/firewall networks.
3. Long-call, reconnect, Bluetooth/headset, speaker/earpiece, interruption, app-background and permission-revocation tests.
4. TURN abuse/cost monitoring, quotas and rate limiting at the TURN layer.
5. Accessibility/UX pass for incoming-call notifications and audio route/mute controls.
6. Production privacy/retention review for short-lived signaling metadata.
7. Video remains a separate future milestone; this branch must not be represented as video calling.
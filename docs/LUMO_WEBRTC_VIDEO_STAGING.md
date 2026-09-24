# Lumo Phase 25 — explicit TURN-only WebRTC video staging

This stacked branch adds one-to-one **video + audio** transport to accepted video-call invitations. It follows the same privacy model as audio staging: accepting a call never starts camera or microphone capture.

## Consent and lifecycle

- Each participant must first accept the call, then separately tap `Включить видео и аудио`.
- Android requests `CAMERA` and `RECORD_AUDIO` only after that explicit action.
- If either permission is missing or later revoked, the local video session is stopped.
- Pressing Home/backgrounding Lumo stops the WebRTC session and releases camera, microphone, renderer and peer-connection resources.
- Only one active Lumo media session is allowed in the call screen at a time.
- Users can independently mute/unmute microphone, disable/re-enable camera, switch camera, change speaker routing, or disconnect the media transport.

## Network privacy

- WebRTC uses `IceTransportsType.RELAY`.
- Android accepts only authenticated server-provided `turn:` / `turns:` entries; no public STUN service is configured.
- Existing short-lived TURN REST credentials and bounded/idempotent SDP/ICE signaling are reused.
- Video calls do not create a second signaling channel or public media URL.

## Video implementation

- Front camera is preferred, with back camera fallback.
- Capture is bounded to 640×480 at 24 fps in this staging path.
- Local and remote video use WebRTC `SurfaceViewRenderer` with EGL-backed encoder/decoder factories.
- Audio travels in the same peer connection as video.
- Camera capture can be stopped without ending the accepted call state.

## Safety / privacy guardrails

- No hidden/background camera or microphone start.
- No automatic media start when an invitation arrives or is accepted.
- No camera permission request during app startup.
- No screenshot, recording, media persistence or upload is added by this phase.
- This is transport encryption/TURN routing, **not** a claim of application-level end-to-end encryption.

## Required before production

1. Two physical Android phones across Wi‑Fi↔mobile, mobile↔mobile and restrictive NAT/firewall networks.
2. Test front/back camera switching, rotation, low-memory devices, permission revocation and app background/foreground transitions.
3. Test Bluetooth/headset/speaker routing and long calls for thermal/battery behavior.
4. Real private TURN capacity/abuse/cost monitoring and quotas.
5. Accessibility and incoming-call UX review.
6. Security/privacy review of camera/microphone indicators and signaling retention.
7. Do not merge the stacked tip directly to `main`; stage prerequisites intentionally.
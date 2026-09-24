# Lumo Phase 26 — restart-safe WebRTC media sessions

This stacked staging phase fixes a reliability bug in accepted calls: after an explicit audio/video disconnect, a later media start could replay stale SDP/ICE or hit the server's former one-offer-per-call rule.

## What changed

- Every offer, answer and ICE candidate now carries a validated UUID `mediaSessionId`.
- A new caller-side media start gets a fresh session id; old signaling remains immutable but cannot be consumed by the new peer connection.
- Android bootstraps signal history before live polling and applies only the current caller session or the latest offered session.
- A callee peer can move to a newer offered session without mixing ICE candidates from the previous negotiation.
- The server allows later offers for later session ids while still allowing only one caller offer per session.
- Re-answers are bounded per session so activity recreation/background recovery cannot create an unbounded signal stream.
- Answers and ICE are rejected unless the referenced session has a caller offer.
- Existing per-call signal limits, authentication, participant checks, block checks, short-lived TURN credentials and relay-only ICE remain in force.

## Privacy and lifecycle

This does not auto-start camera or microphone. Media still requires an accepted call, an explicit local enable action and Android runtime permission. Backgrounding the app still stops capture and native WebRTC resources.

## Verification required before production

1. Reconnect both directions after one participant backgrounds and returns.
2. Reconnect after local media disconnect/re-enable.
3. Repeat rotation/recreation several times and verify no stale remote video/audio is attached.
4. Test delayed/out-of-order network delivery and TURN credential refresh on two physical devices.
5. Keep this branch staging-only until real-device call tests pass.

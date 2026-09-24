# Lumo Phase 28 — audio output routing

This stacked Android phase adds user-controlled communication-device routing to the existing explicit TURN-only call lab.

## Android 12+ (API 31+)

- Uses `AudioManager.availableCommunicationDevices`.
- Uses `AudioManager.setCommunicationDevice` for phone earpiece, speaker, wired/USB headset, Bluetooth SCO/BLE headset and hearing-aid routes exposed by Android.
- Requests `BLUETOOTH_CONNECT` only when the user explicitly chooses a Bluetooth route.
- Calls `clearCommunicationDevice()` when the local WebRTC media session stops.
- Requests transient voice-communication audio focus while the media session is active.

## Android 8–11

- Keeps a conservative phone/speaker picker using the legacy speakerphone switch.
- Does not attempt old Bluetooth SCO forcing because behavior varies by device and modern Android guidance prefers communication-device routing.

## Privacy

Accepting a call still does not start microphone or camera capture. Routing is available only after the user explicitly starts a local media session.

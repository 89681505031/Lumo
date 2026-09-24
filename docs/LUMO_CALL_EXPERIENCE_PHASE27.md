# Lumo Phase 27 — clearer live-call experience

This stacked Android phase makes the existing TURN-only staging calls easier to understand without changing consent or media-start rules.

## Added

- The call controls receive the actual WebRTC ICE connected/disconnected state instead of inferring success from UI actions.
- Audio and video surfaces show `Подключение…`, `Соединено`, or `Связь прервана`.
- A monotonic call timer starts on the first real ICE connection and continues across temporary relay interruptions in the same local media session.
- The current basic audio route is displayed as `Телефон` or `Динамик`, and the toggle action is worded as a destination.
- The calls screen copy now explains that the visible status is the real WebRTC media state.

## Unchanged privacy behavior

- Accepting a call still does not start microphone or camera capture.
- Runtime microphone/camera permission is still requested only after the explicit media action.
- WebRTC remains relay-only with server-provided authenticated TURN.
- Backgrounding/stopping the screen still tears down capture and native peer resources.

## Production follow-up

Bluetooth/headset route selection is intentionally not forced in this phase. It should be added only with device-specific testing and the correct modern Android Bluetooth permission flow.

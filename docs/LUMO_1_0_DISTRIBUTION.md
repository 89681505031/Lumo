# Lumo 1.0 distribution candidate

This branch consolidates the current Android/server feature stack for public APK distribution.

- Android default version: 1.0.0 (versionCode 1000).
- Production API remains https://lumo-gamma-seven.vercel.app.
- The blocking full-screen conversations error is replaced by an inline retry state.
- User-facing call controls no longer use staging/test wording.
- Explicit microphone/camera consent, TURN-only WebRTC, offline encrypted cache, and background media teardown remain unchanged.

Production deployment must come from this exact stack (or its merge to main) before distributing the APK, so Android and backend capabilities stay aligned.

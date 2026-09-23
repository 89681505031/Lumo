# Lumo profile photo — local-first implementation

This is an **independent draft cosmetic feature** stacked on the cosmic theme PR #28.

- The user can tap the pencil over their large neon avatar in Profile to launch Android Photo Picker.
- The app requests no broad photo-library, storage or camera permission.
- Only JPEG, PNG and WebP are accepted. Input bytes are bounded to 8 MiB; dimensions are validated before decoding; decoded images are downsampled to a maximum 512 px edge before the app creates a compressed JPEG.
- The image is stored in Android's private app files directory under a SHA-256-derived current-user key and never uploaded. The profile makes the local-only limitation visible. The public user list and other devices will continue showing the generated initial.
- The temp file is removed after a successful or failed save. No third-party storage or remote URL is involved.

Before treating profile photos as cross-device or public, implement an authenticated server-side avatar protocol with moderation/content checks, ownership rules, safe profile image delivery, optional removal, caching limits and explicit user consent.

Verify physically: cancel picker, permission changes, oversized/corrupt images, orientation metadata, sign-out/switch accounts, app reinstall and device low-memory behavior. Android CI compilation alone does not complete these checks.

# Lumo Android release signing

The permanent Lumo release certificate is intentionally public and pinned in CI.

- Package: `app.lumo`
- Certificate SHA-256: `4d0c7d55658a469902a4935c41ba2e2a3f7a7fab6506319e67b7aa1c90bbc78b`
- Key alias: `lumo-release`

The private keystore and passwords must **never** be committed to this repository.

GitHub Actions expects these repository secrets:

- `LUMO_KEYSTORE_B64`
- `LUMO_KEYSTORE_PASSWORD`
- `LUMO_KEY_ALIAS`
- `LUMO_KEY_PASSWORD`

The Android workflow verifies that any configured keystore produces the pinned
certificate digest before a signed APK can be uploaded or published. A mismatch
means the APK would not be able to update existing Lumo release installations
and must not be published.

The private backup is stored outside the public repository.

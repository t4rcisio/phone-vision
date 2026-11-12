# Building a release

## Signing

Generate and securely retain a private Android signing keystore outside the repository. Keep an independent protected backup: the same signing identity is required for compatible future updates.

Supply these environment variables only to the build process:

| Variable | Meaning |
| --- | --- |
| `VISION_STORE_FILE` | Path to the private keystore |
| `VISION_STORE_PASSWORD` | Keystore password |
| `VISION_KEY_ALIAS` | Signing-key alias |
| `VISION_KEY_PASSWORD` | Signing-key password |

```sh
./gradlew -PvisionAbi=arm64-v8a assembleRelease
```

Without signing variables, Gradle can create an unsigned release for development verification. A published APK must be signed and non-debuggable. Never commit a keystore, password file, environment file, or local SDK configuration.

## Verification

1. Run unit tests, lint, compilation, and applicable device checks.
2. Run the publication hygiene script and a redacted secret scan over the staged tree and Git history.
3. Verify the APK with Android SDK `apksigner verify --verbose --print-certs`.
4. Inspect the APK manifest and confirm that `debuggable` is false, backups are disabled, and cleartext traffic is disabled.
5. Inspect the release archive for accidental SDK paths, logs, captures, personal test data, and credentials.
6. Publish a tag and GitHub Release with the signed APK, SHA-256 checksums, and release notes.

The public release uses a production signing identity. Development builds signed by Android's debug keystore are a different signing lineage and cannot be directly updated with the production APK. Do not uninstall a development build containing valuable private history without first considering that data.

## Demo assets

The supplied screenshots and recording are stored under `docs/assets`. Public derivatives omit device status bars and capture metadata; the recording is muted. Older footage can show the earlier Portuguese interface, while the current app and documentation are English.

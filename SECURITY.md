# Security and privacy

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/t4rcisio/phone-vision/security/advisories/new). Do not include API keys, signing material, private photographs, or unredacted logs in public issues.

The latest published release is the supported security baseline. Reports should identify the app version, Android version, affected feature, and a minimal reproduction with synthetic or public test data.

## Data boundaries

| Data | Handling |
| --- | --- |
| Moondream API key | Entered at runtime; AES-GCM encryption with a non-exportable Android Keystore key; ciphertext and IV in private preferences |
| Remote queries | Only explicit user actions send an image and prompt to Moondream over HTTPS |
| YOLO frames | Processed on the device; no frame upload or API key required |
| Query history | Original image, annotated image, answer, coordinates, and timestamp in private app storage |
| Gallery exports | Published through Android MediaStore; visible to the user and other apps with appropriate access |
| Diagnostics | Detailed app exception logging is restricted to debug builds; credentials are never logged |
| Application backups | Disabled; the key cannot be recovered by copying encrypted preferences to another device |

Removing the app or clearing its data removes private history and credentials. Gallery copies are separate files and are not automatically removed with private app data. Moondream receives the actual image and prompt of each remote query; remote queries are not an offline feature.

## Publication safeguards

- `.gitignore` excludes SDK paths, IDE state, local environments, captures, build outputs, credentials, and signing files.
- Production signing uses environment variables. No release private key or password belongs in this repository.
- CI runs a publication hygiene check and Gitleaks with redacted output.
- Workflow actions are pinned to full commit SHAs and use read-only repository permissions.
- Instrumented tests use a public fixture; personal captured scenes are not bundled with tests.
- Showcase media is explicitly supplied for publication, with capture metadata removed and video audio omitted.

Automated scanning is a defense, not a guarantee that arbitrary new content is safe. Review the staged diff and media before publishing. If a real credential is exposed, revoke or rotate it; deleting a line does not revoke a credential.

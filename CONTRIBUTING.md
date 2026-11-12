# Contributing

Thank you for improving Phone Vision. Keep code, comments, issues, and documentation in English.

## Development

1. Install Android Studio, Android SDK 37 (`platforms;android-37.0`), and JDK 25.
2. Clone the repository and configure the SDK with `ANDROID_HOME` or an untracked `local.properties` file.
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
4. For a smaller device build, add `-PvisionAbi=arm64-v8a`.

Use Kotlin's four-space indentation and ktfmt 0.65 with `--kotlinlang-style`. Keep comments focused on coordinate spaces, invariants, ownership, lifecycle transitions, and non-obvious decisions rather than restating each line.

## Tests

Unit tests exercise geometry and identity association without a device. Instrumented tests exercise Android graphics, OpenCV, the packaged model, persistence, and Compose. Camera and AR checks need suitable hardware; real network checks are opt-in and use credentials entered into the app.

Install test APKs manually with `adb install -r -t` and select the intended tests with `adb shell am instrument -w -e class ... com.example.vision.test/androidx.test.runner.AndroidJUnitRunner`. Use a dedicated emulator or test device for unattended instrumentation: some connected-test workflows uninstall apps and remove their private data.

Do not add personal images as regression fixtures. Use public, redistributable fixtures or deterministic synthetic scenes. Do not place API keys, tokens, local paths, private signing keys, or device identifiers in source, comments, logs, screenshots, or issue reports.

## Pull requests

Describe the concrete behavior being changed, how it was verified, and any limitations. Preserve the independence of 2D, 3D, and local tracking camera owners. New asynchronous results must be associated with their original capture and discarded after the relevant generation or session expires.

Run `python scripts/check_publication.py` before opening a pull request. Contributions are provided under the project's AGPL-3.0 license; third-party material must retain its attribution and compatible license.

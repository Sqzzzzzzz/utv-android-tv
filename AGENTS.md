# Ю — Android TV

Private personal client for u-tv.ru. Support only «Мама в 16» and «Маменькин сынок». Java 17, AGP 9.0.1, Gradle 9.2.1, compile SDK 36; min SDK 26, target 35.

## Changes and validation

- Make the smallest complete fix, reuse current code and dependencies, and address the cause. Preserve existing functions, validation and error handling.
- Before editing, read callers and applicable tests. Add a regression test for a material bug when useful.
- Run `./gradlew --no-daemon -Putv.ci=true assembleDebug testDebugUnitTest lintDebug`. The CI flag uses an ephemeral debug key. This APK is for checks and cannot update the TV installation.
- Production-compatible personal APKs require the owner's original signing key, stored separately. Never commit keys, tokens, local.properties, generated APKs or build output. Do not change app ID, signature, history format or version without a task requiring it.
- Use a separate agent for independent review when available; incorporate verified findings, then rerun affected checks. Review-only agents should report findings without spawning further agents.
- State actual validation and limitations. Robolectric tests do not verify real HLS playback, network availability, TV hardware decoding, or remote timing on the TCL.

## Code Review Rules

### TV interaction and playback

Flag broken DPAD focus, accidental OK actions across lifecycle transitions, or loss of per-show resume/history. Seek selection must not seek the main player before OK; Back cancels it and restores the earlier play/pause state. Seek frames must come from real video and release the extra decoder when no longer needed.

### HTTPS and remote data

Keep certificate and hostname validation enabled. The bundled GlobalSign R46 root applies only to the declared u-tv.ru domains; do not expand it to unrelated CDNs. Remote URLs, redirects, download sizes and decoded image dimensions must remain bounded and validated.

### Update compatibility

Preserve `dev.mark.utvremote`, existing per-show preferences and update signature checks. CI uses a disposable signing key only with explicit `-Putv.ci=true`; it must never be presented as an installable update over the owner's APK.

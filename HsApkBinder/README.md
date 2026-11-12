# Hs Apk Binder

Kotlin + Jetpack Compose APK binder with a Telegram payload engine.

## Build

### Option A — GitHub Actions (no PC needed)
1. Create a repo and upload all files.
2. Actions -> Build APK -> Run workflow.
3. Download the APK from the run's Artifacts section.

The workflow generates `payload.keystore` automatically.

### Option B — Android Studio
1. Generate the signing keystore once:
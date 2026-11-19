# Cape 100 Coach – notes for Claude

Personal Android app (Kotlin + Jetpack Compose) coaching Abu to the CAPE 100, a 100 km road ultra on 5 Sep 2027.
See README.md for what it does.

## Building
- Tests and APK: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, compileSdk 35).
- Every push runs `.github/workflows/build.yml`. A push to `main` also replaces the `latest` GitHub release's
  `Cape100Coach.apk`, which Abu downloads on his phone to update. Branches only get a zipped artifact.
- APKs are signed with `keystore/debug.keystore`. Keep it, or new builds won't install over the app on the phone.

## Rules
- Never commit personal data: Garmin exports, `import/*.json`, `backup/`, or anything with health, weight or account details.
- No API keys or passwords in code. The Claude key and Garmin login are entered in the app and stored encrypted on the phone.
- Plan logic lives in `plan/` and has unit tests in `app/src/test`. Add or update a test when the plan changes.
- The app no longer sends workouts to Garmin (Abu runs with the watch's normal Run activity). Don't bring that back.
- Tone in the app's coach text: warm and friendly, can call him "babe".

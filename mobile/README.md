# Daybook mobile

The native Kotlin/XML Views app uses Android's built-in UI components and no third-party app libraries. It stores account-separated expenses and pending operations in app-private SQLite, encrypts the session token with Android Keystore, and schedules network sync and daily reminders with Android system services.

Open `mobile` in Android Studio, let Gradle sync, then run `app` on an Android device or emulator. For a build with account access, set `DAYBOOK_API_BASE_URL=https://your-api-origin` in the environment or in an untracked `~/.gradle/gradle.properties`, then run `./gradlew assembleDebug`. The value must be an HTTPS origin. A build without it shows a setup message on the account screen; no URL or credential is committed to Git. The server needs SMTP configuration before public account creation works. Run `./gradlew testDebugUnitTest` for local Robolectric checks of account UI, durable storage, account isolation, migration, and sync retries; these checks do not cover Keystore or real-device scheduling.

The app ID is `com.daybook.mobile`. It targets Android 16 (API 36) and supports Android 8.0 (API 26) and newer. Reminders use inexact local alarms and may arrive around the chosen time. Android 13+ asks for notification permission when reminders are enabled.

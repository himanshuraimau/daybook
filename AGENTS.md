# Daybook agent guide

## Project

Daybook is a small personal finance Android app. Its first job is to make daily expense entry easy, then show a clear spending summary. Read [plan.md](plan.md) for product scope, [design.md](design.md) for the proposed UI, and [sessions.md](sessions.md) for the ordered work and completion checks.

The chosen stack is native Kotlin Android in `mobile/`, a TypeScript API running on Bun in `server/`, and Mongoose with the user's existing MongoDB server. Currency is INR only; amounts are integer paise. The app needs email/password sign-up and login, offline expense creation with later server sync, and an optional reminder that runs every enabled day. The existing Android starter uses XML Views. Keep that approach for the first release unless a session explicitly changes it. Do not bring back the old Expo/React Native code; this repository was converted from an Expo starter.

## Working rules

1. Read the relevant part of `plan.md` and the current session before changing code. Work on the smallest complete behavior that satisfies the session's acceptance check.
2. Follow project-local skills in `.agents/skills/` when relevant, especially Android architecture, Kotlin code design, Bun, and MongoDB schema design. Skills are guidance; the current product plan and user instructions set the scope.
3. Keep the Android project as one app module initially. Separate UI, data access, and models in packages without adding frameworks or modules just for structure. Prefer clear Kotlin APIs, lifecycle-aware state, and simple XML screens.
4. Keep the server inside `server/`. Do not initialize or populate it during planning sessions. When backend work starts, use Bun, TypeScript, Mongoose, environment-based configuration, and a committed lockfile.
5. Keep money calculations in integer paise. Treat an expense date as a local calendar date; do not let UTC conversion move it to another day. Validate data on both client and server.
6. Save an offline expense durably before showing success. Show whether it is pending, synced, or failed. Retry uploads safely using a client-generated ID so reconnects cannot duplicate expenses. Keep one account's local data and pending operations separate from another's.
7. Protect account data: hash passwords, scope queries to the authenticated account, use HTTPS for the mobile API, and keep credentials, database URLs, session tokens, and financial details out of source control and logs. Add appropriate ignore rules before generating build outputs or local secrets.
8. Keep reminders local to Android. Ask for notification permission in context. Enabled reminders run daily around the chosen local time; do not claim exact-minute delivery. Notification text must not reveal an amount.
9. Keep the UI minimal: quick entry, readable totals, useful empty/error states, accessible labels and touch targets. Avoid extra finance features until the first release is working.

## Progress and verification

- `plan.md` is the product contract. `sessions.md` is the execution checklist. After finishing a task, run the relevant build or behavior check, record the result in that session, and then tick its box. Tick a session's completion box only when its acceptance check passes. Keep corresponding plan checkboxes aligned.
- For documentation-only edits, check links, section mapping, and consistency with the confirmed choices. Do not claim an Android build passed until an Android SDK is available and the build actually runs.
- If a required external resource is missing, record the concrete blocker and keep other independent session work moving. Do not mark blocked work complete.
- Do not overwrite the user's MongoDB server or its data. Use a development database for implementation and tests; define backup/restore before release.
- Before changing a product choice that is still open in `plan.md`, use the stated default if there is one. Record any consequential new decision in the plan.

## Useful paths and commands

- Android starter: `mobile/app/src/main/java/com/daybook/mobile/MainActivity.kt` and `mobile/app/src/main/res/`.
- Android build, once the SDK is installed: from `mobile/`, run `./gradlew assembleDebug`.
- Backend commands will be added when `server/` is initialized in its session.

# Daybook agent guide

## Project

Daybook is a small personal finance Android app: quick daily expense entry, a pay-cycle budget with fixed spends and EMIs, and a clear picture of what is left to spend. Read [README.md](README.md) for what the app does and the rules it follows, and [docs/design.md](docs/design.md) for the UI. Component details are in [mobile/README.md](mobile/README.md) and [server/README.md](server/README.md).

The stack is native Kotlin Android in `mobile/` (XML Views, one app module), a TypeScript API on Bun in `server/` (deployed on Vercel), and Mongoose with MongoDB. Currency is INR only; amounts are integer paise. Do not bring back the old Expo/React Native code.

## Working rules

1. Read the relevant part of the README and `docs/design.md` before changing behaviour. Work on the smallest complete change that satisfies the request.
2. Follow project-local skills in `.agents/skills/` when relevant, especially Android architecture, Kotlin code design, Bun, and MongoDB schema design. Skills are guidance; the README and the owner's instructions set the scope.
3. Keep the Android project as one app module. Separate UI (`ui/`), data access (`data/`), and pure logic (`domain/`) in packages without adding frameworks or modules just for structure. Prefer clear Kotlin APIs, lifecycle-aware state, and XML screens.
4. Keep the server inside `server/`: Bun, TypeScript, Mongoose, environment-based configuration, and a committed lockfile.
5. Keep money calculations in integer paise. Treat an expense date as a local calendar date; do not let UTC conversion move it to another day. Validate data on both client and server.
6. Save an offline expense durably before showing success. Show whether it is pending, synced, or failed. Retry uploads safely using a client-generated ID so reconnects cannot duplicate expenses. Keep one account's local data and pending operations separate from another's.
7. Protect account data: hash passwords, scope queries to the authenticated account, use HTTPS for the mobile API, and keep credentials, database URLs, session tokens, and financial details out of source control and logs. Keep `.env` files and build outputs ignored.
8. Keep reminders local to Android. Ask for notification permission in context. Enabled reminders run daily around the chosen local time; do not claim exact-minute delivery. Notification text must not reveal an amount.
9. Keep the UI simple: quick entry, readable totals, useful empty/error states, accessible labels and touch targets. Add finance features only when the owner asks for them.

## Verification

- Before calling work done, run the relevant checks: from `mobile/`, `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease`; from `server/`, `bun run check` and `bun test` (with `MONGODB_URI_TEST` pointing at an isolated database). Do not claim a check passed without running it, and say plainly what was not verified (for example a physical phone).
- Unit tests must never reach a real server: they set `ApiClient.baseUrl = ""`.
- Record a consequential product decision in the README section that describes it, in the same change.
- If a required external resource is missing, name the concrete blocker and keep independent work moving.
- Do not overwrite the owner's MongoDB data. Use a development database for tests; back up before changing production data.
- Do not push to the remote or deploy unless the owner asks.

## Useful paths and commands

- App entry: `mobile/app/src/main/java/com/daybook/mobile/MainActivity.kt`; screens in `ui/`, storage and sync in `data/`, money and cycle logic in `domain/`, resources in `mobile/app/src/main/res/`.
- Android build: from `mobile/`, `./gradlew assembleDebug -PDAYBOOK_API_BASE_URL=https://<api-origin>`.
- Server: from `server/`, `bun run dev`; Vercel entry is `server/api/index.ts`.

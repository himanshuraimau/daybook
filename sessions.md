# Daybook build sessions

This checklist turns the components in [plan.md](plan.md#6-delivery-plan) into working sessions. Each session maps to one plan component. Work may span more than one sitting. Mark a task complete only after its result is checked; mark the session complete only when its acceptance check passes. Record the check or blocker under **Evidence / notes**. Then update the matching checkbox in `plan.md`.

## Current state

- The Android SDK is installed; debug/release APK builds pass, and 21 local Android JVM tests pass. An API 36 emulator now boots (KVM enabled) and the app launches on it; other device behavior (offline restart, reminders, large text) is not yet checked because the app needs an HTTPS API address to log in.
- `server/` contains the Bun/TypeScript API and `bun.lock`; tests use an isolated local MongoDB container.
- Product decisions already confirmed: INR only, email/password sign-up and login, offline expense creation followed by automatic server sync, and a reminder every enabled day.
- Project-local AI skills are in `.agents/skills/`.

| Session | Plan component | Status |
| --- | --- | --- |
| 1 | P1 — Product decisions and screen sketches | Complete |
| 2 | P2 — Development setup and API foundation | Complete |
| 3 | P3 — Authentication and account safety | In progress |
| 4 | P4 — Expense API | Complete |
| 5 | P5 — Android expense flow and offline sync | In progress |
| 6 | P6 — Dashboard and history | In progress |
| 7 | P7 — Reminder and settings | In progress |
| 8 | P8 — Release check | In progress |
| 9 | P9 — Budget API and cycle logic | Not started |
| 10 | P10 — Streak redesign | Not started |
| 11 | P11 — Budget, fixed spends, Insights | Not started |

## Session 1 — Product decisions and screen sketches (P1)

**Goal:** make the account and daily expense flows precise enough to build, while keeping the app small.

- [x] Record the confirmed currency, account, offline-save, and daily-reminder choices in `plan.md`.
- [x] Decide the starting categories and exact dashboard figures.
- [x] Decide authentication details: simple email/password sign-up and login, with no verification or reset (revised 2026-11-23).
- [x] Decide whether edits/deletes of synced expenses must work offline in v1.
- [x] Sketch Sign-up/Login, Home, Add/Edit, History, and Settings, including empty, pending-sync, and error states in `design.md`.
- [x] Finalize the short labels, opening prompt, and notification text.
- [x] **Session complete:** the flows and remaining choices are recorded in `plan.md` and can be implemented without guessing about core behavior.

**Evidence / notes (2026-11-23):** Checked the P1/Session 1 mapping, local links, screen sketches and state wording. The seven categories, dashboard figures, simple email/password approach (verification and reset dropped later the same day), online-only edits of synced entries, and exact short labels are recorded in `plan.md` §8. No Android build is claimed for this documentation session.

## Session 2 — Development setup and API foundation (P2)

**Goal:** make both projects runnable and establish safe data boundaries.

- [x] Install/configure an Android SDK and run the Kotlin starter on a device or emulator.
- [x] Initialize `server/` with Bun and TypeScript; add a health route and environment-based configuration.
- [x] Connect Mongoose to a development MongoDB database without committing a connection string.
- [x] Define account and expense models, validation, and indexes for owner/date and retry-safe create.
- [x] Write down the backup/restore approach for the existing MongoDB server.
- [x] **Session complete:** Android launches; the API starts; health and development-database checks pass.

**Evidence / notes (2026-11-23):** Installed Android platform 36 and build tools 36.0.0. The starter and implemented app both passed `./gradlew assembleDebug --no-daemon`; `lintDebug` and `assembleRelease` also passed. `adb devices` showed no device and `/dev/kvm` is absent. An installed API 36 emulator exits 139 before boot (GDB shows a crash in its bundled `libtcmalloc_minimal`/`libandroid-webrtc` path), so launch remains unverified. `bun run check` passed. Isolated Docker MongoDB on localhost port 27028: API started, `GET /health` returned `{"status":"ok"}`, and Mongoose created the four collections. `server/README.md` documents dump and test restore. Session acceptance remains open until Android launches.

**Launch check (2026-11-23):** After the owner loaded `kvm_intel` (`/dev/kvm` present, `emulator -accel-check` reports KVM usable), the emulator still segfaulted: gdb showed `libandroid-webrtc.so`'s exported `pthread_create` wrapper recursing forever on glibc 2.43 (~174k frames, crashing in tcmalloc). A 10-line `LD_PRELOAD` shim outside the repo (`~/.local/lib/daybook-emu/pthread_shim.so`, forwarding to glibc's `pthread_create@GLIBC_2.34`) fixes it; start with `LD_PRELOAD=$HOME/.local/lib/daybook-emu/pthread_shim.so ~/Android/Sdk/emulator/emulator -avd daybook_test -gpu swiftshader_indirect`. The `daybook_test` AVD (API 36, google_apis x86_64) booted (`sys.boot_completed=1`); `adb install` of the debug APK succeeded; `am start -W` returned `Status: ok` (1.7 s); the process stayed alive and logcat had no `AndroidRuntime` errors; the screenshot shows the Log in / Create account screen with the expected "needs an HTTPS API address" notice. With the earlier API and database checks, the acceptance check is met.

## Session 3 — Authentication and account safety (P3)

**Goal:** support normal account creation and login without exposing another user's data.

- [x] Build sign-up, login, current-session, and logout API routes.
- [x] Hash passwords, handle session expiry, and limit repeated login attempts.
- [ ] Build Android sign-up/login screens and persist the session securely.
- [ ] Separate local records and pending operations by account; handle logout with pending expenses safely.
- [x] Keep sign-up simple: no email verification, password reset, or SMTP (revised 2026-11-23).
- [ ] **Session complete:** a new account can log in; cross-account access fails; an expired session cannot upload under another account.

**Evidence / notes (2026-11-23):** `bun run check` and isolated MongoDB tests passed for Argon2id hashes, account-scoped access, 30-day session expiry rejection, logout, and repeated-login limit. (An earlier email-verification and reset-code flow was tested, then removed at the owner's request.) Android account screens and encrypted Keystore session storage compile. In the next work session, Robolectric checked account form transitions and validation; SQLite tests confirmed account-isolated records and pending operations even when two accounts use the same client ID, and confirmed version 1 data migrates without losing a pending entry. Keystore persistence and logout behavior still need a device check. Session acceptance remains open.

**Revision (2026-11-23):** Removed email verification, password reset, SMTP, and the `nodemailer` dependency. `POST /api/auth/signup` now creates the account and returns a session like login (201; duplicate email 409; password 8-128 characters). Server tests (4, 100 assertions) pass against an isolated MongoDB container, covering immediate login after sign-up, duplicate rejection, short passwords, Argon2id hashing, removed reset route, and the login rate limit. The Android auth screen has only Log in / Create account; JVM tests cover the mode switch and validation.

## Session 4 — Expense API (P4)

**Goal:** store and summarize INR expenses correctly for the authenticated account.

- [x] Implement create, paginated list, edit, delete, and monthly summary routes.
- [x] Validate positive integer paise, local dates, categories, and notes.
- [x] Make create retries idempotent using account + client-generated ID.
- [x] Check day/month boundaries, owner isolation, and totals after edit/delete.
- [x] **Session complete:** API expense changes produce the right history and totals without duplicates or cross-account access.

**Evidence / notes (2026-11-23):** `bun run check` and `MONGODB_URI_TEST=mongodb://127.0.0.1:27028/daybook_api_test bun test` passed: 4 tests, 93 assertions, 0 failures. Tests cover invalid local dates, integer paise, month rollover, totals after edit/delete, wrong-owner access, duplicate create replay and payload conflict, stale versions, expired-session upload rejection, and two pages of 52 same-date expenses. The test database was isolated from the user's MongoDB server.

## Session 5 — Android expense flow and offline sync (P5)

**Goal:** let the user record today's spending in a few taps, even without internet.

- [x] Add local expense storage and a durable pending-operation queue.
- [x] Build the add/edit screen with validation and today's date preselected.
- [ ] Save offline first; show pending/synced/failed states and a retry action.
- [ ] Sync on app opening and through background work when a network is available; avoid duplicate uploads.
- [ ] Add the dismissible opening prompt and quick-add action once per app opening.
- [ ] **Session complete:** an offline entry survives app restart, stays visible, syncs once after reconnect, and the opening prompt behaves as planned.

**Evidence / notes (2026-11-23):** Implemented SQLite expense plus pending-operation transaction, account-scoped records, client-ID upload, network-constrained JobScheduler retry, and UI states. In the next work session, `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` passed: nine local Android JVM tests, debug APK, and lint. Tests reopened SQLite after save, verified a version 1 migration, kept two owners with the same client ID separate, and simulated offline/reconnect, lost create response, expired session, and edits/deletes during upload with a fake API. Real-device app restart, JobScheduler delivery, HTTPS connectivity, and prompt behavior remain unverified; acceptance stays open. No HTTPS API address is available yet.

**Follow-up (2026-11-23):** Added 12 Robolectric UI tests (`DailyFlowTest`, with an in-memory stand-in for the device-only AndroidKeyStore) for a logged-in user. They found and I fixed a real bug: the default "Food" category was never selected because the `RadioButton`s had no ids when added to the group, so the first save was rejected until a category was tapped. After the fix, tests confirm: invalid amounts (blank, 0, negative, text, 3 decimals) are rejected and store nothing; today's date is preselected; a valid save is written to SQLite with a queued operation, survives re-opening the activity, and shows as pending; the opening prompt shows on open, stays dismissed while navigating, and returns after backgrounding; the prompt's Add action opens the entry form. `testDebugUnitTest assembleDebug lintDebug assembleRelease` passed (21 tests, 0 failures). These are JVM checks, not a device run.

## Session 6 — Dashboard and history (P6)

**Goal:** make recent spending and month totals easy to understand.

- [ ] Build Home with today's total, this month's total, monthly category breakdown, recent expenses, and clear empty state.
- [ ] Build History grouped by date with month navigation, edit, and delete paths.
- [ ] Include pending entries in displayed totals and show their status.
- [ ] Check totals after a backdated entry, edit, delete, and sync confirmation.
- [ ] **Session complete:** visible totals match the local expense list and a past entry is easy to find and correct.

**Evidence / notes (2026-11-23):** Implemented local today/month totals, category bars, recent entries, grouped monthly History, and edit/delete flows in XML Views. Debug APK builds and lint passes. Totals and large-text layout still need device checks; acceptance remains open. JVM UI tests (2026-11-23) confirm that today/month totals include pending entries, a backdated entry changes the month but not today, an edit and a delete of a pending entry update the totals and the list, another account's entries never appear, and the empty state shows guidance.

## Session 7 — Reminder and settings (P7)

**Goal:** offer an optional daily prompt around the selected local time.

- [ ] Add reminder enable/disable and time selection; persist the choice on the device.
- [ ] Request notification permission when enabling reminders and explain a denial.
- [ ] Schedule the reminder every enabled day; update it after time changes, reboot, or time-zone changes.
- [ ] Open the expense-entry flow when the notification is tapped; keep its text free of amounts.
- [ ] **Session complete:** enable, reschedule, disable, and notification tap are checked on a real device.

**Evidence / notes (2026-11-23):** Implemented local reminder preference, inexact daily rescheduling, Android 13+ permission prompt, reboot/time-zone/time-change handling, and notification tap to entry. Debug APK builds and lint passes. Real-device timing, permission denial, and notification tap checks remain open. JVM tests (2026-11-23) confirm: the alarm is set for the chosen time within the next 24 h, rescheduled on time change, restored after a boot broadcast, and cancelled on disable; the notification text contains no digits or currency symbol; its tap intent opens the Add expense screen; no notification is posted without permission; and turning the switch on without permission leaves the reminder off and shows the explanation. Delivery on a real phone is still unchecked.

## Session 8 — Release check (P8)

**Goal:** verify the complete daily workflow and recoverability.

- [ ] Walk through a week of entries, including backdated expenses and a month boundary.
- [ ] Check sign-up/login, empty state, offline save/restart/reconnect, failed sync, and duplicate retry.
- [ ] Check notification permission denial, large text, and the reminder on a real device.
- [ ] Confirm no secrets are tracked and database backup/restore works.
- [ ] Build and install a release candidate on the intended Android phone.
- [ ] **Session complete:** the end-to-end flow works and backed-up server data can be restored.

**Evidence / notes (2026-11-23):** Final `assembleDebug lintDebug assembleRelease` passed (91 tasks, 24 s). The release APK is unsigned; no install or end-to-end phone test is claimed. `git diff --check` and local documentation link checks passed. `git check-ignore` confirmed `.env`, `node_modules`, local SDK properties, and APK/build outputs are excluded; `bun.lock` is present for source control. A test MongoDB dump was restored into a separate empty test database; account and expense counts both matched 1:1, and a sample expense retained `amountMinor=1234` and `occurredOn=2026-11-23`. The temporary restore database was removed. The owner will supply the HTTPS API origin through `DAYBOOK_API_BASE_URL` later; an intended phone remains unavailable, so release acceptance is open.

**Deployment (2026-11-23):** The code is on GitHub (`himanshuraimau/daybook`, branch `main`). A Vercel project `daybook-api` (root directory `server`, Bun runtime) is linked to it. Vercel Authentication covers preview deployments only so the phone app can reach the production URL. `MONGODB_URI` must be added to the project's environment variables (production) before `/health` can report `ok`; until then the API answers 503.

## Session 9 — Budget API and cycle logic (P9)

**Goal:** the numbers behind "how much can I still spend" are correct and tested before any screen uses them.

- [ ] Add the budget profile model and `GET/PUT /api/budget` with validation and last-write-wins by `updatedAt`.
- [ ] Add pure Kotlin cycle and budget calculations with unit tests (payday 27, payday 31 in short months, payday 1, over budget, streak).
- [ ] Store the profile per account on the phone, upload it when online, adopt a newer server copy.
- [ ] **Session complete:** tests cover cycle boundaries, month-end paydays, over-budget amounts, account separation, and offline-then-online upload.

**Evidence / notes:** _not started_

## Session 10 — Streak redesign of the existing screens (P10)

**Goal:** apply the chosen Streak direction to every screen that exists today.

- [ ] Design system: colors (light/dark), Nunito, 3D buttons, cards, nav.
- [ ] Redesign Sign-up/Login, Home, Add expense (number pad, category chips), History, Settings.
- [ ] **Session complete:** existing tests pass; every screen checked in light and dark on the emulator.

**Evidence / notes:** _not started_

## Session 11 — Budget, fixed spends, Insights (P11)

**Goal:** let the owner set salary, payday, and fixed spends and see what is left.

- [ ] Budget setup and fixed-spends screens (first login, Home card, Settings).
- [ ] Left-to-spend and daily allowance on Home; History by cycle; Insights tab; streak.
- [ ] **Session complete:** salary, payday, and rent change Home and Insights correctly, including after an offline edit and restart.

**Evidence / notes:** _not started_

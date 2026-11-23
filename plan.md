# Daybook — product and build plan

**Status:** First-release product contract. Implementation progress is tracked in [sessions.md](sessions.md).

## 1. What Daybook solves

I want a simple way to remember and record what I spend each day, then understand where my money goes. Daybook should make that daily habit easy: open the app, add today's expenses quickly, and see a useful summary without navigating a complex finance tool.

### Product rules

- **Fast daily entry:** recording one expense should take only a few taps.
- **Gentle habit support:** show an add-expense prompt each time the app is opened and offer an optional daily reminder.
- **Clear numbers:** totals must be correct, dated correctly, and easy to read.
- **Small first release:** build the spending workflow and dashboard before adding other finance features.
- **Private by default:** show no spending amounts in reminder notifications; protect the API and database.
- **Works during connection gaps:** save an expense on the phone immediately and sync it to the server when connectivity returns.

## 2. Confirmed stack and current starting point

| Part | Choice | Notes |
| --- | --- | --- |
| Android app | Native Kotlin | Existing starter is in `mobile/`. It currently uses Android XML Views and has no third-party UI library. Keep this for the first release to stay small; revisit Jetpack Compose only if the UI needs it. |
| API | TypeScript running on Bun | Put future backend code in `server/`; leave that folder untouched during planning. Bun's official guide covers Mongoose integration. |
| Database | Existing MongoDB server through Mongoose | Connection details stay in server environment variables, never in the Android app or Git. |
| Reminder | Android local notification | The phone schedules it; the API does not need a notification service. |
| Money | INR only | Display ₹; store integer paise for calculations. |
| Accounts | Email/password sign-up and login | Each account sees only its own records. |
| Offline data | Android local storage plus background sync | Pending entries stay visible until the server confirms them. |

The Android starter targets API 36 and supports API 26+. A working Android SDK/build has not yet been verified on this machine.

## 3. First release: exact scope

### 3.1 Expense entry

An expense has an amount in INR, date, category, and optional note. The date defaults to **today on the phone** and can be changed for a missed entry. The save button is disabled until the required values are valid. After save, show a clear confirmation and updated totals. Saving must work without internet: persist the expense locally first and label it **Pending sync** until the server confirms it. A saved expense can be edited or deleted; deletion needs a confirmation or undo path.

Suggested starting categories: Food, Transport, Shopping, Bills, Health, Entertainment, and Other. Categories can be adjusted during design; custom categories are later work.

### 3.2 Open-app prompt

On every new app opening, show a small, dismissible prompt on the home screen: **“Add today's spending”** with **“Add expense”** and **“Not now”** actions. It must not block the dashboard. The add action opens the form with today's date. If an expense was already entered today, the prompt still offers quick entry because there may be more spending to record. Avoid showing it again when simply returning from the add form or switching screens in the same app session.

### 3.3 Daily reminder

Settings offer a reminder switch and a time picker, with **10:00 p.m. local time** as the proposed default. The reminder is off until the user enables it, then fires **every day**, even if an expense was already logged. Notification text: **“Any spending to add today?”**; tapping it opens the entry flow. Handle notification permission on Android 13+, changes to the chosen time, device reboot, and time-zone changes. The reminder is intended for *around* the chosen time; Android may delay an inexact alarm. Do not request exact-alarm access unless we later decide minute-level precision is essential.

### 3.4 Minimal dashboard and history

The home screen shows **today's total**, **this month's total**, a small **category breakdown for the current month**, and the **most recent expenses**. Include a prominent add button. Locally saved, unsynced expenses appear in these totals and in History, visibly marked as pending. An empty state should say what to do next rather than displaying empty charts. History lists expenses newest first, grouped by date, with a month selector and a path to edit an entry. Keep the first release to three areas: **Home**, **History**, and **Settings**, plus the add/edit form.

### 3.5 Settings

Settings contain the reminder switch/time, INR display, account information, and sign-out. If unsynced expenses remain, sign-out must explain that they are still on the device and avoid silently deleting them. Avoid adding settings that have no working behavior.

### 3.6 Sign-up and login

The first-run flow offers **Create account** and **Log in** with email and password. Sign-up/login require a connection; after a successful login, the app can save expenses offline for that account. Require a normalized email and a password of at least 8 characters. Sign-up creates the account and signs the user in immediately: there is no email verification, no password reset, and no SMTP dependency (this is a personal app). Provide clear validation; a duplicate email gets a plain "already has an account" message. Persist the signed-in session securely, support logout, and keep local account data separated by account.

## 4. Data, API, and app behavior

### Expense record

| Field | Initial design | Reason |
| --- | --- | --- |
| `id` | Server-generated identifier after sync | Stable server-side editing and deletion. |
| `clientId` | UUID generated on the phone | Prevent duplicate creates if an upload is retried. |
| `ownerId` | Account identifier | Prevent another client from reading the data. |
| `amountMinor` | Positive integer paise | Avoid floating-point errors in money totals. |
| `currency` | Fixed `INR` | One currency in the first version. |
| `category` | One of the initial categories | Enables breakdowns without a category-management screen. |
| `occurredOn` | Local calendar date such as `2026-11-23` | An expense belongs to the day the user selects, independent of UTC conversion. |
| `note` | Optional short text | Extra context when wanted. |
| `createdAt`, `updatedAt` | Server timestamps | Useful for ordering and troubleshooting. |

Use one `expenses` collection with one document per expense, plus an accounts collection. Validate required fields and allowed ranges on the server as well as in the app. Use a unique owner + client ID constraint for retry-safe creates, and an index that supports owner + date queries; add others only when a real query needs them. Monthly totals and category totals can be computed from expenses for the first release, with no stored summary documents.

### Proposed API contract

| Action | Route | Purpose |
| --- | --- | --- |
| Sign up | `POST /api/auth/signup` | Create an account with email and password. |
| Log in | `POST /api/auth/login` | Start a session. |
| Session | `GET /api/auth/me` | Identify the current account. |
| Log out | `POST /api/auth/logout` | End the current session. |
| Create | `POST /api/expenses` | Add an expense. |
| List | `GET /api/expenses?from=YYYY-MM-DD&to=YYYY-MM-DD&cursor=...` | History and recent items; paginated. |
| Read one | `GET /api/expenses/:id` | Refresh a changed expense before another edit. |
| Update | `PATCH /api/expenses/:id` | Edit amount, date, category, or note. |
| Delete | `DELETE /api/expenses/:id` | Remove a mistaken entry. |
| Summary | `GET /api/summary?month=YYYY-MM&today=YYYY-MM-DD` | Month totals and category totals, plus today's total for the phone's local date. |
| Health | `GET /health` | Check that the API is running. |

The API should return consistent error shapes and reject invalid dates, amounts, and access to another account's records. Sign-up and login need password hashing, session/token handling, and basic protection against repeated login attempts. The Android app talks to the internet-reachable API over HTTPS; MongoDB is never accessed directly from the phone. Keep credentials and tokens out of logs. The API base URL will be configured later; no server address is assumed here.

### Client structure

Keep one Android app module at first. Separate screen logic, API/data access, and domain models in packages; add modules or dependency injection frameworks only when the app grows enough to justify them. Store expense records and a durable pending-operation queue in an app-private local database (Room is the leading choice); screens read local data so they keep working offline. Save an entry and its pending upload together, then request synchronization when connected and retry with background work if needed. On app launch/return, attempt sync again. The server is authoritative after confirmation, but the local copy remains the screen's source of data. An offline save is **not** described as backed up to the server until it has synced. Show pending/failed status and allow retry; do not lose or duplicate an expense after app restart or repeated network attempts. Android may defer background work, so sync is automatic when conditions allow, not guaranteed at the instant connectivity returns.

For the first version, guarantee offline **creation**. A pending entry may be edited or deleted locally before upload. Editing or deleting a synced entry requires a connection; the server rejects stale updates with a conflict response, then the client refreshes the server copy before another edit. Local expense data must be isolated by account; changing accounts must never upload one person's queued data under another account.

## 5. Screen and interaction design

1. **Home:** readable totals first, one obvious **Add expense** action, small category list or bars, then recent items. The opening prompt sits near the top and can be dismissed.
2. **Add/edit:** amount gets focus; date defaults to today; categories are quick to choose; note is optional; one save action. Keep labels, sync status, and error text explicit.
3. **History:** dated list, month navigation, tap to edit. Show the category, amount, and pending status clearly.
4. **Settings:** reminder controls and account/INR information.
5. **Authentication:** simple, separate sign-up and login forms before the main app; explain connection errors clearly.

Use a calm visual style with generous spacing and restrained color. The interface must support large text, screen readers, clear touch targets, and light/dark appearance where practical. No bank-style feature dashboard or decorative charts in the first release.

## 6. Delivery plan

These numbered components map to the sessions in [sessions.md](sessions.md). A component is complete only when its acceptance checks pass.

### P1 — Product decisions and screen sketches

- [x] Confirm INR-only money, sign-up/login, offline creation with later sync, and a daily enabled reminder.
- [x] Confirm categories, auth details, and the first dashboard figures.
- [x] Sketch Sign-up/Login, Home, Add/Edit, History, and Settings, including empty/offline/error states in [design.md](design.md).
- [x] Agree on short labels and notification wording.

**Done when:** the account flow and four main screen flows, including pending-sync states, are written down clearly enough to build.

### P2 — Development setup and API foundation

- [x] Verify the Android starter builds and runs on a device or emulator once an Android SDK is available.
- [x] Set up a Bun + TypeScript server in `server/`, environment configuration, health route, and MongoDB connection through Mongoose.
- [x] Define account and expense models, input validation, per-account access control, and a minimal backup/restore approach for the existing MongoDB server.

**Done when:** the Android starter launches, the API starts locally, and database connectivity can be checked without committing secrets.

### P3 — Authentication and account safety

- [ ] Implement sign-up, login, session validation, and logout on the API and Android app.
- [ ] Hash passwords, protect account-scoped routes, handle session expiry, and keep local account data separated.
- [x] Keep sign-up and login simple: no email verification, no password reset, no SMTP (decided 2026-11-23).

**Done when:** a new account can be created and used to log in, another account cannot see its expenses, and an expired session does not upload queued data to the wrong account.

### P4 — Expense API

- [x] Implement create, list, edit, delete, and summary routes.
- [x] Check date boundaries, paise arithmetic, pagination, invalid input, owner isolation, and idempotent create retries.

**Done when:** an expense entered via the API appears in the right day/month totals and can be corrected or removed safely.

### P5 — Android expense flow and offline sync

- [x] Build the add/edit screen and persistent local expense/operation storage.
- [ ] Implement automatic network-constrained upload and retry, plus an app-open sync attempt.
- [ ] Add loading, validation, pending-sync, synced, and failed-sync states.
- [ ] Add the dismissible opening prompt and quick-add path.

**Done when:** opening the app offers today's entry; an expense saved offline remains visible after app restart, uploads once after connectivity returns, and the prompt does not reappear during ordinary in-app navigation.

### P6 — Dashboard and history

- [ ] Build the simple Home summary and recent expenses.
- [ ] Build History with month navigation and edit/delete actions.
- [ ] Verify totals match the locally visible expense list, including pending entries, after changes and sync.

**Done when:** today's and this month's numbers are correct and a past expense is easy to find and fix.

### P7 — Reminder and settings

- [ ] Add reminder on/off and time choice, persisting the preference on the device.
- [ ] Request notification permission in context, schedule the local reminder, and handle reboot/time-zone/time changes.
- [ ] Open the add-expense flow from a tapped notification.

**Done when:** the reminder can be enabled, changed, disabled, and tested on a real device; it is sent every enabled day and contains no sensitive amount.

### P8 — Release check

- [ ] Walk through a week of sample expenses, including backdated items and month boundaries.
- [ ] Check sign-up/login, first-run, denied permission, offline save/restart/reconnect, duplicate-upload retries, empty history, and large-text behavior.
- [ ] Confirm secrets are excluded from Git, database backup/restore works, and a release build can be installed.

**Done when:** the complete daily flow works on the intended phone and the data can be recovered from a backup.

## 7. After the first release

Consider these only after daily expense logging works well: income entries, budgets, recurring expenses, savings goals, export, and richer multi-device sync. Choose future features from actual use, so the app stays small.

## 8. First-release decisions and deployment input

1. **Categories:** Food, Transport, Shopping, Bills, Health, Entertainment, and Other. No custom categories in v1.
2. **Dashboard:** today's total, this month's total, monthly category totals, and recent expenses. All include locally pending entries.
3. **Authentication:** normalized email and a password of at least 8 characters. Sign-up logs the user in at once. Email verification, password reset, and SMTP were dropped on 2026-11-23 because this is a personal app. A forgotten password has no in-app recovery; reset it directly in the database if ever needed.
4. **Offline editing:** pending entries can be changed locally. Synced entries require a connection for edit/delete; a stale version causes a conflict and refresh.
5. **Wording:** “Add today's spending?” / “Add expense” / “Not now”; “Spent today”; “This month”; “Pending sync”; “Couldn't sync. Your expense is saved here.” / “Retry”; reminder: “Any spending to add today?”
6. **Connectivity:** the Android HTTPS API address is supplied by the owner through the `DAYBOOK_API_BASE_URL` environment variable at build time. No server address is assumed in source.

## 9. Technical references

- [Android notification permission](https://developer.android.com/develop/ui/views/notifications/time-sensitive)
- [Android alarm scheduling and inexact delivery](https://developer.android.com/develop/background-work/services/alarms)
- [Android app architecture guidance](https://developer.android.com/topic/architecture)
- [Android offline-first data guidance](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Android WorkManager network constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Bun's Mongoose guide](https://bun.com/guides/ecosystem/mongoose)
- [MongoDB index design](https://www.mongodb.com/docs/manual/data-modeling/schema-design-process/create-indexes/)

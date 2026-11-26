# Daybook UI design — "Streak"

**Chosen direction (2026-11-23):** option F, *Streak*. A friendly, habit-first look: deep navy ink, teal and amber, rounded Nunito type, chunky 3D buttons, and a streak card that rewards logging every day. The app's job is unchanged: make daily expense entry quick, then show a clear picture of what is left to spend. How the budget, fixed spends, EMIs, Insights and streak work is described in the [README](../README.md#how-the-money-works). The other design options that were considered are not part of the build.

Implementation stays on native Android XML Views (no Compose, no UI library). Everything below must work in light and dark, follow the phone's theme setting, and stay usable at 1.3× font size.

## 1. Principles

1. **One number per screen first.** Home leads with what is left to spend (or today's spending when no budget exists). Everything else supports it.
2. **Add in a few taps.** The number pad is always on screen; category and date are single taps; note is optional.
3. **Money is never hidden.** Over-budget shows as "Over by ₹…" in the warning color, never a silent zero.
4. **Honest state.** Pending, synced and failed expenses are always labelled. The streak counts only what is on the phone.
5. **Playful, not noisy.** 3D buttons and the streak card carry the personality; the rest is calm cards on a tinted background.
6. **Accessible.** Touch targets ≥ 48dp, text contrast ≥ 4.5:1 in both themes, state never conveyed by color alone, every icon-only control has a content description.

## 2. Design tokens

### Color (light / dark)

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| `bg` | `#F1F7F8` | `#0D2030` | Screen background |
| `surface` | `#FFFFFF` | `#15324A` | Cards, inputs |
| `surface2` | `#E3EEF1` | `#1F4563` | Tracks, inactive fills |
| `ink` | `#16324F` | `#EAF4F8` | Primary text |
| `muted` | `#5C7285` | `#93AEBE` | Secondary text |
| `line` | `#CFE0E6` | `#23506F` | Outlines and 3D card edges |
| `accent` | `#00A896` | `#1FD1BC` | Primary actions, hero card |
| `accentDark` | `#00776A` | `#0E8F80` | 3D edge of accent surfaces |
| `onAccent` | `#FFFFFF` | `#06302B` | Text on accent |
| `amber` | `#FFB703` | `#FFC93D` | Prompts, streak, highlights |
| `amberDark` | `#D99500` | `#C79200` | 3D edge of amber surfaces |
| `warn` | `#9C6400` | `#FFC93D` | Pending, caution text |
| `bad` | `#D6405A` | `#FF7C93` | Failed sync, over budget |

Category colors (light / dark): Food `#F2A400`/`#FFC93D`, Bills `#7B6CF6`/`#A397FF`, Shopping `#F0587B`/`#FF86A2`, Transport `#1E9AD6`/`#52BBF0`, Health `#22B073`/`#43D697`, Entertainment `#F57C2A`/`#FF9B55`, Other `#6E8798`/`#9DB5C4`. A category tint is its color at 16–25% over `surface`.

### Type — Nunito (bundled, not downloaded at runtime)

| Style | Size | Weight | Use |
| --- | --- | --- | --- |
| Display | 40sp | 800 | Hero and amount entry, tabular figures |
| Title | 22sp | 800 | Screen titles |
| Heading | 16sp | 800 | Section headings |
| Body | 14sp | 600 | Rows, inputs |
| Label | 12sp | 700 | Captions, chips, status |
| Overline | 11sp | 800, +8% tracking, uppercase | Field and section labels |

Amounts always use tabular figures and Indian digit grouping (₹1,24,000.50).

### Shape and depth

Cards: 20dp radius, 2dp `line` outline, 3dp solid bottom edge in `line` (the "3D" look). Hero and primary buttons: 22dp / 16dp radius with a 5dp bottom edge in `accentDark`. Chips: 14dp radius, 2dp outline, 3dp bottom edge. Pressed state moves the surface down by the edge height and removes the edge (80ms). No blur, no gradients, no shadows other than these solid edges.

Spacing scale: 4, 8, 12, 16, 20, 24dp. Screen side padding 16dp. Card inner padding 12–16dp.

### Motion

Only meaningful motion: the button press (above) and a short pop of the streak flame after saving the day's first expense. Both respect the system "remove animations" setting.

## 3. Components

- **Hero card** — accent fill, `onAccent` text, overline label, Display amount, up to two translucent chips. One per screen.
- **Streak card** — flame badge, "N days in a row", one-line encouragement, and a seven-day strip: filled teal circle with a check for a logged day, dashed outline for a missed day, today's circle ringed in amber.
- **Prompt card** — amber tint with amber outline and edge; heading, one line of help, a 3D amber **Add expense** button and a text **Not now**.
- **Expense row** — category circle (category color tint, icon), note or category as title, "Category · date" caption, amount right-aligned, and a status line: *Pending sync* (warn pill), *Synced* (muted text with a check), *Sync failed · Retry* (bad pill). Failed rows open the retry dialog.
- **Rank row** — label and amount on one line, a 10dp rounded bar below in the category color on a `surface2` track.
- **Chip** — icon + label; selected chips use the category color for outline and edge and a tint fill.
- **Number pad** — 3×4 keys (1–9, `.`, 0, backspace), 3D, 56dp tall. Amount accepts up to 2 decimals and at most ₹10,00,00,000.
- **Bottom navigation** — four tabs: Home, History, Insights, Settings. The selected tab has an accent pill behind icon and label.
- **Segmented control** — two or three options for Insights cycle and similar choices; selected segment is a 3D ink pill.
- **Section header** — Heading on the left, optional muted action ("See all") on the right.
- **Dialogs** — rounded 24dp cards in `surface`, 3D buttons, destructive action in `bad`.
- **Empty state** — a small teal illustration (wallet), one sentence, one action.

## 4. Screens

### 4.1 Sign up / Log in

Teal circle logo with a 3D edge, "Daybook" Title, tagline "Know what you spent, one day at a time." Email and Password fields, one primary button (**Create account** or **Log in**), and a text link to switch. Password rule shown inline ("At least 8 characters"). Server errors appear under the fields in `bad`. There is no verification or reset step.

### 4.2 Home

Top bar: "Daybook" and an avatar chip. Then, in order:

1. **Streak card.**
2. **Hero card.** With a budget: overline "LEFT TO SPEND · 27 NOV – 26 DEC", Display amount, chips "₹1,040 a day · 12 days left" and "1 pending". Over budget: the amount reads "Over by ₹2,300" and the hero uses `bad` fill with `onBad` text. Without a budget: overline "SPENT TODAY", today's total, chip "This cycle ₹18,430".
3. **Prompt card** (once per app opening, dismissible).
4. **Today row** — "Today ₹1,240.50" with the number of entries, only when a budget hero is shown.
5. **Budget nudge** (only without a budget): "See what's left each day — set your salary" with a 3D button **Set up budget**.
6. **Where it went** — top three categories as rank rows for the current cycle; "See all" opens Insights.
7. **Recent** — the latest three expenses.

A floating **Add expense** 3D button stays above the navigation bar. Empty state: "Add your first expense today."

### 4.3 Add / edit expense

Title "Add expense" (or "Edit expense") with a back button. Overline "AMOUNT", Display amount with a blinking caret. Category chips (7, three to a row, Food preselected; Entertainment reads "Fun" on the chip). The amount and chips scroll; the date chips (**Today** / **Yesterday** / **Pick date**), the optional note field (240 characters; its hint asks where the money went, e.g. "chai at the station") and the number pad stay fixed above the Save button so a whole entry is reachable without scrolling. Full-width 3D **Save expense** button. Edit mode adds a **Delete** text button in `bad` with a confirmation dialog. Validation shows inline under the amount ("Enter an amount above ₹0"). Above the category chips, when there is history and the screen is not editing, an overline **QUICK PICKS** and a horizontally scrolling row of up to five 3D chips: "Repeat · Auto ₹150" first, then frequent ones such as "Chai ₹20" (labels longer than 14 characters are cut with an ellipsis). A tap fills amount, category and note without saving. Under the category chips a text link, **Paying in instalments? Set up an EMI**, opens the EMI dialog prefilled with the note, category and typed amount (as the instalment); saving it adds the EMI to the budget instead of saving a one-off expense. Without a budget the link asks for a salary first. It is hidden when editing an existing expense. Saving writes locally first; the confirmation is a toast ("Saved on this phone. Waiting to sync.") and the streak flame pops if it is the day's first entry.

### 4.4 History

Title "History". Cycle switcher: back chevron, "27 Oct – 26 Nov" (calendar month when no payday is set), forward chevron (disabled on the current cycle). Total for the cycle under the switcher. Expenses grouped by date with a day header ("Mon 23 Nov · ₹1,240.50"). A one-line hint says "Tap an expense to edit or delete it." Tapping a row opens edit, where Delete sits at the top right. Empty cycle: "No expenses in this cycle."

### 4.5 Insights

Title "Insights". Segmented control **This cycle / Last cycle**. Without a budget the budget-dependent items are omitted.

1. **Summary strip** — three cards: *Spent*, *Fixed*, *Left* (Left in `bad` when negative).
2. **Daily spending** — bar chart, one bar per day of the cycle (bars for days after today are empty), a dashed line for the budget per day (spendable ÷ days in the cycle), today's bar in amber and days above the line in `bad`. Tapping a bar shows that day's total.
3. **Pace** — one sentence: "At this pace you'll finish ₹6,400 under budget." or "…₹2,100 over budget." plus a progress bar of cycle elapsed vs budget used.
4. **Where it went** — all categories as rank rows with percentage of spending.
5. **Numbers** — a 2×2 grid: *Average a day*, *Biggest spend* (with its note), *No-spend days*, *Vs last cycle* (percent change up to the same day).

Every figure has an accessible text equivalent. Empty cycle: "Nothing to analyse yet."

### 4.6 Budget setup

Reached from the Home nudge, first login (skippable with **Set up later**) and Settings → Budget. One scrolling screen with three cards:

1. **Salary** — ₹ amount per month.
2. **Payday** — stepper (− / +) and a label "Paid on the 27th". Helper text: "Your cycle runs from the 27th to the 26th."
3. **Fixed spends and EMIs** — list rows (category icon, name, a second line, amount, delete) and two 3D buttons, **Add fixed spend** and **Add EMI**. An EMI row's second line says where it stands: "EMI · 3 of 12 · total ₹60,000", "EMI · starts next cycle" or "EMI · finished". Footer line: "Fixed ₹15,000 · Spendable ₹35,000" for the current cycle, updating live; in `bad` if fixed exceeds salary.

**EMI dialog.** A scrolling dialog with permanent labels above each field (Name, Monthly instalment, Number of months, Total cost optional), a live line "₹5,000 × 12 months = ₹60,000", a **First instalment** choice (This cycle / Next cycle), "Instalments already paid" (only for This cycle), category chips, and Cancel / Add pinned at the bottom. Entering the total works out the instalment (rounded to the nearest paisa); entering the instalment works out the total. A note explains that the EMI is set aside automatically and is not added as an expense.

4. **Savings target** — an optional ₹ amount kept aside every cycle. **One-off income** — rows (name, date, "+₹10,000", delete) and an **Add income** button that opens a dialog with Name and Amount, dated today. The footer line adds "Saving ₹…" and "Extra income ₹…" when present: "Fixed ₹0 · Saving ₹5,000 · Extra income ₹10,000 · Spendable ₹55,000".

A full-width **Save budget** button. Edits made offline are saved on the phone and uploaded later; Settings shows "Budget saved on this phone, waiting to sync" until then.

### 4.7 Settings

Title "Settings". A **Budget alerts** card has the switch "Warn me about my budget" and helper text; turning it on without notification permission asks for it and leaves the switch off until it is granted. Cards: **Budget** (salary, payday, fixed total, tap to edit), **Daily reminder** (switch, time, helper "Around this time every day. Notifications never include amounts."; denied-permission explanation and a link to system settings), **Appearance** (three-way segmented control: System, Light, Dark; applies immediately and is remembered), **Account** (email, currency INR, sync status with Retry), and a bad-outlined **Sign out** button. Signing out with unsynced entries explains they stay on the phone for that account.

## 5. States and copy

- **Offline:** no banner on every screen; the sync status lives on rows and in Settings. Sign-up and login say "Connect to the internet to continue."
- **Loading:** Home and History render from local data immediately; a thin teal progress line appears under the top bar only while a refresh runs.
- **Errors:** plain sentence plus action ("Couldn't sync. Your expense is saved here. Retry").
- **Notification:** title "Daybook", text "Any spending to add today?" — no amounts.
- **Wording:** "cycle" is used only when a payday is set; otherwise the UI says "this month".

## 6. Light and dark

Both themes use the token table. Dark mode keeps the same 3D edges (edges are `line`/`accentDark`, not black). Hero text uses `onAccent`, which flips to deep teal-black in dark mode for contrast. Images and the logo need no separate assets.

## 7. Build notes (Android)

- Fonts: bundle Nunito Regular/SemiBold/Bold/ExtraBold in `res/font/` and set `fontFamily` in the theme.
- Colors in `values/colors.xml` and `values-night/colors.xml`; no hard-coded hex in layouts.
- 3D surfaces are `layer-list` drawables (edge layer under a face layer), pressed state via a selector.
- Charts (daily bars) and the streak strip are small custom `View`s drawn with `Canvas`; no charting library.
- Icons are vector drawables from the set used in the mockups (food, transport, shopping, bills, health, fun, home, history, insights, settings, plus, back, flame, check).
- Money math, cycle dates, streak and projections live in a pure Kotlin `domain` package with unit tests; screens only format and display.


### Widget and shortcut

A resizable home-screen widget (4×2 by default) on a rounded card: an overline (LEFT TO SPEND / OVER BUDGET / SPENT TODAY / DAYBOOK), a bold amount, a caption ("₹7,144 a day · 4 days left") and a round teal **+** that opens Add expense. Tapping elsewhere opens Home. A static launcher shortcut, **Add expense**, does the same as the + button.

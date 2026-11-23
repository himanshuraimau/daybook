# Daybook — interface design direction

**Status:** Chosen first-release direction, 23 November 2026. This document translates the product in [plan.md](plan.md) into a screen and visual specification. The research trail is in [design-research.md](design-research.md). These are Daybook design choices, inspired by agency process and principles; no agency screen, branding, or asset should be copied.

## 1. Design idea: an editorial expense journal

Daybook should look like a modern personal logbook: **one clear number, one clear action, then the supporting details**. The feel is confident and crisp, with large typography and a small number of expressive colors. The interface should make adding today's spending feel easy, not like filling out an accounting form.

**Visual keywords:** editorial, calm, precise, human, current.

**What this means on screen:** a large daily amount, short date labels, strong alignment, ample space, a bright but limited accent, and expense rows that read like a dated journal. Category bars describe actual spending; they do not look like progress toward a budget that Daybook has not defined.

## 2. Research translated into Daybook

| Agency case | Observed design lesson | Daybook application |
| --- | --- | --- |
| [350life × Cashews](https://350life.com/work/cashews) | The studio tested alternatives before choosing one meaningful figure for the opening view. | Make **spent today** the focal number, with month total and recent entries nearby to explain it. Do not imply “safe to spend” or predict future money. |
| [Arounda × Kinves](https://arounda.agency/works/kinves) | Direct access to frequent tasks and a clear primary purpose per screen. | Put **Add expense** within thumb reach and give Home, History, Entry, and Settings distinct jobs. |
| [Clay × Lulo Bank](https://clay.global/work/lulo-bank) | Straightforward navigation with small visual and motion cues. | Use brief save and sync feedback; keep visual effects secondary to the amount and status. |
| [Daylight × EarnIn](https://www.daylightdesign.com/projects/earnin) | Visualizations and micro-interactions were explored and tested around money over time. | Start with a simple monthly category bar list. Add a trend chart only if it answers a real question after use. |
| [Humbleteam × Afternoon](https://humbleteam.com/work/afternoon) | Consistent cards with one job and plain prompts. | The opening reminder card has one message, an Add action, and a dismiss action. |

These case studies describe different products. The layout, copy, palette, and components below are original proposals for Daybook.

## 3. Visual system

### Color

The palette uses warm paper-like neutrals, dark ink, a cobalt action color, and one lime highlight. Cobalt makes the primary action easy to spot; lime is reserved for the daily-entry prompt or a small positive confirmation. No color alone communicates sync or error state.

| Token | Light value | Use |
| --- | --- | --- |
| Canvas | `#F5F4EF` | Main screen background. |
| Ink | `#202726` | Main text and prominent numbers. |
| Muted ink | `#68716D` | Secondary labels on Canvas. |
| Primary | `#424DE8` | Add/Save actions, selected tab, focused field. |
| Primary text | `#FFFFFF` | Text on Primary. |
| Prompt | `#DDF97C` | Opening prompt surface, used sparingly. |
| Soft surface | `#ECEBFF` | Month summary or category detail surface. |
| Divider | `#D9DDD8` | Quiet separation between journal rows. |
| Error | `#B43E32` | Failed sync and validation alongside text/icons. |
| Success | `#236D50` | Synced confirmation alongside text/icons. |

For dark mode, start with Canvas `#121818`, surface `#1D2625`, main text `#F4F5F1`, secondary text `#BBC6C1`, and action `#A7B3FF` with dark ink text. Test every component in both modes. The proposed light palette has at least **4.5:1 text contrast** for the listed text/background pairs (for example muted ink on Canvas: 4.57:1; white on Primary: 6.07:1). Recheck contrast after implementation and when any token changes. Android recommends scalable text, sufficient contrast, and at least 48 dp touch targets. [Android accessibility guidance](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)

### Type and spacing

- Use Android's system sans font initially, with strong weight and size differences instead of adding a font asset. Use tabular numerals for aligned amounts where the chosen text rendering supports them.
- App title: **24 sp, bold**. Hero amount: **48–56 sp, bold**; allow it to shrink or wrap gracefully for large values and font scaling. Section heading: **20 sp, semibold**. Row amount/body: **16–18 sp**. Supporting labels: **14 sp**.
- Use 20 dp side padding, an 8 dp spacing rhythm, 24–32 dp between major sections, and 16–20 dp internal card padding. Cards can use 20–24 dp corner radius; rows should mostly sit directly on the canvas instead of each becoming a card.
- Use distinct date and category labels, not icons alone. Avoid tiny all-caps text for essential information.

### Components and motion

- **Primary action:** filled cobalt **Add expense** button or extended floating action button; only one persistent primary action. The opening prompt may also offer the same action because it is shown temporarily.
- **Daily prompt card:** lime surface, short question, Add action, and a clearly labeled **Not now** action. It appears once per new app opening and never blocks the screen.
- **Expense row:** category marker, note or category name, date/time context if useful, right-aligned ₹ amount, and text status when pending or failed. Avoid a different saturated color for every category.
- **Category breakdown:** horizontal bars with category name, ₹ total, and proportion of actual monthly spend. Label the values so the bars are understandable without color.
- **Feedback:** brief save confirmation and a small pending-to-synced transition. Avoid celebratory animation for ordinary spending. Respect device reduced-motion preferences where available.

Android's navigation guidance supports a bottom bar for three to five top-level destinations and a prominent floating action for the highest-priority task. Daybook uses **Home / History / Settings** plus one Add action. [Android layout and navigation guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns)

## 4. Screen layouts and behavior

### 4.1 Home — daily overview

```text
DAYBOOK                                      23 NOV

SPENT TODAY
₹420                                   [ + Add ]

┌  Add today's spending?                         ┐
│  Add expense                         Not now   │  shown once on opening
└                                                ┘

THIS MONTH                              ₹8,750

WHERE IT WENT
Food           ███████████       ₹3,200
Transport      █████             ₹1,450
Other          ███                 ₹900

RECENT                              See all →
Coffee · Food                           ₹200
Lunch · Food                            ₹180
Bus · Transport                          ₹40   Pending sync

Home                 History              Settings
```

The real screen should use proper spacing, not box-drawing borders. The total is the visual anchor. The daily prompt sits high enough to notice without hiding the total. If a user has no expenses yet, show `₹0` and “Add your first expense today” with the same Add action. If offline, keep local totals visible and show a compact “Offline · 2 expenses waiting to sync” status; never silently present pending data as server backed up.

### 4.2 Add / edit expense — focused entry

```text
‹ Back                          Add expense

AMOUNT
₹ [ 0 ]                         numeric keyboard

CATEGORY
[ Food ] [ Transport ] [ Shopping ]
[ Bills ] [ Health ] [ More ]

DATE                 Today, 23 Nov  ›
NOTE                 Add a note (optional)

[ Save expense ]                  fixed near bottom
```

Focus the amount field. Validate a positive INR amount before save. The date defaults to today but can be changed. Category choices should be large enough to tap and show selection with shape/text as well as color. When offline, save locally and show **“Saved on this phone. Waiting to sync.”** An edit screen uses the same layout with a clear delete action and confirmation/undo. The session that defines offline editing must decide how a pending change behaves before this flow is finalized.

### 4.3 History — dated journal

```text
History                         November 2026  ▾

TODAY · 23 NOV                         ₹420
  Coffee · Food                        ₹200
  Lunch · Food                         ₹180
  Bus · Transport                       ₹40   Pending

YESTERDAY · 22 NOV                     ₹760
  Groceries · Food                     ₹620
  Auto · Transport                     ₹140
```

Group entries by local calendar date, newest first. Each row opens editing. Month selection changes the list; do not require a filter screen. A failed sync gets a visible **“Sync failed · Retry”** action. Empty month copy: “No expenses recorded this month.”

### 4.4 Settings — reminder and account

```text
Settings

DAILY REMINDER
Remind me every day                  [ On ]
Time                                10:00 PM

ACCOUNT
Email                          name@example.com
Currency                              INR (₹)
Sync status                         All synced
Sign out
```

Turning the reminder on is the moment to request notification permission. If denied, explain that reminders are off and provide a route to system settings. If there are pending expenses at sign-out, explain their status and preserve account isolation; never silently discard them.

### 4.5 Sign-up and login — short and reassuring

```text
Daybook
Know what you spent, one day at a time.

Email
Password
[ Create account ]
Already have an account? Log in
```

Login swaps the primary action to **Log in** and links to Create account. Keep onboarding to these essentials; do not ask for a budget, bank connection, or category setup before the first expense. Show connection errors plainly. Sign-up logs the user in immediately; there is no email verification or password-reset flow (personal app, decided 2026-11-23).

## 5. Content and state rules

| Moment | First-release wording |
| --- | --- |
| Opening prompt | “Add today's spending?” / **Add expense** / **Not now** |
| No entries | “Nothing recorded today. Add an expense to start your daybook.” |
| Offline save | “Saved on this phone. Waiting to sync.” |
| Sync complete | “Synced” |
| Sync failure | “Couldn't sync. Your expense is saved here.” / **Retry** |
| Reminder | “Any spending to add today?” |

Use neutral language for spending; avoid scolding, streak pressure, or implying a financial judgment. Use the same terms on every screen: **expense**, **spent today**, **this month**, **pending sync**.

## 6. Build and review notes

- Implement this in the existing Kotlin/XML Views app first. Modernity comes from hierarchy, typography, spacing, color, and responsive states; no Compose migration or large UI dependency is required for the proposal.
- Verify the Home layout on a small phone and with large font settings; long ₹ values and notes must not collide with actions.
- Check light/dark colors, contrast, 48 dp touch targets, screen-reader labels, keyboard behavior, and the visible status of offline entries.
- If the user prefers a different visual personality, keep the same screen hierarchy and change the palette/shape/type tokens before building components.

**Visual choice:** editorial cobalt and lime. A calmer deep-teal and clay variation can be explored later without changing the screen hierarchy.

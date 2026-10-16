# Daily

A native Android daily task manager focused on one day at a time. Kotlin,
Jetpack Compose, Material 3, Room.

## What it does

- **Day view (home)** — one day per screen with an oversized date header.
  Swipe or use the arrows to move between days. Fast add-task field is always
  visible. A collapsible day-level notes panel hangs off the header.
- **Task rows** — tap a row to expand it inline: per-task note, and a
  Schedule toggle that reveals a start-time picker and duration field. Timed
  tasks show a live time-remaining pill (derived from start + duration vs.
  now — never stored). Completing a task gives a haptic tick and a spring
  pulse on the checkmark.
- **Calendar overview** — month grid with per-day density dots: accent dots
  for timed tasks, neutral dots for untimed, `+N` overflow. Tap a day to jump
  to its Day view.
- **Voice** — a built-in assistant over your own list: add, read back,
  complete, move, reschedule, annotate and delete tasks by speaking, with
  spoken replies. Reachable from the device assist gesture, a
  quick-settings tile, the launcher shortcut or the mic in the app. See
  [Voice](#voice).
- **Settings** — theme: System / Light / Dark. The preference persists via
  DataStore.

## Architecture

- Single `app` module, single Activity, Navigation Compose.
- Room for persistence: `tasks` and `day_notes` tables, dates stored as
  epoch-day for cheap range queries; the calendar reads a GROUP BY summary.
- Manual DI (`AppContainer` on the `Application`) — no Hilt at this size.
- One `AppViewModel` over two repositories; day pages collect flows keyed by
  arbitrary dates because the pager renders neighbouring days.

## Theme system

- `ui/theme/` holds all tokens: monochrome Material color schemes (true
  near-black `#050505` in dark), oversized black-weight typography, large
  corner radii, a spacing scale (`Dimens`).
- The single green accent lives in a semantic `AccentPalette`
  (CompositionLocal, exposed as `AppTheme.accent`) and is also mapped to
  Material `primary` so stock components pick it up. New semantic colors are
  added as palette slots — no screen rework.

## Voice

A built-in voice assistant over your own list — no API key, no account, no
per-utterance cost. Capture is Android's `SpeechRecognizer`, replies are
spoken with the platform synthesiser, and the understanding in between is a
hand-written grammar in `voice/`.

### Getting to it

- **Hold-for-Daily** — Settings → *Voice* → *Choose the assistant app* makes
  Daily the device's assistant, so the home gesture (or power long-press)
  opens voice straight into your list. Works from the lock screen.
- **Quick-settings tile** — add *Daily voice* to the shade.
- **Launcher shortcut** — long-press the app icon → *Speak*.
- **In the app** — the mic in the day header, or the mic in the add-task
  field, which dictates a task straight onto the day you are looking at.

There is no "hey Daily" hotword, deliberately. Android offers no free
high-quality always-listening keyword: the options are a continuous
recognizer loop (battery-hungry, unreliable, throttled) or a third-party
engine with its own key and keyword model. Press-to-talk on four entry
points is the honest trade.

### What it understands

| Say | It does |
| --- | --- |
| "add call Delta Controls tomorrow at 3 for 45 minutes" | creates it, timed, with reminders armed |
| "remind me to send the Novonix invoice on monday" | same, untimed |
| "what's on today" / "what's on friday" / "read my day" | reads the day back |
| "what's next" | the next timed task still ahead |
| "mark the delta deck as done" / "check off the invoice" | completes it |
| "uncheck the delta deck" | reopens it |
| "push the board pack" / "move it to next friday" | moves days ("push" alone means tomorrow) |
| "schedule the site audit tomorrow at 10 for an hour" | times it — and creates it if it didn't exist |
| "unschedule the deck review" | clears the time |
| "add a note to the deck review saying send the fonts too" | appends to the task note |
| "note for tomorrow saying studio closed" | appends to the day note |
| "roll over everything to today" | brings unfinished tasks forward |
| "sync now", "open the calendar", "show me monday", "help" | housekeeping |

**Strings of operations** work in one breath: *"add call Fidelity at 11,
then mark the invoice done, then what's next."* Steps are split on an
explicit sequencer (`then`, `after that`, `also`) — never on a bare "and",
because "add call Delta and Nestlé" is one task title. If any step fails to
parse, the whole utterance is re-read as a single command.

Design rules worth knowing:

- **A verb is required.** An utterance with no recognised command is
  reported as not understood rather than becoming a task — a mis-heard
  fragment landing in your day is worse than being asked to repeat it. The
  dictation mic is the one exception; there, the whole utterance is a title.
- **Titles are resolved fuzzily, but it refuses rather than guesses.**
  "delta deck" finds "Polish the Delta Controls deck"; nothing close enough
  gets "I couldn't find a task like…". Two equally good matches get "I found
  2 matches… which one?" and you answer "the second one".
- **Deletions are confirmed** ("Delete X? Yes or no") unless you turn that
  off in Settings.
- **Today wins.** A spoken title resolves against today first, then a
  window of −14/+60 days.
- **Capitalisation survives.** The grammar lowercases to parse, then puts
  the original casing back, so client and product names stay intact.
- **Bare hours read like speech.** "at 3" is 3pm, "at 9" is 9am, "at 15:30"
  is taken as given.

### Settings

Spoken replies, sound cues (three short synthesised tones — mic open,
applied, failed), on-device recognition, and whether spoken deletions are
confirmed. On-device recognition is off by default: it is private and works
offline, but needs a downloaded language pack, and without one the
recognizer simply reports no match.

### Tests

The grammar carries unit tests (`app/src/test/.../voice`) — a mis-parsed
date fails silently in a way nothing else in the app does. They run on the
JVM, and in CI before the APK is built:

```
./gradlew testDebugUnitTest
```

## Google Sheets sync

Optional two-way sync with a Google Sheet as the backend — free, no
servers, no Google Cloud project. Setup:

1. Create a Google Sheet (sheets.new).
2. Extensions → Apps Script → paste `googlesheets/Code.gs`, change the
   `TOKEN` constant to a long random string, save.
3. Deploy → New deployment → Web app → Execute as **Me**, access
   **Anyone** → Deploy → copy the `/exec` URL.
4. In the app: Settings → *Google Sheets sync* → paste URL + token →
   **Save & sync**.

Tasks and day notes sync both ways with last-write-wins on `updatedAt`;
deletions propagate via tombstones. Editing rows in the sheet (title,
done, note, times) syncs back to the phone — an `onEdit` trigger stamps
`updatedAt`. The app syncs on launch and on demand. Multiple devices can
point at the same sheet.

## Building

```
./gradlew assembleDebug
```

Requires JDK 17+. CI runs the unit tests and builds the debug APK on every
push (`.github/workflows/android.yml`), uploads it as an artifact, and
publishes it to the `debug-latest` release for sideloading.

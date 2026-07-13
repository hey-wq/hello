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

## Building

```
./gradlew assembleDebug
```

Requires JDK 17+. CI builds the debug APK on every push
(`.github/workflows/android.yml`) and uploads it as an artifact.

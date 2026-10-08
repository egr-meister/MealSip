# MealSip

MealSip is a lightweight, fully offline Android water tracker organized around named daily periods.
Starter periods are **Breakfast, Lunch, Dinner and Evening**. You record water inside these blocks,
customize their names, icons, order and optional reminder times, and review daily history.

Period names are organizational labels only. MealSip does not track food, calories, diets, weight
or meal consumption, and it makes no health claims about drinking before, during or after meals.

## Features

- **Daily Period Board** (Today): date, total recorded volume, saved daily goal, a slim progress
  line, and one expandable block per period (icon, name, optional reminder time, recorded volume,
  entry count, *Add Water*, and its entries). Two columns on wide screens, in reading order.
- **Logging**: quick amounts (150 / 250 / 500 mL by default, editable in Settings) record water in
  that period at the current time; *Other amount…* opens the manual form (amount, period, date, time).
  Every addition shows a snackbar with **Undo**.
- **Editing and deletion**: amount, period, date and time can be changed; deletion offers Undo.
  Drafts survive rotation and process recreation; unsaved changes are confirmed before discarding.
- **Custom periods**: 1–8 active periods; create, rename, change icon, reorder (accessible
  *Move up* / *Move down*), archive.
- **Daily goal**: optional, 100–10,000 mL, effective-dated (*Apply today* / *Apply tomorrow*).
- **Optional reminders per period**, off by default.
- **History**: seven-day summaries with navigation to earlier ranges; each date opens its saved board
  where entries can be edited or deleted.
- **Settings**: goal, periods, per-period reminders, quick amounts, privacy information, Clear all data.

## Architecture

One native application module (`:app`), Kotlin + Jetpack Compose, manual dependency injection.

```
com.egrmeister.mealsip
├── data/local        Room entities, DAOs, AppDatabase, DataStore preferences
├── data/repository   RoomStore (MealSipStore implementation + Flows for the UI)
├── domain/model      Period, LayoutItem, WaterEntry, GoalChange, Outcome, MealSipStore boundary
├── domain/periods    PeriodService (configuration, validation), LayoutResolver, LayoutCodec
├── domain/logging    WaterLogService, EntryValidation, ActionGuard, UndoCenter
├── domain/goals      GoalService, GoalLookup, GoalProgress
├── domain/history    BoardBuilder (day boards and summaries)
├── domain/reminders  ReminderPolicy (pure rules), ReminderCoordinator, AlarmScheduler/ReminderNotifier
├── domain/time       TimeSource (injected clock + zone), TimeMath, TodayProvider
├── reminders         AlarmManager scheduler, notifications, receivers
└── ui                Compose screens + ViewModels (StateFlow, lifecycle-aware collection)
```

The domain layer is plain Kotlin with injected clock, time zone, alarm scheduler and notifier, so it
is unit-tested on the JVM with in-memory fakes. Database work runs through Room's suspend APIs
(off the main thread); multi-step writes use transactions.

## Toolchain

| Component | Version |
|---|---|
| compileSdk / targetSdk | **36** |
| minSdk | **26** |
| Android Gradle Plugin | 8.11.1 |
| Gradle (Wrapper, committed) | 8.14.3 |
| Kotlin | 2.2.0 (Compose compiler plugin 2.2.0) |
| KSP | 2.2.0-2.0.2 |
| Compose BOM | 2025.07.00 (Material 3) |
| Navigation Compose | 2.9.3 |
| Lifecycle | 2.9.2 |
| Room | 2.7.2 (KSP) |
| DataStore | 1.1.7 |
| Coroutines | 1.10.2 |
| JDK | 17 (Temurin in CI) |

All versions are pinned in `gradle/libs.versions.toml`; every directly used library is a direct dependency.

Android 16 (API 36) behaviour handled: enforced edge-to-edge (insets handled per screen, cutouts and
IME included), predictive Back (`enableOnBackInvokedCallback`, Back handled only for unsaved drafts),
no orientation or resizability restrictions on large screens.

## Builds

```bash
./gradlew testDebugUnitTest          # focused unit tests
./gradlew lintRelease                # release lint
./gradlew assembleRelease bundleRelease   # signed release (requires signing credentials)
```

Outputs:

- APK: `app/build/outputs/apk/release/app-release.apk` (local install / testing)
- AAB: `app/build/outputs/bundle/release/app-release.aab` — **the only artifact uploaded to Google Play**

R8 is off by default (first verify the signed non-minified release). Enable R8 + resource shrinking
with `-Pmealsip.minify=true`; the mapping file is `app/build/outputs/mapping/release/mapping.txt`.

## Period assignment, customization and historical snapshots

- Water belongs to the period the user explicitly selected. MealSip never infers a period from
  the clock time; periods are labels, not time ranges.
- Name, icon and order changes apply **from today**. Each change stores a
  `PeriodConfigurationVersionEntity` effective on today's date (the starter layout is stored as a
  baseline for earlier dates).
- The first record on a date stores a `DayPeriodSnapshotEntity` layout for that date. Earlier dates
  keep their saved labels and order; renaming today updates today's board while the stable period ID
  stays the same.
- Archiving removes the period from future logging choices, cancels its reminder, keeps all historical
  entries and keeps today's entries visible in an *Archived periods* section. At least one period
  stays active. Entries are never silently moved to another period.
- For a past date without records, logging uses the configuration effective on that date. Moving an
  entry to another date requires an explicit destination period available there (names are never matched).

## Goal history and date attribution

- Goals are stored as `GoalChangeEntity` rows with an effective local date. A goal applies until the next
  change; earlier dates are never rewritten. Dates before any goal show volume only.
- Progress = sum of all entries of the date (including archived periods) / saved goal × 100. Totals use
  `Long`. Percentages above 100% are shown; only the visual line is capped. Wording is neutral:
  "Your selected goal is reached."
- Each entry stores its absolute timestamp, logged local date, logged local time and zone ID. History
  groups by the recorded local date, so a later time-zone change never moves old records.
  Manually entered times inside a daylight-saving gap are rejected; ambiguous times use the earlier offset.
- Today refreshes at local midnight and on return to the foreground, without deleting anything.

## Offline operation, privacy, storage and backup

- No `INTERNET` or `ACCESS_NETWORK_STATE`; no accounts, backend, Firebase, ads, analytics, payments,
  cloud sync, Health Connect, sensors or location.
- Records live in app-private storage (Room database `mealsip.db`, DataStore `mealsip_prefs`).
- `android:allowBackup="false"`, `fullBackupContent` and `dataExtractionRules` exclude everything from cloud
  backup and device-to-device transfer.
- The packaged/merged manifest permission allowlist is exactly `POST_NOTIFICATIONS` and
  `RECEIVE_BOOT_COMPLETED` (checked in CI for the APK, the AAB and the merged manifest).
- In-app *Privacy information* screen describes local records and notifications.
- **Clear all data** (with confirmation) cancels alarms and visible notifications, deletes all records and
  preferences, and restores the starter periods with reminders disabled.

## Reminders: permissions, suppression rules and delivery limits

- Disabled by default, enabled per period; one reminder per period per day.
- Notification: title "MealSip", body "Would you like to record water in *&lt;period name&gt;*?".
  Tapping opens Today and expands that block. Reminders never log water or imply a meal happened.
- Android 13+: `POST_NOTIFICATIONS` is requested only when the user first enables a reminder, and never
  re-prompted. If denied, logging stays fully available, Settings shows the blocked state and offers an
  optional link to the system notification settings. App-level and channel-level switches are checked too.
- Scheduling: inexact one-shot `AlarmManager.set(RTC_WAKEUP)`, one pending occurrence per enabled period,
  explicit immutable PendingIntents, a non-exported alarm receiver. No `SCHEDULE_EXACT_ALARM`,
  `USE_EXACT_ALARM`, wake lock, foreground service, push messaging or battery-exemption request.
- Before posting, MealSip verifies: the period is still active; its reminder is still enabled; the callback
  matches the current configuration (generation + time); notification permission and channel allow
  delivery; the callback belongs to today; the period has no water entry today; the daily goal is not reached.
- **Application delivery policy** (not an Android guarantee): a reminder delayed by more than 60 minutes
  beyond its intended time is skipped. No backlog is delivered. One notification ID is reused.
- Rescheduled after delivery, settings changes, reboot (`RECEIVE_BOOT_COMPLETED`), time / time-zone changes,
  app update and app start. Future reminders use the current device time zone.
- Android may delay inexact alarms (Doze, battery saver). After a **force stop**, Android cancels all alarms
  and blocks the boot receiver until the app is opened again; reminders resume on the next launch.

## PKCS12 signing and GitHub Secrets

The release keystore is PKCS12 and lives **outside the project** (`../_keys/MealSip/`), never in Git.
`app/build.gradle.kts` uses `signingConfigs.getByName("release")` for the release build type; missing
credentials fail every release packaging task — there is no fallback to debug signing.

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the `.p12` keystore (one line) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Local release builds read `../_keys/MealSip/signing.properties` (or the same four values as environment
variables with `ANDROID_KEYSTORE_FILE`).

CI (`.github/workflows/android.yml`) uses the committed Wrapper and JDK 17, runs unit tests and release lint,
builds the signed APK and AAB, runs `apksigner verify --print-certs` (fails on errors or `CN=Android Debug`),
verifies the AAB with `jarsigner` and checks its signer against the keystore certificate, checks the
permission allowlist and native alignment, uploads verified artifacts and deletes the temporary keystore.
A self-signed upload certificate is valid for Google Play and is not rejected.

## 16 KB page-size findings

MealSip uses no NDK code and no dependency with native libraries is expected. CI lists every `.so` in the
APK and AAB (including transitive dependencies) and writes the result to `native-libs.md`; if any appear,
their ELF `LOAD` alignment must be ≥ 16 KB and the APK passes `zipalign -c -P 16`. With no native libraries,
ELF alignment does not apply; runtime testing in a 16 KB environment is still recorded as pending.
API 36 alone does not prove 16 KB compatibility.

## Local adb installation and logcat verification

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat --pid=$(adb shell pidof -s com.egrmeister.mealsip)
adb logcat -s MealSipReminders           # reminder scheduling / decisions
```

See `VERIFICATION.md` for the device checklist, artifact size and pending checks.

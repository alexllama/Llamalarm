# Llamalarm

An alarm-only Android app for people who want real snooze options. It is not a clock
replacement: the stock Clock app still handles timers, the stopwatch, and world clock.
Llamalarm's distinctive feature is a full-screen ringing screen with **six configurable
snooze buttons** (5 min, 15 min, 1 h, 4 h, 8 h, 24 h by default), all editable.

Built with Kotlin, Jetpack Compose / Material 3, and a small, inspectable JSON store.
Dark theme only. No account, no network access, no analytics.

## Requirements

- Android device with API 37 or newer (developed and tested on a Samsung Galaxy S24 Ultra).
- Android Studio (or a JDK matching the Gradle daemon toolchain, currently Java 25) to build
  from source. The repository includes the Gradle wrapper.

## Install and first launch

1. Build an APK (see [Building](#building)) or install a release APK you have built yourself:
   ```sh
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   Debug and release APKs have different signatures; uninstall the other one first if Android
   refuses the install.
2. Open Llamalarm. On first launch, grant the permissions it asks for (or use **Settings**
   to open the relevant system pages):
   - **Alarms & reminders** — exact scheduling, required for alarms to fire on time.
   - **Notifications** — the ongoing ringing notification and the upcoming-alarm reminder.
   - **Display over other apps** — lets the ringing screen appear over other apps.
   - **Full-screen notifications** — lets the ringing screen wake the phone over the lock screen.
3. Add your alarms in the app. There is no import from the stock Clock app; alarms are
   recreated manually.

## Creating and editing alarms

Tap **+** on the alarm list. The editor supports:

- **Time**, using the time picker.
- **Once** vs **Repeats**: **Once** is the default. A one-time alarm needs a date (including
  future dates). Choosing **Repeats** starts with no days selected — pick the ones you want
  using the single-letter weekday chips, or tap **Every day**. Save stays disabled until at
  least one day is selected.
- **Alarm title** — shown on the ringing screen (up to three lines). Tapping it scrolls the
  field into view above the on-screen keyboard.
- **Pause** — set an inclusive **From**/**To** date window to skip every occurrence inside it;
  **Clear** removes the window.
- **Ringtone** — choose **Default alarm sound**, **System sound** to open Android's own picker
  (showing the preinstalled alarm ringtones), or **Audio file** to pick a file from your
  music/audio library. Imported files are copied into the app's private storage, so
  they keep working if the source file is moved. If a custom sound is missing at ring time, the
  default alarm tone is used. **Use default** clears any custom choice. The last ringtone you
  save becomes the starting point for every new alarm you create; choosing **Use default** clears
  that remembered choice too.
- **Alarm options** — per-alarm volume slider (with a **Use default** choice that defers to the
  global setting) and vibration **On**/**Off**. New alarms start with vibration off.

The list is sorted by clock time from midnight onward (12:00 AM → 11:59 PM), ignoring dates.
It shows each alarm's schedule and "Next ring: …". Use the on/off switch to enable or
disable an alarm. The overflow menu (⋮) on an alarm offers:

| Action | Behavior |
| --- | --- |
| **Edit** | Reopen the editor. |
| **Skip next occurrence** | Skip only the next scheduled instance. For a one-time alarm this turns it off. |
| **Pause… / Change pause** | Pick an inclusive start and end date; every occurrence in that window is skipped. |
| **Resume (clear pause)** | Remove the pause window. |
| **Delete** | Remove the alarm. |

## When the alarm rings

The ringing screen shows the alarm's **configured time** (like a normal alarm clock, not the
current wall-clock time), its title, and:

- Six snooze buttons in two columns (edited in **Settings → Snooze options**).
- A single-tap **Stop** at the bottom — no long-press or confirmation.
- The screen wakes the display and appears over the lock screen and other apps. By default it
  also rings in silent/vibrate mode and through headphones; both can be changed in Settings.
- An ongoing notification with a **Stop** action while ringing.

Automatic behavior:

- If ignored, the ring stops after 2 minutes and rings **once more** after 5 minutes. If that
  retry is also ignored, the instance ends.
- Snoozing during the initial ring suppresses that 2-minute/5-minute retry.
- A snoozed alarm that is missed (for example, the phone is off) automatically resnoozes for
  the same duration, up to **3 misses**; then it gives up.
- At most **5 user snoozes** are allowed per alarm instance. After that, the snooze buttons are
  disabled and the screen asks you to Stop.

Snooze durations are honored exactly, including across midnight. Stopping or finishing an
instance schedules the next occurrence; a one-time alarm is switched off after it is resolved.

## Notifications and Settings

A dismissible **"Alarm in X"** notification is posted about 2 hours before the next alarm,
showing the alarm time and label; tapping it opens the app. It is cleared automatically when
the alarm starts ringing. Disable it with **Settings → Upcoming alarm notification**.

**Settings** contains:

- **Snooze options** — edit each of the six durations in hours/minutes, or reset to defaults.
- **Behavior** — vibration, gradual volume ramp, respect silent mode, play through headphones,
  upcoming-alarm notification, and global alarm volume.
- **Permissions** — shortcuts for exact alarms, display over other apps, full-screen
  notifications, and app notifications.

## Data and privacy

All state is local to the app's private storage; Llamalarm declares no internet permission.

- `files/alarms.json` — alarm definitions and schedule state.
- `files/settings.json` — global settings and snooze presets.
- `files/active_instances.json` — in-progress ringing/snooze state.
- `files/ringtones/` — copies of imported ringtone files.

Writing is atomic (temp file plus rename), and alarms are re-armed at startup, after reboot,
and after time/timezone changes.

## Building

```sh
JAVA_HOME=/path/to/jdk ./gradlew assembleDebug
JAVA_HOME=/path/to/jdk ./gradlew testDebugUnitTest
JAVA_HOME=/path/to/jdk ./gradlew assembleRelease
```

Debug and release APKs land in `app/build/outputs/apk/<variant>/`. The project uses AGP 9.5,
Gradle 9.6, Kotlin 2.2, Compose, and `minSdk`/`targetSdk` 37.

Release signing is optional: create a gitignored `keystore.properties` in the project root:

```properties
storeFile=llamalarm-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep the keystore and this file private and backed up; without them, `assembleRelease`
produces an unsigned APK. Never commit them.

> **Note:** release R8/optimization is intentionally disabled in `app/build.gradle.kts`.
> With AGP 9's `optimization {}` block enabled, the release build crashed on startup with
> `IllegalAccessError` from `androidx.startup`. Do not re-enable it without verifying a real
> launch on a device.

The debug build includes a `DebugRingActivity` for adb-triggered ring/snooze testing; it is
not present in release builds.

## Project layout

- `app/src/main/java/com/llama/llamalarm/domain/` — alarm models, occurrence calculation, and
  the pure ringing state machine.
- `app/src/main/java/com/llama/llamalarm/data/` — JSON store and repositories.
- `app/src/main/java/com/llama/llamalarm/alarm/` — scheduling, receivers, boot re-arming.
- `app/src/main/java/com/llama/llamalarm/ringing/` — foreground service, audio, notifications,
  and full-screen ringing UI.
- `app/src/main/java/com/llama/llamalarm/ui/` — list, editor, settings, theme.
- `app/src/test/` — unit tests for occurrence and ringing rules.

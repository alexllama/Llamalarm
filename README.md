# Llamalarm

An alarm-only Android app for people who want real snooze options. It is not a clock
replacement: the stock Clock app still handles timers, the stopwatch, and world clock.
Llamalarm's distinctive feature is a full-screen ringing screen with **six configurable
snooze buttons** (5 min, 15 min, 1 h, 4 h, 8 h, 24 h by default), all editable.

Built with Kotlin, Jetpack Compose / Material 3, and a small, inspectable JSON store.
Dark theme only. No account, no network access, no analytics.

## Requirements

- **On the phone:** Android with API 37 or newer (developed and tested on a Samsung Galaxy
  S24 Ultra). Sideloading must be allowed by the phone's owner.
- **On the computer:** Android Studio with the Android SDK platform 37 installed, plus a JDK
  for Gradle (Java 25 works; Android Studio's bundled JBR is fine). The repository includes
  the Gradle wrapper, so you do not need a separate Gradle install. Building needs internet
  access the first time so the wrapper and dependencies can be downloaded.

## Setting up the project on a new computer

The source lives in Git, but a few local files are deliberately **not** committed. Set up a
fresh machine like this:

1. **Get the code.** From a terminal:

   ```sh
   git clone git@github.com:alexllama/Llamalarm.git
   cd Llamalarm
   ```

   This uses SSH, so the new computer needs an SSH key added to your GitHub account. If you
   prefer HTTPS, use `git clone https://github.com/alexllama/Llamalarm.git` instead.

2. **Install Android Studio** and, in the SDK Manager, install the **Android SDK Platform 37**
   plus build tools. When you open the project, Android Studio generates `local.properties`
   with your SDK path (for example `sdk.dir=/home/alex/Android/Sdk`). That file is gitignored;
   do not try to copy it from another machine.

3. **Choose a JDK.** Point `JAVA_HOME` at a JDK, for example Android Studio's bundled one:

   ```sh
   export JAVA_HOME=/path/to/android-studio/jbr
   ```

   On this computer it is `/opt/android-studio/jbr`. You can also let Android Studio handle
   builds from its GUI and skip setting `JAVA_HOME` in a terminal.

4. **Restore the signing files** (needed to produce the same signed release APK as before).
   Both are gitignored and must be copied from the machine that has them, or from your backup:

   - `llamalarm-release.jks` — the release keystore.
   - `keystore.properties` — the keystore password, key alias, and key password.

   Keep both private. If you do not have them, you can generate a new keystore with Android
   Studio (**Build → Generate Signed App Bundle / APK → Create new key…**) and write a
   matching `keystore.properties`. Be aware that a new signing key means the new APK is a
   *different app identity* to Android: you must uninstall the old version from the phone
   before installing the new one, and that resets the app's local data. Without these files,
   `assembleRelease` still produces an APK, but it will be unsigned and Android will refuse
   to install it.

5. **Verify the setup** by building and running the tests (see [Building](#building)):

   ```sh
   JAVA_HOME=/path/to/jdk ./gradlew assembleDebug testDebugUnitTest assembleRelease
   ```

   The signed release APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Installing the app on a phone

There is no app store listing — the app is sideloaded as an APK. Pick one of these methods.

### Method A — install with adb (recommended if a computer is available)

1. On the phone, enable **Developer options** (tap *Build number* in Settings → About phone
   seven times), then turn on **USB debugging**.
2. Connect the phone by USB and approve the computer's debugging prompt on the phone.
3. Confirm the device is visible:

   ```sh
   adb devices
   ```

4. Copy the APK from the computer that built it to the new computer if needed, then install:

   ```sh
   adb install -r /path/to/app-release.apk
   ```

   On the original computer the file is
   `/home/alex/dev/Llamalarm/app/build/outputs/apk/release/app-release.apk`.
   Use `-r` to replace an existing install. If Android reports a *signature mismatch*, the
   APK was signed with a different key — uninstall the existing Llamalarm first (this deletes
   its local alarms), then install again. Debug and release APKs also have different
   signatures, so do not mix them.

### Method B — install directly on the phone (no adb)

1. Copy `app-release.apk` to the phone (USB file transfer, cloud drive, email attachment, etc.).
2. On the phone, open the file in a file manager. Android will ask to allow installation from
   that source; accept the prompt (Settings → Security/Privacy → *Install unknown apps*).
3. Tap **Install** and wait for it to finish.

   The first install must come from somewhere you trust — only use an APK you built yourself
   or copied from your own machine.

### First launch on the new phone

Open Llamalarm and grant the permissions it asks for (or use **Settings** to open the relevant
system pages):

- **Alarms & reminders** — exact scheduling, required for alarms to fire on time.
- **Notifications** — the ongoing ringing notification and the upcoming-alarm reminder.
- **Display over other apps** — lets the ringing screen appear over other apps.
- **Full-screen notifications** — lets the ringing screen wake the phone over the lock screen.

Then recreate your alarms in the app. **Alarms and settings are stored only in the app's
private storage on each device** — Llamalarm has no account, no export/import, and no
transfer tool, and there is no import from the stock Clock app. Android cloud backup may
restore app data when you sign in with the same Google account, but do not rely on it; assume
you will enter the alarms manually on the new phone.

## Creating and editing alarms

Tap **+** on the alarm list. The editor supports:

- **Time**, using the time picker.
- **Once** vs **Repeats**: **Once** is the default. A one-time alarm needs a date (including
  future dates). Choosing **Repeats** starts with no days selected — pick the ones you want
  using the single-letter weekday chips, or tap **Every day**. Save stays disabled until at
  least one day is selected.
- **Alarm title** — shown on the ringing screen (up to three lines). The first letter is always
  capitalized. Tapping it scrolls the field into view above the on-screen keyboard.
- **Pause** — set an inclusive **From**/**To** date window to skip every occurrence inside it;
  **Clear** removes the window. The list only says "Paused until …" while today is inside the
  window; a pause scheduled for the future does not show that label yet.
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
disable an alarm, or swipe an alarm left or right to delete it. The overflow menu (⋮) on an
alarm offers:

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
- The layout scrolls in landscape so the snooze buttons and **Stop** stay reachable on short screens.
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
showing the alarm time and label; tapping the notification body opens the app. Its **Dismiss**
action — or swiping the notification away — skips that alarm's next occurrence, exactly like
the **Skip next occurrence** menu item in the app (for a one-time alarm this turns it off).
This is a real cancellation, not just hiding the reminder: the alarm will not ring and will be
re-armed for the occurrence after the skipped one. It is cleared automatically when the alarm
starts ringing. Disable the notification with **Settings → Upcoming alarm notification**.

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

First-time setup (SDK, JDK, signing files) is covered in
[Setting up the project on a new computer](#setting-up-the-project-on-a-new-computer).

From a terminal, in the project root:

```sh
JAVA_HOME=/path/to/jdk ./gradlew assembleDebug
JAVA_HOME=/path/to/jdk ./gradlew testDebugUnitTest
JAVA_HOME=/path/to/jdk ./gradlew assembleRelease
```

You can also build from Android Studio with **Build → Generate Signed App Bundle / APK → APK →
release**; that uses the same `keystore.properties` and keystore described above.

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

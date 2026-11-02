# WordWake

WordWake is a personal alarm, reminder manager, daily planner and deadline tracker for Android. It reminds you what to do, when, why and how. It works with the app closed and with no internet.

- **Stack:** Java 17, Android SDK (min API 26 / Android 8.0, target API 36), AndroidX, Material Components 3
- **Storage:** local SQLite and SharedPreferences
- **Privacy:** no internet permission, analytics, ads or cloud backup

Why alarms failed and what was fixed: **[docs/ALARM_AUDIT.md](docs/ALARM_AUDIT.md)**.

> **Known problem on TECNO HiOS:** the phone freezes WordWake in the background and holds back its alarms, even with every setting allowed. WordWake rings on time while it's on screen, but it can't be relied on while the phone sleeps. For anything important, use **Add backup alarm in Clock** from the reminder's menu. See [Test status](#test-status).

## Features

| Area | What you get |
|---|---|
| Reminder types | Alarm, reminder, task, appointment, deadline, tax, bill, quote |
| Categories | 11 built-in categories plus your own, each with a color, an icon and an optional default sound |
| Repeat | Once, daily, selected days, weekly, monthly, yearly, custom dates, before a deadline |
| Alert window | Start and automatic stop, including across midnight; 5m to 2h presets or a custom length |
| Alert style | Ring once or repeat reminders (5, 10, 15, 30 minutes or custom) |
| Alert mode | Full alarm (sound, vibration, full screen) or a less intrusive notification, per reminder |
| Action plan | Checklist you can add to, edit, reorder and tick off, also from the alert screen |
| Quotes | Your own, built-in or favorites; on any reminder, on their own, or at random |
| Attachments | Gallery pictures, camera photos and documents, stored privately on the phone |
| Deadlines | Time remaining, alerts N days before, daily follow-ups, overdue tracking, status |
| Tax and bills | Type, jurisdiction, authority, year, filing and payment deadlines, amount, contact, website, documents, receipt attachment |
| Dashboard | Today's overview, next alarm, quick actions, upcoming list, completion percentage |
| Reminder list | Search; filter by status, type, category and date; pause, duplicate, complete, history |
| Calendar | Month grid with markers, day and week planner, move to another date |
| Diagnostics | Live status, **RUN ALARM SYSTEM TEST**, structured log, export to a file |
| Clock backup | Adds a matching alarm to the phone's own Clock app, for phones that hold back WordWake |
| Backup | Save to and restore from a .zip file you choose |

**Tax information is yours.** WordWake never supplies tax rules, rates or deadlines. It never makes payments or submits anything. It can open a website you saved.

## Build

Requires the Android SDK (platform 36) and JDK 17+. Android Studio's bundled JBR works.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> AGP 8.13.2 needs Gradle 9.5.0. AGP 8.x **does not work with Gradle 9.6 or newer**, so don't upgrade the wrapper without also moving to AGP 9.

## Install and set up

1. On the phone, turn on **Developer options → USB debugging**, connect it, and accept the prompt. Then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Installing over the previous version keeps your reminders.

2. Open WordWake and allow notifications.
3. **TECNO / Infinix / itel:** in *Phone Master → App management → App Launch*, allow WordWake. In *Settings → Apps → WordWake → Battery*, allow background usage. These were not enough on a tested TECNO KM6, but they may help on other phones.
4. **Check it on your own phone:** go to *Settings → Alarm diagnostics → RUN ALARM SYSTEM TEST*, then press the power button right away. Wake the phone after about a minute and read the result.
5. If it says **NOT DELIVERED**, use **⋮ → Add backup alarm in Clock** on anything important. Clock's alarm rings on time, but it shows only the title and uses Clock's sound. Open WordWake afterward for the full message. WordWake can't change that Clock alarm later, so edit or delete it in Clock if your reminder changes.

## Tests

```bash
./gradlew testDebugUnitTest
```

This next one needs a connected phone. **It uninstalls the app afterward, which deletes your reminders**, so don't run it on a phone with real data:

```bash
./gradlew connectedDebugAndroidTest
```

On HiOS, `AlarmDeliveryTest#systemTestAlarmIsDeliveredByAlarmManagerOnTime` hangs unless the phone stays on the WordWake screen, because HiOS freezes the test process.

## Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Alerts and their buttons. Asked only when you tap Allow. |
| `USE_EXACT_ALARM` (API 33+), `SCHEDULE_EXACT_ALARM` (API 32 and below) | Ring at the exact minute. For alarm apps; declare this in the Play Console if you publish. |
| `USE_FULL_SCREEN_INTENT` | Turn the screen on and show the alert over the lock screen |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Play alarm sound reliably |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Optional Reliable delivery setting, off by default. Needs justification on Google Play. |
| `SET_ALARM` | Add a backup alarm to the phone's Clock app |
| `RECEIVE_BOOT_COMPLETED` | Restore alarms after a restart |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | The one-tap battery exemption dialog. Restricted on Google Play. |
| `WAKE_LOCK`, `VIBRATE` | Time-limited CPU wake while ringing; vibration |

Pictures, documents and audio use the system pickers, and the camera uses the camera app, so there is no storage or camera permission.

## What no app can guarantee

- **Phone off:** nothing can ring. Missed alerts are reported at the next start.
- **Force stop:** Android cancels all WordWake alarms until you open the app. Removing it from recents is a different action, and normally doesn't do this, though some brands treat it the same way.
- **Manufacturer battery managers** can override correct app behavior. TECNO HiOS does, and no WordWake setting has beaten it.
- **The lock screen is never bypassed.** "View details" asks for your normal unlock.
- **Do Not Disturb** usually allows alarms, depending on your settings.
- **Volume:** WordWake never changes system volume. It warns when alarm volume is low.

## Known limitations

- Descriptions are plain text with paragraphs. There is no bold or other rich formatting.
- Rescheduling in the calendar uses "Move to another date", not drag and drop.
- Custom categories share one icon and differ by color.
- If two alarms start at the same moment, the later one plays and the earlier one stays as a quiet notification.
- Backup files are not encrypted. There is no cloud sync.
- An unsaved reminder is lost if Android closes the app while the camera is open.

## Privacy

Everything is stored on the phone. The app has no network permission, and cloud backup and device transfer are disabled. Diagnostic logs and exports hold times, IDs and error names, never message text or titles. Opening a saved website hands it to your browser.

## Test status

Tested on one phone: TECNO KM6, HiOS, Android 15. No emulator image is installed. Results on one device don't prove reliability on other brands.

**Automated**

| Suite | Where | Result |
|---|---|---|
| `ScheduleEngineTest` (27), `ReminderSchedulingTest` (21) | Computer | **48/48 passed** |
| `ScheduleRepositoryTest` (4), `DatabaseUpgradeTest` (3), `AlarmSchedulerTest` (5) | Phone | **12/12 passed** |
| `AlarmDeliveryTest` (7) | Phone | **6/7 passed.** The real-delivery test hung because HiOS froze the test process. The same delivery passed through the app with it on screen. |
| Android Lint | Build | 0 errors, 40 warnings |

The phone tests ran just before the last small changes (the notification-check fix, the Clock offer after saving, and a wording fix). They were not rerun, but those changes were exercised by hand on the phone.

**The 30 requested scenarios**

Key: **Phone** = seen working on the TECNO · **Failed** = seen failing on the TECNO · **Computer** = passed in computer tests only · **Not tested** = not run.

| # | Scenario | Status |
|---|---|---|
| 1 | One-time alarm | **Phone** (with the app on screen). Backup alarm added through the app. |
| 2 | Daily alarm | Computer. Phone: next alert computed correctly. Your real daily alerts were **held back** in the background. |
| 3 | Weekly alarm | Computer |
| 4 | Monthly reminder | Computer |
| 5 | Multiple alarms at different times | **Phone:** 9 of 9 registered with Android. Delivery held back as in scenario 6. |
| 6 | Alarm with screen off | **Failed for WordWake (HiOS). Phone for the Clock backup.** |
| 7 | Alarm with phone locked | **Failed for WordWake (HiOS). Phone for the Clock backup.** |
| 8 | Alarm with no internet | Not tested. The app has no network permission. |
| 9 | Alarm after reboot | **Failed for WordWake (HiOS):** alarm restored, but held back, and rang 66 seconds late when the app was opened. |
| 10 | Alarm during battery saver | Not tested. Needs a change to a system setting. |
| 11 | Alarm after process termination | **Phone:** after a force stop, opening the app restored all alarms. Delivery held back as in scenario 6. |
| 12 | Notification permission denied | Not tested |
| 13 | Exact-alarm access denied | Not tested |
| 14 | After changing time zones | Computer |
| 15 | Long message | **Phone** (database and displayed text, with paragraphs) |
| 16 | Attached picture | Not tested |
| 17 | Custom audio | Not tested |
| 18 | Automatic termination | **Phone:** the test alert ended by itself |
| 19 | Repeated reminder intervals | Computer |
| 20 | Snooze and dismissal | **Phone:** regression test passed |
| 21 | Editing a schedule | **Phone** (database test). The full edit flow was not run. |
| 22 | Deleting a schedule | **Phone:** alarm cancelled, with a warning first |
| 23 | Marking a task completed | **Phone:** through the screens |
| 24 | Overdue deadline handling | Computer |
| 25 | Tax reminder workflow | **Phone:** created, listed, details, completed, history, deleted |
| 26 | Checklist completion | **Phone:** ticked on the details screen, progress updated |
| 27 | Database recovery | **Phone:** corrupted file replaced, and your real data upgraded with 9 of 9 reminders kept |
| 28 | Duplicate alarm prevention | **Phone** |
| 29 | Restoration after reboot | **Phone:** real restart, recovery ran at locked boot and restored all reminders |
| 30 | Background restrictions | **Failed: HiOS holds alarms back with every setting allowed.** Clock backup works around it. |

# WordWake

WordWake is a personal message and motivation alarm for Android. You write a message, choose
when it starts and when it stops by itself, and pick a sound. When the time comes, the phone
plays the sound and shows the whole message on screen, including over the lock screen. This
works with the app closed and with no internet.

- **Language / stack:** Java 17, Android SDK (min API 26 / Android 8.0, target API 36), AndroidX, Material Components 3
- **Storage:** local SQLite (`SQLiteOpenHelper`) and SharedPreferences
- **No internet permission, no analytics, no ads, no cloud backup**

---

## Build

Requirements: Android SDK (platform 36), and JDK 17+ (Android Studio's bundled JBR works).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> The project uses AGP 8.13.2 with Gradle 9.5.0. AGP 8.x **does not work with Gradle 9.6 or newer**
> (Gradle removed an internal API it depends on), so don't upgrade the wrapper without also moving to AGP 9.

Tests:

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew connectedDebugAndroidTest
```

The second command needs a connected device with USB debugging turned on.

## Install on a phone

1. On the phone, turn on **Developer options → USB debugging** and connect it by USB. Accept the "Allow USB debugging?" prompt.
2. Install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone and open it. You will have to allow "Install unknown apps" for your file manager.
3. Open WordWake. The first screen explains each permission. Tap **Allow** under Notifications.
4. **TECNO / Infinix / itel phones (HiOS/XOS):** open *Phone Master → App management → Auto-start* and allow WordWake. Then open *Settings → Battery → App battery management → WordWake* and choose *No restrictions*. Other brands are covered in-app under *Settings → Battery & reliability guide*.
5. Create a message and use **⋮ → Test alert now** to check the sound and the screen on your phone.

---

## How it works

```
UI (ui/*)  ──►  ScheduleRepository (SQLite)  ◄──  AlarmController (all state transitions)
                        ▲                                  │
                        │                                  ▼
               ScheduleEngine (pure Java,          AlarmScheduler ──► AlarmManager.setAlarmClock
               unit-tested timing logic)                   │                     │
                                                           ▼                     ▼
                       SystemEventReceiver  ───►  reconcileAll()        AlarmReceiver (FIRE / END)
                (boot, update, time/zone change,                                 │
                 exact-alarm permission change)                                  ▼
                                                     AlarmService (foreground, mediaPlayback)
                                                     AlarmPlayer · vibration · bounded wake lock
                                                     notification + full-screen intent ──► AlarmActivity
```

| Package | Responsibility |
|---|---|
| `model` | `Schedule`: the user's fields plus the persisted runtime state (`occStart`, `lastRingAt`, `dismissedOcc`, `snoozeUntil`, `ringActiveUntil`). |
| `engine` | `ScheduleEngine`: works out occurrences, windows, the next event, reminders, snooze, validation, DST and time zones. It has no Android dependencies. |
| `data` | SQLite database with step-by-step migrations and corruption reset, the repository, settings, and the example preset. |
| `alarm` | AlarmManager registration, receivers, the controller, the foreground ring service, and notifications. |
| `sound` | The sound catalogue (built-in, phone alarms and ringtones, user files) and `AlarmPlayer`. `AlarmPlayer` handles audio focus and falls back to another sound if one fails. |
| `ui` | Dashboard, editor, alarm screen, sound library, settings, and permissions/onboarding. |

### Scheduling model

- Each occurrence is a **window `[start, end)`**. The alarm starts at *start*. At *end*, the sound always stops, and the message closes too if "Close message at end time" is on. A 6:00–6:30 alert lasts 30 minutes, never 24 hours.
- If the end time is earlier than the start time, the window ends the **next day**. The editor shows "(next day)". Windows longer than 12 hours are rejected as likely mistakes.
- Repeat: **Once** (a date), **Daily**, or **Selected days**.
- Alert style: **Ring once** (rings until Stop/Dismiss/end) or **Repeat reminders** (rings every *N* minutes inside the window).
- Controls on the alarm:
  - **Stop alarm** silences the sound. The message stays on screen.
  - **Snooze** rings again after the snooze time. It is only offered when the snooze ends before the end time.
  - **Dismiss** ends this occurrence. No more reminders come until the next one.
- **No duplicate alarms.** Each schedule has exactly one AlarmManager `PendingIntent`, keyed by a schedule-specific data URI, so rescheduling replaces it. When an alarm fires, the receiver checks it against the stored state and ignores stale or duplicate deliveries.
- **Exact alarms** use `setAlarmClock()`. It fires in Doze and lets the app start the foreground service. If exact access is missing, the app falls back to `setAndAllowWhileIdle()` and warns that the alert may be late.
- **Recovery:**
  - After a reboot, app update, clock change, time-zone change, or exact-alarm permission change, every schedule is re-registered.
  - The same happens on every app start and on every dashboard open.
  - If the process dies or the phone reboots mid-ring, the ring resumes through an alarm broadcast, because Android 15 forbids starting a media foreground service from `BOOT_COMPLETED`.
  - A one-time alert missed while the phone was off is reported as "Missed".
- **DST / time zones:** times are wall-clock times in the phone's zone (it follows you when you travel) or in a fixed zone you choose. A time that falls in a DST gap moves forward.

---

## Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Alarm notification, Stop/Snooze/Dismiss buttons, full-screen launch. Asked once, only when you tap **Allow**. |
| `USE_EXACT_ALARM` (API 33+), `SCHEDULE_EXACT_ALARM` (API ≤ 32) | Ring at the exact minute. `USE_EXACT_ALARM` is for alarm-clock apps. If you publish on Google Play, declare WordWake as an alarm app in the Play Console. |
| `USE_FULL_SCREEN_INTENT` | Turn the screen on and show the message over the lock screen. On Android 14+, Play only grants this to alarm/calling apps, and the user can revoke it. The app detects this and links to the setting. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Play the alarm sound reliably while ringing. |
| `RECEIVE_BOOT_COMPLETED` | Restore alarms after a restart. |
| `WAKE_LOCK`, `VIBRATE` | Keep the CPU awake only while ringing (time-limited), and vibrate. |

There is no storage permission. Audio files are picked through the system file picker (Storage Access
Framework), and read access is kept with `takePersistableUriPermission`. There is no `INTERNET` permission.

## Android limitations (what no app can guarantee)

- **Phone switched off:** nothing can ring. A one-time alert missed this way is reported as "Missed" after power-on. Recurring schedules continue with their next occurrence.
- **After a reboot,** alarms are restored when Android delivers `BOOT_COMPLETED`. That happens after the first unlock on encrypted phones.
- **Force stop** (*Settings → Apps → WordWake → Force stop*) makes Android cancel all of WordWake's alarms and block its receivers until you open the app again. Opening the app re-registers everything.
- **Manufacturer battery managers** (TECNO/Infinix HiOS, Xiaomi, Huawei, Oppo, Vivo, Samsung, and others) can kill or delay apps despite correct API use. See the in-app guide.
- **Full-screen display** depends on the full-screen-intent permission and on the phone's lock-screen policy. If it isn't allowed, a high-priority alarm notification appears instead, and you tap it to open the message.
- **The lock screen is never bypassed.** The alarm screen appears on top of it, but the phone stays locked.
- **Do Not Disturb** usually lets alarms through, but this depends on the user's DND settings.
- **Volume:** WordWake never changes system volume. It warns when the alarm volume is low.

## Privacy

Messages, schedules, and settings are stored only in the app's private storage. The app has no
network permission. Cloud backup and device transfer are disabled (`allowBackup=false` plus
`data_extraction_rules.xml`). Logs contain schedule IDs only, never message text.

---

## Test status

### Automated

| Suite | Where | Result |
|---|---|---|
| `ScheduleEngineTest` (27 tests) | JVM | **27/27 passed** |
| `ScheduleRepositoryTest` (4) + `AlarmSchedulerTest` (5) | TECNO KM6, Android 15 | **9/9 passed** |
| Android Lint | build | passes (warnings only) |

### Scenario matrix (25 requested scenarios)

✅ = executed and observed on the TECNO KM6 · 🧪 = covered by automated tests only · ❌ = failed on device · ⏳ = not yet verified

| # | Scenario | Status |
|---|---|---|
| 1 | Alert with app open | ✅ It rang as soon as the app was in the foreground: alarm screen, full message, live countdown, `MediaPlayer` in `state:started` with `USAGE_ALARM`. |
| 2 | Alert with app in background / closed | ❌ **Blocked by TECNO HiOS until the user exempts WordWake** (see "Device findings"). ⏳ Retest after the exemption. |
| 3 | Alert while screen off | ❌ Same cause as #2: the screen was off and the alarm was withheld. ⏳ |
| 4 | No internet | 🧪 By design: the app has no `INTERNET` permission, so it cannot use the network at all. |
| 5 | Daily recurring | ✅ After the ring, the next alert showed as "Tomorrow · 11:10 PM". 🧪 Engine test. |
| 6 | Selected weekdays | 🧪 |
| 7 | Long multiline message | ✅ The 606-character preset was edited, saved and displayed with its paragraphs intact. ✅ DB round-trip test with emoji and CRLF. |
| 8 | Custom ringtone selection | ⏳ |
| 9 | Missing audio file → fallback | ⏳ (fallback chain in `AlarmPlayer`) |
| 10 | Dismissal | 🧪 |
| 11 | Snooze | 🧪 ✅ Snooze was hidden on device because 10 minutes would pass the end time. |
| 12 | Automatic stop at end time | ✅ At 23:15:04 the service had stopped, the audio had stopped, and the alarm screen had closed itself. |
| 13 | Cancelling an upcoming alarm | ✅ Instrumented tests on device. |
| 14 | Editing a schedule | ✅ Instrumented test on device. |
| 15 | Reboot recovery | 🧪 The phone was not rebooted. |
| 16 | Notification permission denied | ⏳ |
| 17 | Exact alarm denied | ⏳ (the device reported `exactAllowReason=policy_permission`, i.e. granted via `USE_EXACT_ALARM`) |
| 18 | Full-screen intent unavailable | ⏳ |
| 19 | Battery saving | ❌/✅ The manufacturer battery manager blocked alarms. The app now detects this and offers the exemption. |
| 20 | Time-zone / clock change | 🧪 |
| 21 | Duplicate prevention | ✅ Instrumented test on device; exactly one AlarmManager entry seen in `dumpsys alarm`. |
| 22 | Process termination / app update mid-window | ✅ After the update, reconciliation ran (`MY_PACKAGE_REPLACED`), and the missed start rang late inside the window as soon as HiOS let the app run. |
| 23 | Audio interruption (call) | ⏳ |
| 24 | Locked with PIN/biometric | ⏳ |
| 25 | Force-stop limitation | documented; ⏳ |

### Device findings (TECNO KM6, HiOS, Android 15)

The alarm was registered correctly as an exact alarm-clock alarm for 23:10:00, so Android itself
was ready to deliver it. But HiOS's power manager ("Hiber") froze the app 5 seconds after it left
the screen and **proxied (held back) its alarms**:

```
I/Hiber/stateManager: freeze uid: 10336 com.wordwake.app
I/Hiber/hiber: proxy alarm (uid=10336, pkgName=com.wordwake.app)
```

The alarm stayed past due until the app was opened again. Then it rang at once as a late start
and stopped correctly at the end time. No app can override this from code. What WordWake does about it:

- On TECNO/Infinix/itel and other aggressive brands, the dashboard counts a missing battery exemption as a **critical issue**.
- The permission screen offers **"Allow background running"**, the standard Android one-tap exemption dialog. It also explains the separate Auto-start switch in Phone Master.

No emulator system image is installed on the build machine, so device testing used one physical phone.
Results on one device do not prove reliability on other manufacturers.

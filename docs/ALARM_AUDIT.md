# WordWake alarm system audit

**Scope:** every component between saving a reminder and the alert reaching the user.
**Evidence used:** a full code review, plus logs and system state captured on a TECNO KM6 (HiOS, Android 15), on both the previous build and version 2.0.0.
**Status:** the code defects are fixed. **The main cause cannot be fixed from inside the app on this phone.** Its only proven workaround is a backup alarm in the phone's Clock app.

## Summary

**WordWake's alarms fail on this phone because the phone's power manager stops them, not because they are scheduled wrongly.** The HiOS power manager ("Hiber") freezes WordWake about 5 seconds after it leaves the screen and holds back its alarms:

```
13:13:48 I/Hiber/stateManager: freeze uid: 10336 com.wordwake.app
13:13:48 I/Hiber/hiber: proxy alarm (uid=10336, pkgName=com.wordwake.app)
```

The same test gave opposite results depending on whether HiOS could freeze the app:

| Alarm | Conditions | Result |
|---|---|---|
| WordWake test alarm | App on screen | Delivered **8 ms** after its due time; alert screen and audio worked |
| WordWake test alarm | Screen off; App Launch allowed, battery exemption on, Reliable delivery running | **Not delivered.** Still held when re-checked 5 minutes later |
| WordWake reminders | Real use on the old version | **Five alerts on Nov 2 passed silently**, and one alarm was still pending more than a day after its due time |
| Phone's Clock app | Screen off, locked | **Rang at exactly 1:39:00 PM**, woke the screen, played sound |

This matches the symptoms you reported:

| Symptom | Explanation |
|---|---|
| Doesn't ring at all | The app stays frozen until the alert's window closes. Seen directly. |
| Rings late | Something unfreezes the app, such as opening it, and the held alarm is then delivered. Seen directly. |
| Sometimes works | The app hasn't been frozen yet, for example because it was just used. Inferred, not seen directly. |

The audit also found six real defects in WordWake's own code, listed below. They are fixed, but they were not the main cause.

## Architecture as audited

| Layer | Component | Role |
|---|---|---|
| Timing logic | `ScheduleEngine` | Pure Java: works out the next event from stored state |
| Scheduling | `AlarmScheduler` | The only code that calls `AlarmManager` |
| Delivery | `AlarmReceiver`, `SystemEventReceiver` | Alarm broadcasts; reboot, update, time and zone events |
| State | `AlarmController` | All transitions, on a single background thread |
| Audible alert | `AlarmService`, `AlarmPlayer` | Foreground service, sound, vibration, wake lock |
| Display | `Notifications`, `AlarmActivity` | Full-screen intent, lock-screen display |
| Storage | `ScheduleRepository`, `WordWakeDatabase` | SQLite |

There is one scheduling engine. WorkManager, `setTimeout`, polling loops and background timers are not used for scheduling.

## Defects found and fixed

| # | Defect | Effect | Fix |
|---|---|---|---|
| 1 | HiOS freezes the app and holds its alarms (see above). | No alarm, or a late one | **Not fixable from the app.** See [Attempted fixes](#attempted-fixes-for-the-hios-freeze). Added: a backup alarm in Clock, honest warnings on the dashboard, and a late ring when the app is next opened if the window is still open. |
| 2 | **Snooze silently did nothing after Stop on a one-time alert.** Stop finished the alert and switched it off, and Snooze never switched it back on. | Snoozed alert never rang | `snooze()` re-enables it. A regression test is included. |
| 3 | **No alarms after a reboot until the first unlock.** The app ignored `LOCKED_BOOT_COMPLETED`, and its data was unreadable before unlock. | A phone that restarted overnight stayed silent | Device-protected storage and direct-boot-aware components |
| 4 | **Missed recurring alerts vanished silently.** Only one-time alerts were reported. | You could not tell an alert had failed | `wasMissed()` detection, a notification, and a log entry for all repeat types |
| 5 | **"Test alert" skipped AlarmManager**, so it passed on a phone that blocks real alarms. | False confidence | **RUN ALARM SYSTEM TEST** registers a real alarm and reports "not delivered" if it never arrives |
| 6 | **Nothing recorded lateness or failures**, and an `AlarmManager` exception on save was unhandled. | The problem could not be diagnosed, and saving could crash | Structured log with measured delay; failures are caught and logged |

Two smaller fixes: the dashboard re-registered every alarm each time ring state changed, and that extra pass is gone. The dashboard also said "Alarm setup looks good" on a phone that was holding alarms back, because it only checked permissions. It now goes by what actually happened.

## Attempted fixes for the HiOS freeze

| Attempt | Result on the TECNO KM6 |
|---|---|
| Exact alarm through `setAlarmClock()` | Registered correctly, then held |
| Battery exemption (present on the device) | Still frozen |
| App Launch "Allowed" in Phone Master (confirmed on screen) | Still frozen |
| **Reliable delivery** (idle foreground service, confirmed running) | **Still frozen.** So it is now off by default and described honestly. It may help on other phones. |
| Faking an exempt state such as silent audio | **Rejected.** Hiber spares apps in "important" situations (its log names live wallpaper and Bluetooth; other states may qualify). Imitating one would drain battery and is the kind of workaround the brief rules out. |
| **Backup alarm in the phone's Clock app** | **Works.** Rang on time with the phone asleep and locked. |

The Clock backup has real limits: it rings with Clock's sound and shows only the title, and WordWake can't change or remove it afterward. Clock can only hold daily, weekly and selected-day alarms, or a one-time alarm within 24 hours. One-time alarms added this way removed themselves from Clock after ringing.

## Checked and found correct

| Area | Finding |
|---|---|
| PendingIntent identity | Each schedule has its own data URI, so reminders cannot overwrite each other, and rescheduling replaces rather than duplicates. Covered by a test. |
| Request codes | FIRE and END alarms differ by URI and action, so they don't collide. |
| Exact alarms | `setAlarmClock()` is used when permitted. The device reported exact access granted. |
| Inexact fallback | Used only when exact access is missing, and the user is told. |
| Time calculation | Wall-clock times via `java.time`. DST, overnight windows and zone changes are covered by tests. |
| Duplicate delivery | Checked against stored state, so a repeated broadcast cannot ring twice. Covered by a test. |
| Cancellation | Deleted or paused reminders can't ring, and the receiver re-checks stored state. |
| Network | The app has no `INTERNET` permission, so no alarm can depend on a connection. |

## Causes of late alarms

| Possible cause | Finding |
|---|---|
| Manufacturer battery manager | **Confirmed** on TECNO: this is the main cause, and it isn't fixable from the app |
| Inexact scheduling | Not the cause here: the alarm was exact |
| WorkManager misuse | Not applicable: not used |
| Doze mode | Unlikely: `setAlarmClock()` is built to fire in Doze, and `device_idle` showed no delay. Not separately tested. |
| Date, UTC or repeat errors | None found; covered by unit tests |
| Service termination | Handled: redelivery plus `ringActiveUntil` resume. Not tested on a device. |
| Notification delay | Not observed |
| Battery saver | **Not tested**: it means changing a system setting on your phone |
| Alarm restoration | Fixed by defect 3. Not tested with a real reboot. |

## How Android restrictions affect alarms

| Condition | Expected behavior | Tested on the TECNO? |
|---|---|---|
| App on screen | Rings on time | **Yes: 8 ms late** |
| Screen off or locked | Rings; full-screen if allowed | **Yes: WordWake held back.** Clock rang on time. Full-screen alert over a lock screen is still unverified for WordWake. |
| App minimized, other apps in use | Same as above | **Yes: frozen within seconds, even during the phone tests** |
| Removed from recents | On stock Android, alarms continue. Some brands treat this like a force stop. **These are different actions.** | No |
| Process killed or app updated | Alarms stay registered, but an update did not restart the app on this phone. Everything is repaired when the app is next opened. | Partly |
| Force stopped | **Android cancels all alarms until the app is opened again.** No app can prevent this. | Yes, used during testing; the alarms came back when the app next started |
| Powered off | **Nothing can ring.** Missed alerts are reported at the next start. | No |
| Rebooted | Restored at locked boot. Before first unlock, picked sounds fall back to a built-in sound, and attachments are unavailable. | **Yes, real restart:** recovery ran at locked boot and again at full boot, restoring all 10 reminders present then. A test alarm due 4 minutes after boot was **still held back** and rang 66 seconds late when the app was opened. The phone had been unlocked by then, so ringing before first unlock remains unproven. |
| Notifications denied | Sound may play, but no visible alert. Logged and flagged. | No |
| Exact alarms denied | Falls back to inexact, with a warning | No. Exact access was granted on this phone. |
| Full-screen unavailable | High-priority notification instead | No |
| Clock or zone changed | Alarms recomputed | Computer tests only |
| No internet | Unaffected by design | Not run, but the app has no network permission |

## Trade-offs to review

- **Reliable delivery** stays available as an opt-in setting because it may help on other phones, though it didn't on this one. It shows a permanent silent notification, and publishing on Google Play would require justifying `FOREGROUND_SERVICE_SPECIAL_USE`.
- **Reminder text is now in device-protected storage**, so alarms can work before first unlock. That storage is not tied to your screen lock, though other apps still can't read it. Pictures and documents stay in lock-protected storage.
- **SQLite was kept instead of Room.** It already works, has tested migrations, and the brief asks to avoid unnecessary dependencies and not replace working parts.

## What is still unproven

1. **Whether a future HiOS setting or update changes this.** Only the settings on this phone were checked.
2. **Other phones and brands.** Everything here comes from one TECNO KM6.
3. **An alarm actually ringing before first unlock.** Recovery at locked boot is proven; the ring is not.
4. **WordWake's full-screen alert appearing by itself on a locked phone**, because HiOS never lets a background alarm get that far.

Test results are in the [README](../README.md#test-status).

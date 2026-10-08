# NFC Alarm

An Android alarm clock that only turns off when you scan a registered NFC tag.
No snooze, no dismiss button. Back and volume keys are ignored, volume is
held at max, and restarting the phone mid-alarm just makes it ring again.

Needs Android 8.0+ and a phone with NFC.

## Build and install

**Option A: Android Studio (easiest)**
1. Install Android Studio (free) and open this `NfcAlarm` folder. Let it sync; it downloads what it needs.
2. On your phone, turn on Developer options › USB debugging, then plug it in.
3. Press the green ▶ Run button. The app installs and opens.

To get an APK file instead, use Build › Build App Bundle(s)/APK(s) › Build APK(s), copy
`app/build/outputs/apk/debug/app-debug.apk` to the phone, and open it. You'll need to allow
"Install unknown apps" for whichever app opens it.

**Option B: GitHub (no Android Studio)**
Push this folder to a GitHub repo. The included workflow builds the APK automatically.
Download it from the repo's Actions tab › latest run › Artifacts › `nfc-alarm-apk`.

## First-time setup
1. Open the app and allow notifications.
2. Work through the **Setup** list until it says "All set". It covers NFC on, full-screen alarm
   permission, exact alarms, and the battery-optimization exemption.
3. Tap **Register a tag** and hold the tag to the back of the phone. Register a spare too.
4. Optional: under **Sound**, tap **Change** to pick a built-in alarm sound or any audio file
   (MP3, M4A, OGG, WAV), and **Preview** to hear it. If the file is later deleted, the alarm
   falls back to the default sound.
5. Tap the time to set it. The alarm turns on automatically once a tag is registered.
6. Tap **Test alarm**, lock the phone, and make sure it wakes you properly.

**Tags:** cheap NTAG213/215 stickers or key fobs work well. The app uses the tag's
built-in hardware ID, so nothing needs to be written to the tag. Put it somewhere
that forces you out of bed, like the bathroom or the coffee maker.

## How it works
- `AlarmScheduler`: `AlarmManager.setAlarmClock()`, the same exact, Doze-proof alarm the stock clock uses.
- `AlarmService`: foreground service that loops the default alarm sound (falls back to tones),
  vibrates, holds a wake lock, re-maxes alarm volume every 2 s, and reposts its notification if swiped.
- `RingingActivity`: full-screen lock-screen alarm that listens for tags in NFC reader mode.
  A matching tag ID is the only thing that calls `AlarmService.stop()`.
- `BootReceiver`: re-arms after reboot, app update, or clock change, and resumes ringing if the
  phone was restarted mid-alarm.

## Limits (by design of Android, not fixable)
- **Emergency off:** Settings › Apps › NFC Alarm › Force stop. Use this if you lose the tag.
  The alarm re-arms for the next day the next time you open the app.
- After a reboot, it rings once the phone has been unlocked.
- The alarm can't ring if the phone is powered off.
- If you turn NFC off, the alarm screen shows a button to turn it back on.

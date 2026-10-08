package dev.nfcalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by AlarmManager at alarm time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.setRinging(context, true)
        AlarmService.start(context)
    }
}

/** Re-arms the alarm after a reboot, app update, or clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.ringing(context)) {
            // Phone was restarted mid-alarm without the tag being scanned: ring again.
            AlarmScheduler.scheduleOneShot(context, System.currentTimeMillis() + 5_000)
        }
        AlarmScheduler.scheduleDaily(context)
    }
}

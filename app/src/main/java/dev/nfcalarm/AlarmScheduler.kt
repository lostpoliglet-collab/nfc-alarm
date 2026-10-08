package dev.nfcalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Uses AlarmManager.setAlarmClock(), the same mechanism the stock clock app uses:
 * it fires exactly on time, even in Doze, and shows the alarm icon in the status bar.
 */
object AlarmScheduler {
    private const val REQ_DAILY = 100
    private const val REQ_ONE_SHOT = 102   // test alarm / re-ring after reboot

    private fun operation(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, AlarmReceiver::class.java).setAction("dev.nfcalarm.FIRE.$requestCode"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Next occurrence of the saved time that is at least [minLeadMs] from now. */
    fun nextTrigger(ctx: Context, minLeadMs: Long = 0L): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.hour(ctx))
            set(Calendar.MINUTE, Prefs.minute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= now + minLeadMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** Arms (or cancels) the daily alarm to match saved settings. Returns false if not permitted. */
    fun scheduleDaily(ctx: Context, minLeadMs: Long = 0L): Boolean {
        if (!Prefs.enabled(ctx)) {
            ctx.getSystemService(AlarmManager::class.java).cancel(operation(ctx, REQ_DAILY))
            return true
        }
        return set(ctx, nextTrigger(ctx, minLeadMs), REQ_DAILY)
    }

    /** A separate one-off alarm that doesn't disturb the daily one. */
    fun scheduleOneShot(ctx: Context, timeMs: Long): Boolean = set(ctx, timeMs, REQ_ONE_SHOT)

    private fun set(ctx: Context, timeMs: Long, requestCode: Int): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val showApp = PendingIntent.getActivity(
            ctx, 101, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(timeMs, showApp), operation(ctx, requestCode))
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

package dev.nfcalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notifications {
    private const val CHANNEL_RINGING = "ringing"
    const val RINGING_ID = 1

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_RINGING) != null) return
        val channel = NotificationChannel(
            CHANNEL_RINGING, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shown while the alarm is ringing"
            setSound(null, null)          // the service plays the sound itself
            enableVibration(false)        // ...and handles vibration
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun ringing(ctx: Context): Notification {
        val openRinging = PendingIntent.getActivity(
            ctx, 200,
            Intent(ctx, RingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // If the notification gets swiped away (possible on Android 14+), put it straight back.
        val repost = PendingIntent.getService(
            ctx, 201,
            Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_REPOST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(ctx, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Alarm")
            .setContentText("Scan your NFC tag to turn it off")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openRinging)
            .setFullScreenIntent(openRinging, true)
            .setDeleteIntent(repost)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Android 14+ lets users (and the system, for sideloaded apps) revoke full-screen alerts. */
    fun canUseFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}

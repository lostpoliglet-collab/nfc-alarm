package dev.nfcalarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Plays the alarm sound and vibration until a registered tag is scanned.
 * There is deliberately no "stop" or "snooze" action anywhere except [stop],
 * which is only called from RingingActivity after a tag match.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_REPOST = "dev.nfcalarm.REPOST"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, AlarmService::class.java))
            } catch (e: Exception) {
                // Not allowed to start right now (rare) — retry through an exact alarm.
                AlarmScheduler.scheduleOneShot(ctx, System.currentTimeMillis() + 2_000)
            }
        }

        /** Only called after a registered tag has been scanned. */
        fun stop(ctx: Context) {
            Prefs.setRinging(ctx, false)
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing = false

    /** Every 2 s: undo any volume reduction, and beep if we had to fall back to tones. */
    private val keepLoud = object : Runnable {
        override fun run() {
            maxAlarmVolume()
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            AlarmScheduler.scheduleOneShot(this, System.currentTimeMillis() + 2_000)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.ringing(this)) {   // e.g. a restart after the alarm was already dismissed
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPOST) return START_STICKY  // goForeground re-posted it
        if (!ringing) startRinging()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        Notifications.ensureChannel(this)
        val notification = Notifications.ringing(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                Notifications.RINGING_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(Notifications.RINGING_ID, notification)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun startRinging() {
        ringing = true
        // Arm tomorrow's alarm right away.
        AlarmScheduler.scheduleDaily(this, minLeadMs = 60_000)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcAlarm:ringing")
            .apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L)
            }

        maxAlarmVolume()
        player = createPlayer()
        if (player == null) {
            tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            } catch (e: Exception) {
                null
            }
        }
        startVibration()
        handler.post(keepLoud)
    }

    private fun createPlayer(): MediaPlayer? {
        // Custom sound first; if it's been deleted or can't play, fall back to the defaults.
        val candidates = listOfNotNull(
            Prefs.soundUri(this)?.let { Uri.parse(it) },
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.prepare()
                mp.start()
                return mp
            } catch (e: Exception) {
                mp.release()
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        vibrator = v
    }

    private fun maxAlarmVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        player = null
        tone?.release()
        tone = null
        vibrator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        if (Prefs.ringing(this)) {
            // Killed by the system without the tag being scanned — come straight back.
            AlarmScheduler.scheduleOneShot(this, System.currentTimeMillis() + 3_000)
        }
        super.onDestroy()
    }
}

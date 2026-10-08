package dev.nfcalarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private var nfc: NfcAdapter? = null
    private var registering = false

    private lateinit var timeText: TextView
    private lateinit var nextText: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var tagsText: TextView
    private lateinit var addTagButton: Button
    private lateinit var checks: LinearLayout
    private lateinit var soundText: TextView
    private lateinit var previewButton: Button

    private var previewPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nfc = NfcAdapter.getDefaultAdapter(this)

        timeText = findViewById(R.id.time)
        nextText = findViewById(R.id.next)
        enabledSwitch = findViewById(R.id.enabled)
        tagsText = findViewById(R.id.tags)
        addTagButton = findViewById(R.id.add_tag)
        checks = findViewById(R.id.checks)
        soundText = findViewById(R.id.sound_name)
        previewButton = findViewById(R.id.preview_sound)

        findViewById<Button>(R.id.change_sound).setOnClickListener { chooseSound() }
        previewButton.setOnClickListener { if (previewPlayer != null) stopPreview() else startPreview() }

        timeText.setOnClickListener { pickTime() }
        enabledSwitch.setOnClickListener { onToggle(enabledSwitch.isChecked) }
        addTagButton.setOnClickListener { if (registering) stopRegistering() else startRegistering() }
        findViewById<Button>(R.id.clear_tags).setOnClickListener { confirmClearTags() }
        findViewById<Button>(R.id.test).setOnClickListener { testAlarm() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.ringing(this)) {
            if (AlarmService.running) {
                startActivity(Intent(this, RingingActivity::class.java))
            } else {
                // The app was force-stopped mid-alarm (the emergency escape hatch). Accept it.
                Prefs.setRinging(this, false)
            }
        }
        // Re-arm in case a force-stop or clock change cleared it.
        AlarmScheduler.scheduleDaily(this)
        refresh()
        if (registering) enableReader()
    }

    override fun onPause() {
        super.onPause()
        disableReader()
        stopPreview()
    }

    // ---- UI ----

    private fun refresh() {
        timeText.text = formatTime(Prefs.hour(this), Prefs.minute(this))
        enabledSwitch.isChecked = Prefs.enabled(this)
        nextText.text = if (Prefs.enabled(this)) describe(AlarmScheduler.nextTrigger(this)) else "Alarm is off"
        val count = Prefs.tags(this).size
        tagsText.text = when {
            registering -> "Hold your tag against the back of the phone…"
            count == 0 -> "No tags registered yet"
            count == 1 -> "1 tag registered"
            else -> "$count tags registered"
        }
        addTagButton.text = if (registering) "Cancel" else "Register a tag"
        soundText.text = Prefs.soundName(this) ?: "Phone's default alarm"
        buildChecks()
    }

    // ---- Sound ----

    private fun chooseSound() {
        stopPreview()
        AlertDialog.Builder(this)
            .setTitle("Alarm sound")
            .setItems(arrayOf("Alarm sounds on this phone", "A music or audio file…", "Phone's default alarm")) { _, which ->
                when (which) {
                    0 -> pickPhoneSound()
                    1 -> pickAudioFile()
                    else -> {
                        releaseOldFilePermission()
                        Prefs.setSound(this, null, null)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun pickPhoneSound() {
        val current = Prefs.soundUri(this)?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        launchForResult(intent, REQ_PHONE_SOUND)
    }

    private fun pickAudioFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        launchForResult(intent, REQ_AUDIO_FILE)
    }

    private fun launchForResult(intent: Intent, requestCode: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            toast("No app on this phone can pick sounds that way")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PHONE_SOUND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                releaseOldFilePermission()
                if (uri == null || RingtoneManager.isDefault(uri)) {
                    Prefs.setSound(this, null, null)
                } else {
                    val title = try {
                        RingtoneManager.getRingtone(this, uri)?.getTitle(this)
                    } catch (e: Exception) {
                        null
                    }
                    Prefs.setSound(this, uri.toString(), title ?: "Custom alarm sound")
                }
            }
            REQ_AUDIO_FILE -> {
                val uri = data.data ?: return
                try {
                    // Keep read access after a reboot so the alarm can still play it.
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Some file providers don't offer lasting access; we test playback below anyway.
                }
                if (!canPlay(uri)) {
                    toast("That file can't be played. Try a different one (MP3, M4A, OGG and WAV work).")
                    return
                }
                releaseOldFilePermission(keep = uri)
                Prefs.setSound(this, uri.toString(), displayName(uri) ?: "Audio file")
            }
        }
        refresh()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (e: Exception) {
        null
    }

    private fun canPlay(uri: Uri): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setDataSource(this, uri)
            mp.prepare()
            true
        } catch (e: Exception) {
            false
        } finally {
            mp.release()
        }
    }

    /** Drop access to a previously chosen file we no longer need. */
    private fun releaseOldFilePermission(keep: Uri? = null) {
        val old = Prefs.soundUri(this) ?: return
        if (old == keep?.toString()) return
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // It was a built-in sound, or access was never persisted.
        }
    }

    /** Plays up to 15 seconds at the phone's current alarm volume. */
    private fun startPreview() {
        val uri = Prefs.soundUri(this)?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            toast("Couldn't play that sound. The alarm will fall back to the default.")
            return
        }
        previewPlayer = mp
        previewButton.text = "Stop"
        handler.postDelayed({ stopPreview() }, 15_000)
    }

    private fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        previewPlayer?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        previewPlayer = null
        if (::previewButton.isInitialized) previewButton.text = "Preview"
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun describe(triggerMs: Long): String {
        val diff = triggerMs - System.currentTimeMillis()
        val totalMin = (diff + 59_999) / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        val day = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(triggerMs))
        val time = DateFormat.getTimeFormat(this).format(Date(triggerMs))
        return "Next: $day $time  ·  in ${h}h ${m}m"
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, hour, minute ->
            Prefs.setTime(this, hour, minute)
            if (Prefs.tags(this).isNotEmpty()) Prefs.setEnabled(this, true)
            if (!AlarmScheduler.scheduleDaily(this)) toast("Allow exact alarms first (see Setup)")
            refresh()
        }, Prefs.hour(this), Prefs.minute(this), DateFormat.is24HourFormat(this)).show()
    }

    private fun onToggle(on: Boolean) {
        if (on && Prefs.tags(this).isEmpty()) {
            enabledSwitch.isChecked = false
            toast("Register an NFC tag first, otherwise there'd be no way to turn the alarm off.")
            return
        }
        Prefs.setEnabled(this, on)
        if (!AlarmScheduler.scheduleDaily(this)) toast("Allow exact alarms first (see Setup)")
        refresh()
    }

    private fun testAlarm() {
        if (Prefs.tags(this).isEmpty()) {
            toast("Register a tag first")
            return
        }
        if (AlarmScheduler.scheduleOneShot(this, System.currentTimeMillis() + 10_000)) {
            toast("Test alarm in 10 seconds. Lock the phone to see the full experience.")
        } else {
            toast("Allow exact alarms first (see Setup)")
        }
    }

    private fun confirmClearTags() {
        AlertDialog.Builder(this)
            .setMessage("Remove all registered tags? The alarm will also be turned off.")
            .setPositiveButton("Remove") { _, _ ->
                Prefs.clearTags(this)
                Prefs.setEnabled(this, false)
                AlarmScheduler.scheduleDaily(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Tag registration ----

    private fun startRegistering() {
        val adapter = nfc
        if (adapter == null) {
            toast("This phone has no NFC reader")
            return
        }
        if (!adapter.isEnabled) {
            toast("Turn on NFC first")
            openSettings(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }
        registering = true
        enableReader()
        refresh()
    }

    private fun stopRegistering() {
        registering = false
        disableReader()
        refresh()
    }

    private fun enableReader() {
        val adapter = nfc ?: return
        if (!adapter.isEnabled) return
        adapter.enableReaderMode(this, NfcAdapter.ReaderCallback { tag -> onTag(tag) }, RingingActivity.READER_FLAGS, null)
    }

    private fun disableReader() {
        try { nfc?.disableReaderMode(this) } catch (e: Exception) { }
    }

    private fun onTag(tag: Tag) {
        val id = tag.id.toHex()
        runOnUiThread {
            if (!registering) return@runOnUiThread
            if (id.isEmpty()) {
                toast("Couldn't read an ID from that tag. Try a different one.")
                return@runOnUiThread
            }
            val isNew = id !in Prefs.tags(this)
            Prefs.addTag(this, id)
            registering = false
            disableReader()
            toast(if (isNew) "Tag registered" else "That tag is already registered")
            refresh()
        }
    }

    // ---- Setup checklist ----

    private fun buildChecks() {
        checks.removeAllViews()
        val pkg = Uri.parse("package:$packageName")

        val adapter = nfc
        if (adapter == null) {
            addCheck("This phone has no NFC, so the alarm could never be dismissed. Don't enable it.", null, null)
        } else if (!adapter.isEnabled) {
            addCheck("NFC is turned off", "Turn on") { openSettings(Intent(Settings.ACTION_NFC_SETTINGS)) }
        }

        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            addCheck("Notifications are blocked, so the alarm screen can't appear", "Allow") {
                openSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }

        if (!Notifications.canUseFullScreen(this) && Build.VERSION.SDK_INT >= 34) {
            addCheck("Full-screen alarm permission is off", "Allow") {
                openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
        }

        if (!AlarmScheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= 31) {
            addCheck("Exact alarms aren't allowed", "Allow") {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
        }

        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            addCheck("Battery optimization could delay or kill the alarm", "Exempt") {
                openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
        }

        if (checks.childCount == 0) addCheck("✓ All set", null, null)
    }

    private fun addCheck(message: String, action: String?, onClick: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(
            TextView(this).apply {
                text = message
                textSize = 15f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (action != null && onClick != null) {
            row.addView(Button(this).apply {
                text = action
                setOnClickListener { onClick() }
            })
        }
        checks.addView(row)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open that setting on this phone")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PHONE_SOUND = 10
        private const val REQ_AUDIO_FILE = 11
    }
}

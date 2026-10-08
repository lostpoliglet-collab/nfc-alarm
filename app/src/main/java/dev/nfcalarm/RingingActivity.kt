package dev.nfcalarm

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.util.Date

/** Full-screen alarm screen. Shows over the lock screen; only a registered tag dismisses it. */
class RingingActivity : Activity(), NfcAdapter.ReaderCallback {

    private var nfc: NfcAdapter? = null
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var nfcButton: Button
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(this@RingingActivity).format(Date())
            handler.postDelayed(this, 1_000)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_ringing)

        clock = findViewById(R.id.clock)
        status = findViewById(R.id.status)
        nfcButton = findViewById(R.id.nfc_settings)
        nfcButton.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (e: Exception) { }
        }
        nfc = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.ringing(this)) {
            finish()
            return
        }
        handler.post(tick)
        val adapter = nfc
        when {
            adapter == null -> {
                status.text = "This phone has no NFC reader."
                nfcButton.visibility = View.GONE
            }
            !adapter.isEnabled -> {
                status.text = "NFC is turned off. Turn it on, then scan your tag."
                nfcButton.visibility = View.VISIBLE
            }
            else -> {
                status.text = "Scan your NFC tag to turn off the alarm"
                nfcButton.visibility = View.GONE
                adapter.enableReaderMode(this, this, READER_FLAGS, null)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        try { nfc?.disableReaderMode(this) } catch (e: Exception) { }
    }

    override fun onTagDiscovered(tag: Tag) {
        val id = tag.id.toHex()
        runOnUiThread {
            if (id.isNotEmpty() && id in Prefs.tags(this)) {
                AlarmService.stop(this)
                finish()
            } else {
                status.text = "That's not your alarm tag."
            }
        }
    }

    // Back does nothing while ringing.
    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        status.text = "Scan your NFC tag to turn off the alarm"
    }

    // Swallow volume keys while this screen is up (the service also re-maxes volume).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> super.onKeyDown(keyCode, event)
    }

    companion object {
        val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}

package dev.nfcalarm

import android.content.Context
import android.content.SharedPreferences

/** All saved settings: alarm time, on/off, registered tag IDs, and whether it's ringing. */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("nfc_alarm", Context.MODE_PRIVATE)

    fun hour(ctx: Context): Int = sp(ctx).getInt("hour", 7)
    fun minute(ctx: Context): Int = sp(ctx).getInt("minute", 0)
    fun setTime(ctx: Context, hour: Int, minute: Int) {
        sp(ctx).edit().putInt("hour", hour).putInt("minute", minute).apply()
    }

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("enabled", on).apply()
    }

    fun tags(ctx: Context): Set<String> = sp(ctx).getStringSet("tags", null)?.toSet() ?: emptySet()
    fun addTag(ctx: Context, id: String) {
        sp(ctx).edit().putStringSet("tags", tags(ctx) + id).apply()
    }
    fun clearTags(ctx: Context) {
        sp(ctx).edit().remove("tags").apply()
    }

    /** Custom sound (content URI as a string), or null for the phone's default alarm sound. */
    fun soundUri(ctx: Context): String? = sp(ctx).getString("sound_uri", null)
    fun soundName(ctx: Context): String? = sp(ctx).getString("sound_name", null)
    fun setSound(ctx: Context, uri: String?, name: String?) {
        sp(ctx).edit().putString("sound_uri", uri).putString("sound_name", name).apply()
    }

    fun ringing(ctx: Context): Boolean = sp(ctx).getBoolean("ringing", false)
    fun setRinging(ctx: Context, on: Boolean) {
        // commit() (not apply) so the state is on disk before anything else happens
        sp(ctx).edit().putBoolean("ringing", on).commit()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

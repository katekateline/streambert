package com.truelockmc.streambert

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin

// Opens the native Media3 player and exposes saved playback progress.
// `open` resolves only after PlayerActivity closes so React can sync progress.
@CapacitorPlugin(name = "Player")
class PlayerPlugin : Plugin() {
    @PluginMethod
    fun open(call: PluginCall) {
        val url = call.getString("url").orEmpty()
        val uri = Uri.parse(url)
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrEmpty()) {
            call.reject("Invalid video URL")
            return
        }
        val subtitle = call.getString("subtitle").orEmpty()
        if (subtitle.isNotEmpty()) {
            val subtitleUri = Uri.parse(subtitle)
            if ((subtitleUri.scheme != "http" && subtitleUri.scheme != "https") ||
                subtitleUri.host.isNullOrEmpty()
            ) {
                call.reject("Invalid subtitle URL")
                return
            }
        }

        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_REFERER, call.getString("referer"))
            putExtra(PlayerActivity.EXTRA_USER_AGENT, call.getString("userAgent"))
            putExtra(PlayerActivity.EXTRA_SUBTITLE, subtitle)
            putExtra(
                PlayerActivity.EXTRA_SUBTITLE_LANGUAGE,
                call.getString("subtitleLanguage") ?: "en",
            )
            putExtra(
                PlayerActivity.EXTRA_SUBTITLE_LABEL,
                call.getString("subtitleLabel") ?: "English",
            )
            putExtra(
                PlayerActivity.EXTRA_IS_MP4,
                call.getBoolean("isDirectMp4", false) == true ||
                    url.contains(".mp4", ignoreCase = true),
            )
            putExtra(PlayerActivity.EXTRA_MEDIA_ID, call.getString("mediaId"))
            putExtra(PlayerActivity.EXTRA_TITLE, call.getString("title"))
            putExtra(
                PlayerActivity.EXTRA_START_POSITION,
                (call.getDouble("startPosition") ?: 0.0).toLong().coerceAtLeast(0L),
            )
        }
        try {
            startActivityForResult(call, intent, "onPlayerClosed")
        } catch (e: Exception) {
            call.reject(e.message ?: "Unable to start player")
        }
    }

    // Keeps the JS call pending while PlayerActivity is open; PlayerActivity
    // saves progress on pause/stop/destroy, so JS reads getProgress right after.
    @ActivityCallback
    fun onPlayerClosed(call: PluginCall?, result: ActivityResult) {
        call?.resolve()
    }

    @PluginMethod
    fun getProgress(call: PluginCall) {
        val id = call.getString("id").orEmpty()
        if (id.isEmpty()) {
            call.reject("Missing media id")
            return
        }
        val prefs = context.getSharedPreferences(PlayerActivity.PREFS, Context.MODE_PRIVATE)
        call.resolve(
            JSObject()
                .put("position", prefs.getLong("pos_$id", 0L))
                .put("duration", prefs.getLong("dur_$id", 0L))
                .put("completed", prefs.getBoolean("done_$id", false)),
        )
    }
}

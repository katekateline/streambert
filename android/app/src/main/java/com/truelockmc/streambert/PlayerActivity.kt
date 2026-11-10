package com.truelockmc.streambert

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

// Native Media3 player. Receives a resolved stream URL + headers from PlayerPlugin.
// The same DataSource.Factory feeds the HLS playlist, variants, segments, keys and
// side-loaded subtitles, so Referer/User-Agent are applied to every request.
class PlayerActivity : AppCompatActivity() {
    companion object {
        const val PREFS = "streambert_player"
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "userAgent"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_SUBTITLE_LANGUAGE = "subtitleLanguage"
        const val EXTRA_SUBTITLE_LABEL = "subtitleLabel"
        const val EXTRA_IS_MP4 = "isDirectMp4"
        const val EXTRA_MEDIA_ID = "mediaId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_POSITION = "startPosition"
        private const val TAG = "PlayerActivity"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private lateinit var playerView: PlayerView
    private lateinit var errorText: TextView
    private lateinit var player: ExoPlayer
    private var mediaId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        playerView = findViewById(R.id.player_view)
        errorText = findViewById(R.id.error_text)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isEmpty()) {
            finish()
            return
        }
        mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
        val subtitleLanguage = intent.getStringExtra(EXTRA_SUBTITLE_LANGUAGE) ?: "en"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val isMp4 = intent.getBooleanExtra(EXTRA_IS_MP4, false)
        val startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        // Generic MP4 hosts reject a spoofed browser UA; HLS providers expect one.
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            ?: DEFAULT_USER_AGENT.takeUnless { isMp4 }

        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }

        val headers = mutableMapOf<String, String>()
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        if (userAgent != null) headers["User-Agent"] = userAgent
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true)
            .apply { if (userAgent != null) setUserAgent(userAgent) }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        playerView.player = player

        val itemBuilder = MediaItem.Builder().setUri(url)
        if (!title.isNullOrEmpty()) {
            itemBuilder.setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        }
        if (isMp4) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_MP4)
        } else if (url.contains(".m3u8", ignoreCase = true)) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        if (!subtitle.isNullOrEmpty()) {
            itemBuilder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(subtitleLanguage)
                        .setLabel(
                            intent.getStringExtra(EXTRA_SUBTITLE_LABEL) ?: "English",
                        )
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "state=$playbackState")
                if (playbackState == Player.STATE_ENDED) saveProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "playing=$isPlaying")
            }

            override fun onTracksChanged(tracks: Tracks) {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                Log.d(TAG, "textGroups=${textGroups.size} selected=${textGroups.any { it.isSelected }}")
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                Log.d(TAG, "cues=${cueGroup.cues.size}")
            }

            override fun onPlayerError(error: PlaybackException) {
                // No URL in the log or UI: playback URLs can contain access tokens.
                val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                Log.e(TAG, "playbackError=${error.errorCodeName}" + (httpCode?.let { " http=$it" } ?: ""))
                showError("Playback error: ${error.errorCodeName}")
            }
        })

        if (!subtitle.isNullOrEmpty()) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(subtitleLanguage)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }
        player.setMediaItem(itemBuilder.build(), startPosition.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun saveProgress() {
        val id = mediaId ?: return
        if (!::player.isInitialized) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val completed = duration > 0 && position >= (duration * 0.95).toLong()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("pos_$id", position)
            .putLong("dur_$id", duration)
            .putBoolean("done_$id", completed)
            .apply()
        Log.d(TAG, "savedProgress position=$position duration=$duration completed=$completed")
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) {
            player.pause()
            saveProgress()
        }
    }

    override fun onStop() {
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        saveProgress()
        if (::player.isInitialized) {
            playerView.player = null
            player.release()
        }
        super.onDestroy()
    }
}

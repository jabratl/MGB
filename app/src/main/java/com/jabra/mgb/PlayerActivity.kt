package com.jabra.mgb

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * S36: the built-in player as a real Activity (the Dialog + fake rotation of S28-S35 drew a stretched, wrongly turned
 * picture on a SurfaceView). Like MX Player: the Activity itself turns to landscape, the browser Activity is never touched.
 * Media3 ExoPlayer: mkv / mp4 / webm / ts / mp3 ..., subtitles (srt, vtt, ass/ssa, ttml: embedded tracks, a file next to the
 * video, or a file you pick), audio track choice, speed 0.25x - 6x, Fit / Fill / Stretch, swipe brightness / volume / seek,
 * double tap -+10 s, hold = 2x, lock, resume position, Open in another app.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MGB.player"
        const val EXTRA_TITLE = "title"
        private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

        fun start(ctx: Context, uri: Uri, title: String) {
            val i = Intent(ctx, PlayerActivity::class.java).setData(uri).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        private fun subMime(ext: String): String? = when (ext) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var uri: Uri
    private var title = ""
    private var player: ExoPlayer? = null

    private lateinit var rootView: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tTotal: TextView
    private lateinit var playBtn: ImageView
    private lateinit var speedBtn: TextView
    private lateinit var fitBtn: TextView
    private lateinit var ccBtn: TextView
    private lateinit var flash: TextView
    private lateinit var unlockBtn: ImageView
    private lateinit var audio: AudioManager
    private lateinit var prefs: android.content.SharedPreferences

    private val subs = ArrayList<MediaItem.SubtitleConfiguration>()
    private var wantAutoSub = false
    private var autoSubDone = false
    private var shown = true
    private var locked = false
    private var dragging = false
    private var userRot = false
    private var boost = false
    private var speedBeforeBoost = 1f
    private var fitMode = 0 // 0 fit, 1 fill (crop), 2 stretch
    private var ended = false
    private var swipe = 0 // 0 none, 1 vertical, 2 horizontal
    private var swipeLeft = false
    private var seekTarget = -1L
    private var volAcc = 0f
    private var dxAcc = 0f

    private val openSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) addSubtitleFile(u)
    }

    private fun dp(v: Int) = Util.dp(this, v)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = intent?.data
        if (u == null) { finish(); return }
        uri = u
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("mgb_resume", Context.MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideBars()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER // the player may rotate with the phone; the rotate button decides after that

        installBackHandler()
        buildUi()
        startPlayer()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig) // declared in configChanges: no recreate, the player keeps playing
        hideBars()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        saveResume()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        saveResume()
        try { playerView.player = null } catch (_: Exception) { }
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) { Util.toast(this@PlayerActivity, "Locked: tap the screen, then the lock button"); return }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    // ---------- player ----------

    private fun startPlayer() {
        findSidecars()
        wantAutoSub = subs.isNotEmpty()
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { refreshPlayIcon() }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { ended = true; showControls(true) }
                else if (state == Player.STATE_READY) ended = false
                refreshPlayIcon()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!userRot && videoSize.width > 0 && videoSize.height > 0) {
                    userRot = true // only the first time: after that the rotate button / the phone decide
                    requestedOrientation = if (videoSize.width >= videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_USER
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                autoPickSubtitle(tracks)
                updateCcLabel()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "play error", error)
                try {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Cannot play this file here")
                        .setMessage("Try another player app.")
                        .setPositiveButton("Open in another app") { _, _ -> openExternal() }
                        .setNegativeButton("Close") { _, _ -> finish() }
                        .show()
                } catch (_: Exception) { }
            }
        })
        setItem(resumePos())
        p.playWhenReady = true
        p.prepare()
        main.post(ticker)
        styleSubtitles()
        showControls(true)
    }

    private fun setItem(startMs: Long) {
        val p = player ?: return
        val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(ArrayList(subs)).build()
        if (startMs > 0) p.setMediaItem(item, startMs) else p.setMediaItem(item)
    }

    private fun resumePos(): Long {
        val v = prefs.getLong(uri.toString(), 0L)
        if (v > 5000) { main.postDelayed({ flash("Resumed " + fmt(v)) }, 600); return v }
        return 0L
    }

    private fun saveResume() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        val e = prefs.edit()
        if (dur > 0 && pos > 5000 && pos < dur - 5000) e.putLong(uri.toString(), pos) else e.remove(uri.toString())
        e.apply()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val dur = p.duration.let { if (it > 0) it else 0L }
                val pos = if (seekTarget >= 0) seekTarget else p.currentPosition
                if (!dragging) {
                    seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
                    tCur.text = fmt(pos)
                }
                tTotal.text = fmt(dur)
            }
            main.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%02d:%02d", m, ss)
    }

    // ---------- subtitles ----------

    private fun findSidecars() {
        try {
            if (Build.VERSION.SDK_INT < 29 || uri.scheme != "content" || uri.authority != "media") return
            var name = ""
            var rel = ""
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: ""; rel = it.getString(1) ?: "" }
            }
            if (name.isEmpty() || rel.isEmpty()) return
            val stem = name.substringBeforeLast('.')
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", arrayOf(rel), null)?.use { c ->
                while (c.moveToNext() && subs.size < 8) {
                    val n = c.getString(1) ?: continue
                    if (!n.startsWith(stem)) continue
                    val ext = n.substringAfterLast('.', "").lowercase()
                    if (ext !in SUB_EXT) continue
                    val mime = subMime(ext) ?: continue
                    val lang = n.removePrefix(stem).removeSuffix(".$ext").trim('.', ' ', '_', '-')
                    subs.add(subConfig(ContentUris.withAppendedId(base, c.getLong(0)), mime, lang, n))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sidecar", e)
        }
    }

    private fun subConfig(u: Uri, mime: String, lang: String, label: String): MediaItem.SubtitleConfiguration {
        val b = MediaItem.SubtitleConfiguration.Builder(u).setMimeType(mime).setLabel(if (lang.isEmpty()) label else lang)
        if (lang.length in 2..3) b.setLanguage(lang)
        if (subs.isEmpty()) b.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        return b.build()
    }

    private fun addSubtitleFile(u: Uri) {
        var name = ""
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: ""
            }
        } catch (_: Exception) { }
        if (name.isEmpty()) name = u.lastPathSegment ?: "subtitle"
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = subMime(ext)
        if (mime == null) { Util.toast(this, "Use .srt, .vtt, .ass, .ssa or .ttml"); return }
        val pos = player?.currentPosition ?: 0L
        subs.add(0, subConfig(u, mime, "", name))
        wantAutoSub = true
        autoSubDone = false
        setItem(pos)
        player?.prepare()
        player?.playWhenReady = true
        Util.toast(this, "Subtitle added: $name")
    }

    private fun textGroups(t: Tracks) = t.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    private fun trackName(f: androidx.media3.common.Format, n: Int): String {
        val l = f.label ?: f.language?.let { java.util.Locale(it).displayLanguage }
        return if (l.isNullOrBlank()) "Track $n" else l
    }

    /** A file next to the video, or one the user just picked, is switched on by itself (once). */
    private fun autoPickSubtitle(tracks: Tracks) {
        val p = player ?: return
        if (!wantAutoSub || autoSubDone) return
        val groups = textGroups(tracks)
        if (groups.isEmpty()) return
        autoSubDone = true
        if (groups.any { it.isSelected }) return
        val g = groups[0]
        if (g.length == 0) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    private fun styleSubtitles() {
        val sv = playerView.subtitleView ?: return
        sv.setApplyEmbeddedStyles(true)
        sv.setStyle(CaptionStyleCompat(Color.WHITE, 0x55000000, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        sv.setFractionalTextSize(0.0533f * subScale())
        sv.setBottomPaddingFraction(0.1f)
    }

    private fun subScale(): Float = when (getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getInt("sub_size", 1)) { 0 -> 0.8f; 2 -> 1.35f; else -> 1f }

    private fun updateCcLabel() {
        val p = player ?: return
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val any = textGroups(p.currentTracks).isNotEmpty()
        val on = any && !off && textGroups(p.currentTracks).any { it.isSelected }
        ccBtn.text = if (on) "CC on" else "CC"
        ccBtn.alpha = if (any) 1f else 0.6f
    }

    private fun showSubtitleDialog() {
        val p = player ?: return
        val groups = textGroups(p.currentTracks)
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || groups.none { it.isSelected }
        names.add((if (off) "● " else "○ ") + "Off")
        actions.add {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            updateCcLabel()
        }
        var n = 0
        for (g in groups) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            val label = trackName(g.getTrackFormat(i), n)
            val sel = g.isTrackSelected(i) && !off
            names.add((if (sel) "● " else "○ ") + label)
            val gg = g
            val ii = i
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(TrackSelectionOverride(gg.mediaTrackGroup, ii))
                    .build()
                updateCcLabel()
            }
        }
        names.add("Add subtitle file...")
        actions.add { openSub.launch(arrayOf("*/*")) }
        val sizeNames = arrayOf("Small", "Medium", "Large")
        val cur = getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getInt("sub_size", 1).coerceIn(0, 2)
        names.add("Text size: " + sizeNames[cur] + " (tap to change)")
        actions.add {
            getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putInt("sub_size", (cur + 1) % 3).apply()
            styleSubtitles()
        }
        if (n == 0) Util.toast(this, "No subtitle in this video: add a file")
        AlertDialog.Builder(this).setTitle("Subtitles")
            .setItems(names.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null).show()
    }

    private fun showAudioDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        var n = 0
        for (g in groups) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            val f = g.getTrackFormat(i)
            val extra = if (f.channelCount > 0) " (" + f.channelCount + "ch)" else ""
            names.add((if (g.isTrackSelected(i)) "● " else "○ ") + trackName(f, n) + extra)
            val gg = g
            val ii = i
            acts.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setOverrideForType(TrackSelectionOverride(gg.mediaTrackGroup, ii))
                    .build()
            }
        }
        if (names.size < 2) { Util.toast(this, if (names.isEmpty()) "No audio track" else "Only one audio track"); return }
        AlertDialog.Builder(this).setTitle("Audio track")
            .setItems(names.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Close", null).show()
    }

    // ---------- actions ----------

    private fun openExternal() {
        try {
            val mime = contentResolver.getType(uri) ?: "video/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Open with"))
        } catch (e: Exception) {
            Util.toast(this, "No app can open this file")
        }
    }

    private fun toggleRotate() {
        userRot = true
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun togglePlay() {
        val p = player ?: return
        if (ended || p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.play(); ended = false; return }
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun refreshPlayIcon() {
        if (!this::playBtn.isInitialized) return
        playBtn.setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val dur = p.duration
        var t = p.currentPosition + ms
        if (t < 0) t = 0
        if (dur > 0 && t > dur) t = dur
        p.seekTo(t)
        flash((if (ms > 0) "+" else "-") + abs(ms / 1000) + " s   " + fmt(t))
    }

    private fun setSpeed(s: Float) {
        player?.playbackParameters = PlaybackParameters(s)
        speedBtn.text = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
    }

    private fun showSpeedDialog() {
        val vals = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 6f)
        val cur = player?.playbackParameters?.speed ?: 1f
        val names = vals.map { (if (abs(it - cur) < 0.01f) "● " else "○ ") + (if (it == it.toInt().toFloat()) "${it.toInt()}x" else "${it}x") }
        AlertDialog.Builder(this).setTitle("Speed")
            .setItems(names.toTypedArray()) { _, i -> setSpeed(vals[i]) }
            .setNegativeButton("Close", null).show()
    }

    private fun cycleFit() {
        fitMode = (fitMode + 1) % 3
        playerView.resizeMode = when (fitMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        fitBtn.text = when (fitMode) { 1 -> "Fill"; 2 -> "Stretch"; else -> "Fit" }
    }

    private fun showMore() {
        val items = arrayOf("Open in another app", "Audio track", "Subtitles")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) { 0 -> openExternal(); 1 -> showAudioDialog(); else -> showSubtitleDialog() }
        }.setNegativeButton("Close", null).show()
    }

    // ---------- controls visibility ----------

    private val hider = Runnable { if (player?.isPlaying == true && !dragging) showControls(false) }

    private fun showControls(on: Boolean) {
        shown = on && !locked
        controls.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        main.removeCallbacks(hider)
        if (shown) main.postDelayed(hider, 3500)
    }

    private fun poke() { if (shown) showControls(true) }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            showControls(false)
            unlockBtn.visibility = View.VISIBLE
            main.postDelayed({ if (locked) unlockBtn.visibility = View.INVISIBLE }, 2500)
        } else {
            unlockBtn.visibility = View.GONE
            showControls(true)
        }
    }

    private fun flash(msg: String) {
        flash.text = msg
        flash.visibility = View.VISIBLE
        main.removeCallbacks(flashOff)
        main.postDelayed(flashOff, 900)
    }

    private val flashOff = Runnable { flash.visibility = View.GONE }

    // ---------- UI ----------

    private fun icon(res: Int, size: Int = 44, on: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { on(); poke() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun pill(label: String, on: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = round(0x2EFFFFFF, 20)
        minimumWidth = dp(56)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { on(); poke() }
    }

    private fun buildUi() {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        rootView = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
        }
        rootView.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))

        // touch layer: tap, double tap, hold, swipes
        val touch = View(this)
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { swipe = 0; volAcc = 0f; dxAcc = 0f; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    unlockBtn.visibility = View.VISIBLE
                    main.postDelayed({ if (locked) unlockBtn.visibility = View.INVISIBLE }, 2500)
                } else showControls(!shown)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = touch.width.toFloat()
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2f / 3f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked || swipe != 0) return
                val p = player ?: return
                if (!p.isPlaying) return
                boost = true
                speedBeforeBoost = p.playbackParameters.speed
                p.playbackParameters = PlaybackParameters(2f)
                flash("2x")
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || boost) return false
                val w = touch.width.toFloat()
                val h = touch.height.toFloat()
                if (swipe == 0) {
                    if (abs(e2.x - e1.x) < dp(14) && abs(e2.y - e1.y) < dp(14)) return false
                    swipe = if (abs(e2.x - e1.x) > abs(e2.y - e1.y)) 2 else 1
                    swipeLeft = e1.x < w / 2f
                    seekTarget = -1
                }
                if (swipe == 2) {
                    val p = player ?: return true
                    val dur = p.duration
                    if (dur <= 0) return true
                    dxAcc -= dx
                    seekTarget = (p.currentPosition + dxAcc / w * 90_000f).toLong().coerceIn(0L, dur)
                    flash(fmt(seekTarget) + " / " + fmt(dur))
                } else if (swipeLeft) {
                    val lp = window.attributes
                    var br = lp.screenBrightness
                    if (br < 0f) br = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
                    br = (br + dy / h * 1.3f).coerceIn(0.02f, 1f)
                    lp.screenBrightness = br
                    window.attributes = lp
                    flash("Brightness " + (br * 100).toInt() + "%")
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    volAcc += dy / h * max * 1.4f
                    val step = volAcc.toInt()
                    if (step != 0) {
                        volAcc -= step
                        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    }
                    flash("Volume " + (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max) + "%")
                }
                return true
            }
        })
        touch.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (boost) {
                    boost = false
                    player?.playbackParameters = PlaybackParameters(speedBeforeBoost)
                    flash("1x")
                }
                if (swipe == 2 && seekTarget >= 0) { player?.seekTo(seekTarget) }
                seekTarget = -1
                swipe = 0
            }
            true
        }
        rootView.addView(touch, FrameLayout.LayoutParams(MATCH, MATCH))

        // controls: top bar + bottom block
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB0000000.toInt(), 0x00000000))
        }
        top.addView(icon(R.drawable.ic_back, 48) { finish() })
        top.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        top.addView(icon(R.drawable.ic_screen, 48) { toggleRotate() })
        top.addView(icon(R.drawable.ic_more, 48) { showMore() })
        controls.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        controls.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB0000000.toInt(), 0x00000000))
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tCur = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = "00:00" }
        tTotal = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = "00:00" }
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(getColor(R.color.mg_primary))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.mg_primary))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) tCur.text = fmt((player?.duration?.coerceAtLeast(0L) ?: 0L) * prog / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true; main.removeCallbacks(hider) }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                    val d = player?.duration ?: 0L
                    if (d > 0) player?.seekTo(d * (sb?.progress ?: 0) / 1000)
                    poke()
                }
            })
        }
        seekRow.addView(tCur)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        seekRow.addView(tTotal)
        bottom.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(icon(R.drawable.ic_lock) { setLocked(true) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(pill("-10") { seekBy(-10_000) }, LinearLayout.LayoutParams(WRAP, dp(40)))
        playBtn = icon(R.drawable.ic_pause, 64) { togglePlay() }
        playBtn.setColorFilter(getColor(R.color.mg_on_primary))
        playBtn.background = round(getColor(R.color.mg_primary), 32)
        row.addView(playBtn, LinearLayout.LayoutParams(dp(64), dp(64)).apply { setMargins(dp(14), 0, dp(14), 0) })
        row.addView(pill("+10") { seekBy(10_000) }, LinearLayout.LayoutParams(WRAP, dp(40)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(44), dp(44)))
        bottom.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        speedBtn = pill("1x") { showSpeedDialog() }
        ccBtn = pill("CC") { showSubtitleDialog() }
        val audioBtn = pill("Audio") { showAudioDialog() }
        fitBtn = pill("Fit") { cycleFit() }
        val pl = LinearLayout.LayoutParams(WRAP, dp(36)).apply { setMargins(dp(5), dp(10), dp(5), 0) }
        pills.addView(speedBtn, pl)
        pills.addView(ccBtn, pl)
        pills.addView(audioBtn, pl)
        pills.addView(fitBtn, pl)
        bottom.addView(pills, LinearLayout.LayoutParams(MATCH, WRAP))
        controls.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        rootView.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        flash = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = round(0x99000000.toInt(), 14)
            visibility = View.GONE
        }
        rootView.addView(flash, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        unlockBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(getColor(R.color.mg_on_primary))
            scaleType = ImageView.ScaleType.CENTER
            background = round(getColor(R.color.mg_primary), 28)
            visibility = View.GONE
            setOnClickListener { setLocked(false) }
        }
        rootView.addView(unlockBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(dp(24), 0, 0, 0) })

        // keep the controls out of a display cutout / camera hole
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            controls.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }
        setContentView(rootView)
    }
}

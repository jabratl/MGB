package com.jabra.mgb

import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
 * S38: new look (centre buttons, clean seek bar, icon toolbar, dark bottom sheets, level bar for brightness / volume,
 * buffering spinner, fades), repeat, sleep timer, video info.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MGB.player"
        /** S45: the open player (PlayerService buttons call bgToggle / bgClose on it). */
        @Volatile var live: PlayerActivity? = null
        const val EXTRA_TITLE = "title"
        const val EXTRA_REF = "ref"
        const val EXTRA_UA = "ua"
        private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

        fun start(ctx: Context, uri: Uri, title: String) {
            val i = Intent(ctx, PlayerActivity::class.java).setData(uri).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        /** S39: play a web address (mp4 / mkv / HLS .m3u8 / DASH .mpd ...) with the page's Referer + User-Agent + cookies. */
        fun startStream(ctx: Context, url: String, title: String, referer: String?, ua: String?) {
            val i = Intent(ctx, PlayerActivity::class.java).setData(Uri.parse(url)).putExtra(EXTRA_TITLE, title)
            if (!referer.isNullOrBlank()) i.putExtra(EXTRA_REF, referer)
            if (!ua.isNullOrBlank()) i.putExtra(EXTRA_UA, ua)
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
    private var refUrl = "" // S39: web streams
    private var uaStr = ""
    internal var player: ExoPlayer? = null

    private lateinit var rootView: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tTotal: TextView
    private lateinit var playBtn: ImageView
    private lateinit var speedVal: TextView
    private lateinit var fitLabel: TextView
    private lateinit var ccIcon: ImageView
    private lateinit var infoTv: TextView
    private lateinit var hud: LinearLayout
    private lateinit var hudText: TextView
    private lateinit var hudBar: ProgressBar
    private lateinit var tapL: TextView
    private lateinit var tapR: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var flash: TextView
    private lateinit var unlockBtn: ImageView
    private lateinit var titleTv: TextView
    private lateinit var prevBtn: ImageView
    private lateinit var nextBtn: ImageView
    private lateinit var resumeBar: TextView
    internal lateinit var panel: PlayerPanel // P1: side panel (3-dot menu)
    private lateinit var audio: AudioManager
    private lateinit var prefs: android.content.SharedPreferences

    private val subs = ArrayList<MediaItem.SubtitleConfiguration>()
    private val sibs = ArrayList<Pair<Uri, String>>() // S37: other media files of the same folder (previous / next episode)
    private var sibIdx = -1
    private var zoom = 1f
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
    internal var sleepAt = 0L // S38: sleep timer end (ms), 0 = off, -1 = end of this video (P4)
    internal var sleepPick = 0 // P4: minutes the user picked (0 = none / custom add), for the selected row

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
        refUrl = intent.getStringExtra(EXTRA_REF) ?: ""
        uaStr = intent.getStringExtra(EXTRA_UA) ?: ""
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

        live = this // S45
        installBackHandler()
        buildUi()
        startPlayer()
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED) // S42
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // S39: a new link from the browser while the player is open (e.g. in PiP): restart with the new address
        val n = intent?.data ?: return
        if (n != uri) { finish(); startActivity(intent) }
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

    // S45: background audio: keep playing when the screen turns off / the user leaves (not after PiP, not when finishing)
    private var pipWas = false

    override fun onStart() {
        super.onStart()
        pipWas = false
    }

    override fun onStop() {
        super.onStop()
        if (this::panel.isInitialized) panel.close(true) // P1
        val keep = PlayerService.on(this) && player?.isPlaying == true && !pipWas && !isFinishing
        if (!keep) player?.pause()
        saveResume()
    }

    private var bgStarted = false

    private fun bgNote(playing: Boolean) {
        if (!PlayerService.on(this)) return
        if (!playing && !bgStarted) return // never started: nothing to update
        bgStarted = true
        PlayerService.update(this, title, playing)
    }

    fun bgToggle() { runOnUiThread { togglePlay() } }

    fun bgClose() { runOnUiThread { player?.pause(); finish() } }

    override fun onDestroy() {
        if (live === this) live = null // S45
        PlayerService.stop(this)
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) { } // S42
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
                if (this@PlayerActivity::panel.isInitialized && panel.isOpen) { panel.pop(); return } // P1
                if (locked) { Util.toast(this@PlayerActivity, "Locked: tap the screen, then the lock button"); return }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    // ---------- player ----------

    private fun startPlayer() {
        findSidecars()
        findSiblings()
        updateNav()
        wantAutoSub = subs.isNotEmpty()
        val p = buildPlayer()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { refreshPlayIcon(); updatePipActions(); bgNote(isPlaying) }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ended = true
                    showControls(true)
                    if (sleepAt == -1L) { // P4: sleep timer "end of this video": stop here, no next episode, no folder loop
                        sleepAt = 0L
                        sleepPick = 0
                        flash("Sleep timer: paused")
                    }
                    else if (sibIdx in 0 until sibs.size - 1) { // like MX Player: the next episode starts by itself
                        flash("Next: " + sibs[sibIdx + 1].second.substringBeforeLast('.'))
                        main.postDelayed({ if (ended && player?.playbackState == Player.STATE_ENDED) openSibling(1) }, 1800)
                    }
                    else if (repeatMode() == 2 && sibs.size > 1 && sibIdx == sibs.size - 1) { // P3: repeat folder, back to the first file
                        flash("Back to the first file")
                        main.postDelayed({ if (ended && player?.playbackState == Player.STATE_ENDED && repeatMode() == 2) openSibling(-sibIdx) }, 1800)
                    }
                }
                else if (state == Player.STATE_READY) ended = false
                refreshPlayIcon()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!userRot && videoSize.width > 0 && videoSize.height > 0) {
                    userRot = true // only the first time: after that the rotate button / the phone decide
                    requestedOrientation = if (videoSize.width >= videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_USER
                }
                updateInfoLine()
            }

            override fun onTracksChanged(tracks: Tracks) {
                autoPickSubtitle(tracks)
                updateCcLabel()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "play error", error)
                try {
                    sheet("Cannot play this file here", "Try another player app.", listOf(
                        SheetRow("Open in another app") { openExternal() },
                        SheetRow("Close") { finish() }
                    )).setCancelable(false)
                } catch (_: Exception) { }
            }
        })
        setItem(resumePos())
        if (PlayerSpeed.keep(this)) setSpeed(PlayerSpeed.last(this)) // P2: keep speed for next video
        applyRepeat() // P3
        applyQuality(false) // P3: saved height cap
        p.playWhenReady = true
        p.prepare()
        main.post(ticker)
        styleSubtitles()
        showControls(true)
    }

    private fun isWeb() = uri.scheme == "http" || uri.scheme == "https"

    /** S39: a web address gets an HTTP data source with Referer / Origin / User-Agent and the WebView cookies of THAT host
     *  (added per request, so HLS / DASH segments on other hosts only get their own cookies). Files keep the default player. */
    private fun buildPlayer(): ExoPlayer {
        if (!isWeb()) return ExoPlayer.Builder(this).build()
        val ua = if (uaStr.isNotBlank()) uaStr else try { android.webkit.WebSettings.getDefaultUserAgent(this) } catch (_: Exception) { "Mozilla/5.0" }
        val h = HashMap<String, String>()
        if (refUrl.startsWith("http")) {
            h["Referer"] = refUrl
            try {
                val r = Uri.parse(refUrl)
                if (!r.scheme.isNullOrEmpty() && !r.authority.isNullOrEmpty()) h["Origin"] = r.scheme + "://" + r.authority
            } catch (_: Exception) { }
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setDefaultRequestProperties(h)
        val withCookies = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val s = dataSpec.uri.scheme
                if (s != "http" && s != "https") return dataSpec
                val ck = try { android.webkit.CookieManager.getInstance().getCookie(dataSpec.uri.toString()) } catch (_: Exception) { null }
                if (ck.isNullOrEmpty()) return dataSpec
                val m = HashMap(dataSpec.httpRequestHeaders)
                m["Cookie"] = ck
                return dataSpec.buildUpon().setHttpRequestHeaders(m).build()
            }
        })
        return ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(withCookies)).build()
    }

    private fun setItem(startMs: Long) {
        val p = player ?: return
        if (sleepAt == -1L) { sleepAt = 0L; sleepPick = 0 } // P4: "end of this video" belongs to the video it was set on
        val ib = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(ArrayList(subs))
        if (isWeb()) { // links without a clear extension in the path (token / query) still need the right source type
            val l = uri.toString().lowercase()
            if (l.contains(".m3u8")) ib.setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (l.contains(".mpd")) ib.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val item = ib.build()
        if (startMs > 0) p.setMediaItem(item, startMs) else p.setMediaItem(item)
    }

    private fun resumePos(): Long {
        val v = prefs.getLong(uri.toString(), 0L)
        if (v > 5000) { main.postDelayed({ showResumeBar(v) }, 500); return v }
        return 0L
    }

    private fun showResumeBar(pos: Long) {
        resumeBar.text = "Continue from " + fmt(pos) + "     START OVER"
        resumeBar.visibility = View.VISIBLE
        resumeBar.setOnClickListener { player?.seekTo(0); resumeBar.visibility = View.GONE }
        main.removeCallbacks(resumeOff)
        main.postDelayed(resumeOff, 6000)
    }

    private val resumeOff = Runnable { resumeBar.visibility = View.GONE }

    // ---------- S37: previous / next file of the same folder ----------

    private fun natural(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val ra = re.findAll(a.lowercase()).map { it.value }.toList()
        val rb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val xi = x.trimStart('0')
                val yi = y.trimStart('0')
                if (xi.length != yi.length) xi.length - yi.length else xi.compareTo(yi)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    private fun findSiblings() {
        sibs.clear()
        sibIdx = -1
        try {
            if (Build.VERSION.SDK_INT < 29 || uri.scheme != "content" || uri.authority != "media") return
            var rel = ""
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) rel = it.getString(0) ?: ""
            }
            if (rel.isEmpty()) return
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val me = ContentUris.parseId(uri)
            val tmp = ArrayList<Triple<Long, String, String>>()
            contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", arrayOf(rel), null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    val m = c.getString(2) ?: ""
                    if (m.startsWith("video/") || m.startsWith("audio/")) tmp.add(Triple(c.getLong(0), n, m))
                }
            }
            tmp.sortWith(Comparator { a, b -> natural(a.second, b.second) })
            for ((i, t) in tmp.withIndex()) {
                sibs.add(Pair(ContentUris.withAppendedId(base, t.first), t.second))
                if (t.first == me) sibIdx = i
            }
            if (sibIdx < 0) { sibs.clear() }
        } catch (e: Exception) {
            Log.e(TAG, "siblings", e)
            sibs.clear(); sibIdx = -1
        }
    }

    private fun updateNav() {
        if (!this::prevBtn.isInitialized) return
        prevBtn.alpha = if (sibIdx > 0) 1f else 0.3f
        nextBtn.alpha = if (sibIdx in 0 until sibs.size - 1) 1f else 0.3f
    }

    private fun openSibling(delta: Int) {
        val idx = sibIdx + delta
        if (sibIdx < 0 || idx < 0 || idx >= sibs.size) { flash(if (delta > 0) "No next file" else "No previous file"); return }
        val p = player ?: return
        saveResume()
        sibIdx = idx
        uri = sibs[idx].first
        title = sibs[idx].second.substringBeforeLast('.')
        titleTv.text = title
        subs.clear()
        findSidecars()
        wantAutoSub = subs.isNotEmpty()
        autoSubDone = false
        ended = false
        resumeBar.visibility = View.GONE
        resetZoom()
        setItem(resumePos())
        p.prepare()
        p.playWhenReady = true
        updateNav()
        updateInfoLine()
    }

    private fun resetZoom() {
        zoom = 1f
        playerView.scaleX = 1f
        playerView.scaleY = 1f
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
                seek.secondaryProgress = (p.bufferedPercentage * 10).coerceIn(0, 1000)
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
        PlayerSubs.applyStyle(this, sv) // P5: colour, size, background, position (prefs sub_color, pl_sub_*)
    }

    internal fun restyleSubs() { styleSubtitles() }

    private fun subScale(): Float = PlayerSubs.sizePct(this) / 100f // P5: kept as a thin wrapper

    /** P5: subtitle tracks as page rows, Off state, pick, add file (used by PlayerSubs). */
    internal fun subOff(): Boolean {
        val p = player ?: return true
        return p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || textGroups(p.currentTracks).none { it.isSelected }
    }

    internal fun subOpts(): List<PlayerPages.AudioOpt> {
        val p = player ?: return emptyList()
        val off = subOff()
        val out = ArrayList<PlayerPages.AudioOpt>()
        var n = 0
        for (g in textGroups(p.currentTracks)) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            val gg = g
            val ii = i
            out.add(PlayerPages.AudioOpt(trackName(g.getTrackFormat(i), n), null, g.isTrackSelected(i) && !off) { pickSub(gg, ii) })
        }
        return out
    }

    private fun pickSub(g: Tracks.Group, i: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
        updateCcLabel()
        main.postDelayed({ if (panel.isOpen) panel.refresh() }, 250) // the player reports the new selection a moment later
    }

    internal fun setSubOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        updateCcLabel()
        main.postDelayed({ if (panel.isOpen) panel.refresh() }, 250)
    }

    internal fun addSubFile() { openSub.launch(arrayOf("*/*")) }

    private fun updateCcLabel() {
        val p = player ?: return
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val any = textGroups(p.currentTracks).isNotEmpty()
        val on = any && !off && textGroups(p.currentTracks).any { it.isSelected }
        ccIcon.setColorFilter(if (on) getColor(R.color.mg_primary) else Color.WHITE)
        ccIcon.alpha = if (any) 1f else 0.55f
    }

    // ---------- actions ----------

    internal fun openExternal() {
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
        val buf = player?.playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true
        spinner.visibility = if (buf) View.VISIBLE else View.GONE
        playBtn.visibility = if (buf) View.INVISIBLE else View.VISIBLE
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

    internal fun speedText(s: Float) = PlayerSpeed.fmt(s)

    internal fun setSpeed(s0: Float) {
        val s = PlayerSpeed.clamp(s0) // P2: 0.05x - 6x, 2 decimals
        player?.playbackParameters = PlaybackParameters(s, 1f)
        if (PlayerSpeed.keep(this)) PlayerPrefs.putFloat(this, PlayerPrefs.K_SPEED_LAST, s)
        speedVal.text = speedText(s)
        speedVal.setTextColor(if (abs(s - 1f) < 0.01f) Color.WHITE else getColor(R.color.mg_primary))
    }

    private fun cycleFit() {
        fitMode = (fitMode + 1) % 3
        playerView.resizeMode = when (fitMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        fitLabel.text = when (fitMode) { 1 -> "Fill"; 2 -> "Stretch"; else -> "Fit" }
        resetZoom()
        flash(when (fitMode) { 1 -> "FILL (CROP)"; 2 -> "STRETCH"; else -> "FIT TO SCREEN" })
    }

    internal fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) { Util.toast(this, "Needs Android 8 or newer"); return }
        try {
            val vs = player?.videoSize
            val w = if (vs != null && vs.width > 0) vs.width else 16
            val h = if (vs != null && vs.height > 0) vs.height else 9
            pipRatio = (w.toFloat() / h).coerceIn(0.5f, 2.0f)
            enterPictureInPictureMode(pipParams())
        } catch (e: Exception) {
            Util.toast(this, "Picture in picture is not available")
        }
    }

    // S42: play / pause button inside the PiP window
    private var pipRatio = 16f / 9f
    private val PIP_ACTION = "com.jabra.mgb.PIP_TOGGLE"
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != PIP_ACTION) return
            togglePlay()
            updatePipActions()
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((pipRatio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 26) {
            val playing = player?.isPlaying == true
            val pi = PendingIntent.getBroadcast(this, 7, Intent(PIP_ACTION).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val label = if (playing) "Pause" else "Play"
            val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
            b.setActions(listOf(RemoteAction(icon, label, label, pi)))
        }
        return b.build()
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) { pipWas = true; if (this::panel.isInitialized) panel.close(true); controls.visibility = View.GONE; resumeBar.visibility = View.GONE } else showControls(true)
    }

    // ---------- S38: dark bottom sheets (replace the grey platform dialogs) ----------

    private class SheetRow(
        val label: String,
        val sub: String? = null,
        val checked: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    private class MaxScroll(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
    }

    private fun sheet(title: String, note: String?, rows: List<SheetRow>): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val accent = getColor(R.color.mg_primary)
        val r = dp(22).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1B1C20.toInt())
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(0, dp(8), 0, dp(10))
        }
        box.addView(View(this).apply { background = round(0x40FFFFFF, 2) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(22), dp(8), dp(22), dp(6))
        })
        if (!note.isNullOrBlank()) box.addView(TextView(this).apply {
            text = note
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            setPadding(dp(22), 0, dp(22), dp(8))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val out = TypedValue()
        val ripple = if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) getDrawable(out.resourceId) else null
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(22), dp(6), dp(22), dp(6))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = row.label
                textSize = 15.5f
                setTextColor(if (row.checked) accent else Color.WHITE)
            })
            if (!row.sub.isNullOrBlank()) col.addView(TextView(this).apply {
                text = row.sub
                textSize = 12.5f
                setTextColor(0x99FFFFFF.toInt())
            })
            line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row.checked) line.addView(TextView(this).apply { text = "✓"; textSize = 18f; setTextColor(accent) })
            val click = row.onClick
            if (click != null) {
                if (ripple != null) line.foreground = ripple.constantState?.newDrawable()
                line.setOnClickListener { d.dismiss(); click() }
            }
            list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(MaxScroll(this, (resources.displayMetrics.heightPixels * 0.62f).toInt()).apply { addView(list) })
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(minOf(resources.displayMetrics.widthPixels, dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.attributes = w.attributes.also { it.dimAmount = 0.55f }
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        d.show()
        d.window?.let { w -> WindowInsetsControllerCompat(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        return d
    }

    internal fun showSubtitleDialog() {
        if (player == null) return
        main.removeCallbacks(hider)
        showControls(false)
        panel.open(PlayerSubs.page(this))
    }

    /** P4: audio tracks as page rows (language, channels, codec). Old sheet() version removed. */
    internal fun audioOpts(): List<PlayerPages.AudioOpt> {
        val p = player ?: return emptyList()
        val out = ArrayList<PlayerPages.AudioOpt>()
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val sub = listOfNotNull(PlayerPages.channelName(f.channelCount), PlayerPages.codecName(f.sampleMimeType)).joinToString(" - ")
                val gg = g
                val ii = i
                out.add(PlayerPages.AudioOpt(trackName(f, n), if (sub.isEmpty()) null else sub, g.isTrackSelected(i)) { pickAudio(gg, ii) })
            }
        }
        return out
    }

    private fun pickAudio(g: Tracks.Group, i: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
        main.postDelayed({ if (panel.isOpen) panel.refresh() }, 250) // the player reports the new selection a moment later
    }

    internal fun showAudioDialog() {
        if (audioOpts().isEmpty()) { Util.toast(this, "No audio track"); return }
        main.removeCallbacks(hider)
        showControls(false)
        panel.open(PlayerPages.audioPage(this))
    }

    /** P2: speed page of the side panel (toolbar Speed button). Root page row pushes the same page. */
    internal fun showSpeedDialog() {
        main.removeCallbacks(hider)
        showControls(false)
        panel.open(PlayerSpeed.page(this))
    }

    /** P1: the 3-dot button opens the side panel (PlayerPages.root). The old bottom sheets stay for the pages not rebuilt yet. */
    private fun showMore() {
        main.removeCallbacks(hider)
        showControls(false)
        panel.open(PlayerPages.root(this))
    }

    internal fun subSummary(): String {
        if (subOff()) return "Off"
        return subOpts().firstOrNull { it.selected }?.label ?: "On"
    }

    // P3: repeat (pl_repeat: 0 off / 1 this video / 2 folder)
    internal fun repeatMode(): Int = PlayerPrefs.getInt(this, PlayerPrefs.K_REPEAT, 0).coerceIn(0, 2)

    internal fun repeatLabel(): String = when (repeatMode()) { 1 -> "One"; 2 -> "Folder"; else -> "Off" }

    internal fun hasSiblings(): Boolean = sibs.size > 1

    internal fun applyRepeat() { player?.repeatMode = if (repeatMode() == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    internal fun setRepeat(m: Int) {
        PlayerPrefs.putInt(this, PlayerPrefs.K_REPEAT, m)
        applyRepeat()
        flash(when (m) { 1 -> "Repeat this video"; 2 -> "Repeat folder"; else -> "Repeat off" })
    }

    // P3: quality (pl_quality_cap = max video height, 0 = auto)
    internal fun qualityCap(): Int = PlayerPrefs.getInt(this, PlayerPrefs.K_QUALITY_CAP, 0)

    /** Distinct heights of the supported video tracks, highest first, each with its best bitrate (0 = unknown). */
    internal fun videoQualities(): List<Pair<Int, Int>> {
        val p = player ?: return emptyList()
        val best = HashMap<Int, Int>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                if (f.height <= 0) continue
                best[f.height] = maxOf(best[f.height] ?: 0, if (f.bitrate > 0) f.bitrate else 0)
            }
        }
        return best.entries.sortedByDescending { it.key }.map { Pair(it.key, it.value) }
    }

    /** Cap the video height (Auto = no cap). exact = also force the best track of that height right now (user just picked it). */
    internal fun applyQuality(exact: Boolean) {
        val p = player ?: return
        val h = qualityCap()
        val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (h > 0) {
            b.setMaxVideoSize(Int.MAX_VALUE, h)
            if (exact) {
                var bg: Tracks.Group? = null
                var bi = 0
                var bbr = -1
                for (g in p.currentTracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) {
                        if (!g.isTrackSupported(i)) continue
                        val f = g.getTrackFormat(i)
                        if (f.height == h && f.bitrate > bbr) { bg = g; bi = i; bbr = f.bitrate }
                    }
                }
                if (bg != null) b.setOverrideForType(TrackSelectionOverride(bg.mediaTrackGroup, bi))
            }
        } else b.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        p.trackSelectionParameters = b.build()
    }

    internal fun setQuality(h: Int) {
        PlayerPrefs.putInt(this, PlayerPrefs.K_QUALITY_CAP, h)
        applyQuality(true)
        flash(if (h == 0) "Quality: Auto" else "Quality: " + h + "p")
    }

    internal fun toggleBgAudio() { // S45
        val bg = PlayerService.on(this)
        PlayerService.setOn(this, !bg)
        if (bg) PlayerService.stop(this) else if (player?.isPlaying == true) bgNote(true)
        flash(if (bg) "Background audio off" else "Background audio on")
    }

    internal fun showSleepDialog() {
        main.removeCallbacks(hider)
        showControls(false)
        panel.open(PlayerPages.sleepPage(this))
    }

    private val sleepTask = Runnable {
        sleepAt = 0L
        sleepPick = 0
        player?.pause()
        flash("Sleep timer: paused")
        showControls(true)
    }

    /** P4: min > 0 = pause after min minutes, 0 = off, PlayerPages.SLEEP_END (-1) = pause when this video ends. */
    internal fun setSleep(min: Int) {
        main.removeCallbacks(sleepTask)
        if (min == PlayerPages.SLEEP_END) { sleepAt = -1L; sleepPick = min; flash("Sleep timer: end of video"); return }
        if (min <= 0) { sleepAt = 0L; sleepPick = 0; flash("Sleep timer off"); return }
        sleepAt = System.currentTimeMillis() + min * 60_000L
        sleepPick = min
        main.postDelayed(sleepTask, min * 60_000L)
        flash("Sleep timer: $min min")
    }

    /** P4: quick add, on top of the time that is left (starts a new timer when none is running). */
    internal fun addSleep(min: Int) {
        val left = if (sleepAt > 0L) (sleepAt - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
        val total = left + min * 60_000L
        main.removeCallbacks(sleepTask)
        sleepAt = System.currentTimeMillis() + total
        sleepPick = 0
        main.postDelayed(sleepTask, total)
        flash("Sleep timer: +$min min")
    }

    /** P4: minutes left, rounded up (0 = no timer or end-of-video mode). */
    internal fun sleepLeftMin(): Int = if (sleepAt > 0L) ((sleepAt - System.currentTimeMillis()) / 60000L + 1L).toInt().coerceAtLeast(1) else 0

    internal fun showInfo() {
        val p = player ?: return
        val rows = ArrayList<SheetRow>()
        rows.add(SheetRow("Name", title))
        try {
            val vs = p.videoSize
            if (vs.width > 0) rows.add(SheetRow("Resolution", vs.width.toString() + " x " + vs.height))
            val vf = p.videoFormat
            if (vf != null) {
                if (vf.frameRate > 0) rows.add(SheetRow("Frame rate", String.format("%.2f fps", vf.frameRate)))
                val vc = (vf.codecs ?: vf.sampleMimeType ?: "").removePrefix("video/")
                if (vc.isNotBlank()) rows.add(SheetRow("Video codec", vc))
                if (vf.bitrate > 0) rows.add(SheetRow("Video bitrate", String.format("%.1f Mbps", vf.bitrate / 1e6)))
            }
            val af = p.audioFormat
            if (af != null) {
                val ac = (af.codecs ?: af.sampleMimeType ?: "").removePrefix("audio/")
                if (ac.isNotBlank()) rows.add(SheetRow("Audio codec", ac + (if (af.channelCount > 0) "  " + af.channelCount + " ch" else "")))
            }
            if (p.duration > 0) rows.add(SheetRow("Duration", fmt(p.duration)))
            var size = 0L
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) size = it.getLong(0) }
            if (size > 0) rows.add(SheetRow("File size", EngineApi.fmtSize(size)))
        } catch (e: Exception) {
            Log.e(TAG, "info", e)
        }
        sheet("Video info", null, rows)
    }

    // ---------- controls visibility ----------

    private val hider = Runnable { if (player?.isPlaying == true && !dragging && !(this::panel.isInitialized && panel.isOpen)) showControls(false) }

    private fun showControls(on: Boolean) {
        shown = on && !locked
        main.removeCallbacks(hider)
        controls.animate().cancel()
        if (shown) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(160).start()
            main.postDelayed(hider, 3500)
        } else {
            controls.animate().alpha(0f).setDuration(160).withEndAction { if (!shown) controls.visibility = View.INVISIBLE }.start()
        }
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

    internal fun flash(msg: String) {
        flash.text = msg
        flash.visibility = View.VISIBLE
        main.removeCallbacks(flashOff)
        main.postDelayed(flashOff, 900)
    }

    private val flashOff = Runnable { flash.visibility = View.GONE }

    /** Brightness / volume: a small level bar at the top instead of plain text. */
    private fun showHud(label: String, pct: Int) {
        hudText.text = label
        hudBar.progress = pct.coerceIn(0, 100)
        hud.visibility = View.VISIBLE
        main.removeCallbacks(hudOff)
        main.postDelayed(hudOff, 900)
    }

    private val hudOff = Runnable { hud.visibility = View.GONE }

    /** Double tap feedback: a round "10 s" mark on the side that was tapped. */
    private fun tapFlash(left: Boolean) {
        val v = if (left) tapL else tapR
        v.animate().cancel()
        v.alpha = 1f
        v.visibility = View.VISIBLE
        v.animate().alpha(0f).setStartDelay(350).setDuration(300).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun updateInfoLine() {
        if (!this::infoTv.isInitialized) return
        val parts = ArrayList<String>()
        if (sibs.size > 1 && sibIdx >= 0) parts.add((sibIdx + 1).toString() + " of " + sibs.size)
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) parts.add(vs.width.toString() + "x" + vs.height)
        infoTv.text = parts.joinToString("  ·  ")
        infoTv.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- UI ----------

    /** Round / plain icon button. pad = inner padding in dp (the icon is scaled to fit). */
    private fun iconBtn(res: Int, size: Int, pad: Int, on: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        setOnClickListener { on(); poke() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** "back 10 s" / "forward 10 s": arrow icon with the number inside. */
    private fun skipBtn(res: Int, on: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.addView(ImageView(this).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }, FrameLayout.LayoutParams(dp(54), dp(54)))
        f.addView(TextView(this).apply {
            text = "10"
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(dp(54), dp(54)).apply { topMargin = dp(2) })
        f.setOnClickListener { on(); poke() }
        f.layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        return f
    }

    /** Bottom toolbar item: 28 dp slot (icon or text) with a small label below. */
    private fun cell(label: String, slot: View, on: () -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
            setOnClickListener { on(); poke() }
        }
        c.addView(slot, LinearLayout.LayoutParams(dp(28), dp(28)))
        c.addView(TextView(this).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(0xCCFFFFFF.toInt())
        })
        return c
    }

    private fun iconSlot(res: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    private fun styledSeek(accent: Int): SeekBar {
        val track = round(0x40FFFFFF, 2).apply { setSize(-1, dp(4)) }
        val buf = ClipDrawable(round(0x66FFFFFF, 2).apply { setSize(-1, dp(4)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        val prog = ClipDrawable(round(accent, 2).apply { setSize(-1, dp(4)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        val ld = LayerDrawable(arrayOf<Drawable>(track, buf, prog))
        ld.setId(0, android.R.id.background)
        ld.setId(1, android.R.id.secondaryProgress)
        ld.setId(2, android.R.id.progress)
        return SeekBar(this).apply {
            max = 1000
            progressDrawable = ld
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                setSize(dp(16), dp(16))
                setStroke(dp(2), Color.WHITE)
            }
            splitTrack = false
            setPadding(dp(10), 0, dp(10), 0)
        }
    }

    private fun buildUi() {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val accent = getColor(R.color.mg_primary)
        rootView = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
        }
        rootView.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))

        // touch layer: tap, double tap, hold, swipes, pinch
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
                    e.x < w / 3f -> { seekBy(-10_000); tapFlash(true) }
                    e.x > w * 2f / 3f -> { seekBy(10_000); tapFlash(false) }
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
                    val diff = seekTarget - p.currentPosition
                    flash(fmt(seekTarget) + "\n[" + (if (diff < 0) "-" else "+") + fmt(abs(diff)) + "]")
                } else if (swipeLeft) {
                    val lp = window.attributes
                    var br = lp.screenBrightness
                    if (br < 0f) br = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
                    br = (br + dy / h * 1.3f).coerceIn(0.02f, 1f)
                    lp.screenBrightness = br
                    window.attributes = lp
                    showHud("Brightness  " + (br * 100).toInt() + "%", (br * 100).toInt())
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    volAcc += dy / h * max * 1.4f
                    val step = volAcc.toInt()
                    if (step != 0) {
                        volAcc -= step
                        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    }
                    val pc = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
                    showHud("Volume  $pc%", pc)
                }
                return true
            }
        })
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (locked) return true
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
                playerView.scaleX = zoom
                playerView.scaleY = zoom
                flash((zoom * 100).toInt().toString() + "%")
                return true
            }
        })
        touch.setOnTouchListener { _, ev ->
            sgd.onTouchEvent(ev)
            if (sgd.isInProgress || ev.pointerCount > 1) return@setOnTouchListener true
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

        // ----- controls: top bar, centre buttons, bottom block -----
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        top.addView(iconBtn(R.drawable.ic_back, 48, 12) { finish() })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(8), 0) }
        titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoTv = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 11.5f
            maxLines = 1
            visibility = View.GONE
        }
        titleCol.addView(titleTv)
        titleCol.addView(infoTv)
        top.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(iconBtn(R.drawable.ic_screen, 48, 12) { toggleRotate() })
        top.addView(iconBtn(R.drawable.ic_more, 48, 12) { showMore() })
        controls.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // centre: previous, -10, play, +10, next
        val mid = FrameLayout(this)
        val midRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        prevBtn = iconBtn(R.drawable.ic_skip_prev, 46, 11) { openSibling(-1) }
        nextBtn = iconBtn(R.drawable.ic_skip_next, 46, 11) { openSibling(1) }
        playBtn = iconBtn(R.drawable.ic_pause, 72, 20) { togglePlay() }
        playBtn.setColorFilter(getColor(R.color.mg_on_primary))
        playBtn.background = round(accent, 36)
        val gap = dp(10)
        midRow.addView(prevBtn)
        midRow.addView(skipBtn(R.drawable.ic_pl_back10) { seekBy(-10_000) }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(gap, 0, gap, 0) })
        midRow.addView(playBtn, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(gap, 0, gap, 0) })
        midRow.addView(skipBtn(R.drawable.ic_pl_fwd10) { seekBy(10_000) }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(gap, 0, gap, 0) })
        midRow.addView(nextBtn)
        mid.addView(midRow, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        controls.addView(mid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(26), dp(14), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xD9000000.toInt(), 0x00000000))
        }
        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tCur = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12.5f; text = "00:00"; gravity = Gravity.CENTER; minWidth = dp(46) }
        tTotal = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 12.5f; text = "00:00"; gravity = Gravity.CENTER; minWidth = dp(46) }
        seek = styledSeek(accent).apply {
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
        seekRow.addView(seek, LinearLayout.LayoutParams(0, dp(32), 1f))
        seekRow.addView(tTotal)
        bottom.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // toolbar: lock, speed, subtitles, audio, fit, picture in picture
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedVal = TextView(this).apply {
            text = "1x"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        ccIcon = iconSlot(R.drawable.ic_pl_cc)
        val fitCell = cell("Fit", iconSlot(R.drawable.ic_pl_fit)) { cycleFit() }
        fitLabel = fitCell.getChildAt(1) as TextView
        val cw = LinearLayout.LayoutParams(0, WRAP, 1f)
        bar.addView(cell("Lock", iconSlot(R.drawable.ic_lock)) { setLocked(true) }, cw)
        bar.addView(cell("Speed", speedVal) { showSpeedDialog() }, cw)
        bar.addView(cell("Subtitles", ccIcon) { showSubtitleDialog() }, cw)
        bar.addView(cell("Audio", iconSlot(R.drawable.ic_pl_audio)) { showAudioDialog() }, cw)
        bar.addView(fitCell, cw)
        bar.addView(cell("PiP", iconSlot(R.drawable.ic_pl_pip)) { enterPip() }, cw)
        bottom.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        controls.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        rootView.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        // ----- overlays -----
        flash = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = round(0xB3000000.toInt(), 16)
            visibility = View.GONE
        }
        rootView.addView(flash, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            background = round(0xB3000000.toInt(), 16)
            visibility = View.GONE
        }
        hudText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
        hudBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
        }
        hud.addView(hudText)
        hud.addView(hudBar, LinearLayout.LayoutParams(dp(170), dp(6)).apply { topMargin = dp(8) })
        rootView.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })

        fun tapMark(label: String) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x44FFFFFF) }
            visibility = View.GONE
        }
        tapL = tapMark("«\n10 s")
        tapR = tapMark("»\n10 s")
        rootView.addView(tapL, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(dp(40), 0, 0, 0) })
        rootView.addView(tapR, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, dp(40), 0) })

        spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        rootView.addView(spinner, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))

        unlockBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(getColor(R.color.mg_on_primary))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = round(accent, 28)
            visibility = View.GONE
            setOnClickListener { setLocked(false) }
        }
        rootView.addView(unlockBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(dp(24), 0, 0, 0) })

        resumeBar = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = round(0xE6202024.toInt(), 22)
            visibility = View.GONE
        }
        rootView.addView(resumeBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(140)) })
        panel = PlayerPanel(this, rootView).also { it.onClosed = { showControls(true) } } // P1: added last = on top of everything
        updateNav()
        updateInfoLine()

        // keep the controls out of a display cutout / camera hole
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            controls.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }
        setContentView(rootView)
    }
}

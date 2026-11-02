package com.jabra.mgb

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import kotlin.math.abs
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
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * S28: built-in video / audio player (full-screen Dialog, no manifest entry). Opened from the Downloads screen
 * and from chrome.downloads.open for media files.
 * Minimal on purpose: back + title + rotate on top; seek bar, lock, -10s, play / pause, +10s, speed on the bottom.
 * Tap = show / hide the controls, double tap left / right half = back / forward 10 s.
 * S35: the player rotates its OWN picture (rotate button, or automatically for wide videos): the Activity never
 * rotates for it, so the browser stays portrait and the player is not closed. Fit / Fill, hold = 2x, pauses when the app
 * goes to the background, comes back if the Activity is recreated. Filled accent play button, balanced control row.
 * Speed 0.25x to 6x (MediaPlayer PlaybackParams, API 23+; the device may refuse very high speeds).
 */
object PlayerScreen {
    private const val TAG = "MGB.player"
    private var current: Screen? = null

    private class Pend(val uri: Uri, val title: String, val land: Boolean, val at: Long = System.currentTimeMillis())
    private var pending: Pend? = null

    /** MainActivity.onDestroy: keep the player state when the Activity is only recreated. */
    fun onHostDestroy(act: Activity) {
        val s = current ?: return
        pending = if (act.isFinishing) null else Pend(s.uri, s.title, s.landscapeWanted)
        try { s.dismiss() } catch (_: Exception) { }
        current = null
    }

    /** MainActivity.onCreate (posted): reopen the player that was open before a recreate. */
    fun reopenPending(act: Activity) {
        val p = pending ?: return
        pending = null
        if (System.currentTimeMillis() - p.at > 15_000) return // only a quick recreate, not a later start
        open(act, p.uri, p.title, p.land)
    }

    fun open(act: Activity, uri: Uri, title: String, land: Boolean = false) {
        if (act.isFinishing || act.isDestroyed) return
        try { current?.dismiss() } catch (_: Exception) { }
        try {
            val s = Screen(act, uri, title, land)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
            Util.toast(act, "Cannot open the player")
        }
    }

    private class Screen(private val act: Activity, val uri: Uri, val title: String, startLand: Boolean) :
        Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val main = Handler(Looper.getMainLooper())

        private lateinit var vv: VideoView
        private lateinit var controls: View
        private lateinit var seek: SeekBar
        private lateinit var tCur: TextView
        private lateinit var tTotal: TextView
        private lateinit var playBtn: ImageView
        private lateinit var speedBtn: TextView
        private lateinit var lockBtn: ImageView
        private lateinit var flash: TextView
        private var player: MediaPlayer? = null
        private var speed = 1f
        private var locked = false
        private var shown = true
        private var dragging = false
        var landscapeWanted = startLand // S35: picture turned 90 degrees inside the dialog
        private var userRot = false
        private var fill = false
        private var boost = false
        private lateinit var rootView: FrameLayout
        private lateinit var fitBtn: TextView
        private var ended = false // S29: video reached the end (stay open, show controls)
        // S30: swipe gestures (left half = brightness, right half = volume, horizontal = seek) + resume position
        private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val resumePrefs = act.getSharedPreferences("mgb_resume", Context.MODE_PRIVATE)
        private var swipe = 0 // 0 none, 1 vertical, 2 horizontal
        private var swipeLeft = false
        private var seekTarget = -1
        private var volAcc = 0f

        private fun dp(v: Int) = Util.dp(act, v)

        private val hide = Runnable { if (vv.isPlaying && !dragging) setControls(false) }
        private val tick = object : Runnable {
            override fun run() {
                if (!dragging && player != null) {
                    try {
                        seek.progress = vv.currentPosition
                        tCur.text = fmt(vv.currentPosition)
                    } catch (_: Exception) { }
                }
                main.postDelayed(this, 400)
            }
        }

        // S35: pause when the app leaves the screen (home, lock, another app)
        private val life = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) {
                if (a === act && this@Screen::vv.isInitialized && vv.isPlaying) { vv.pause(); updatePlay(); setControls(true) }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }

        init {
            act.application.registerActivityLifecycleCallbacks(life)
            setOnDismissListener {
                try { act.application.unregisterActivityLifecycleCallbacks(life) } catch (_: Exception) { }
                main.removeCallbacksAndMessages(null)
                saveResume()
                try { vv.stopPlayback() } catch (_: Exception) { }
                if (current === this) current = null
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(Color.BLACK))
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            build()
            window?.let { w ->
                val c = WindowInsetsControllerCompat(w, w.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        private fun fmt(ms: Int): String {
            val t = (ms / 1000).coerceAtLeast(0)
            val h = t / 3600
            val m = (t % 3600) / 60
            val s = t % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        }

        private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

        private fun icon(res: Int, size: Int = 44, on: () -> Unit) = ImageView(act).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { on(); poke() }
        }.also { it.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }

        private fun pill(label: String, on: () -> Unit) = TextView(act).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = round(0x2EFFFFFF, 20) // S35: soft filled pill (no outline)
            minimumWidth = dp(56)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { on(); poke() }
        }

        // ---- S35: own rotation + fit / fill

        /** Turn the picture 90 degrees inside the dialog when landscape is wanted and the screen is portrait. */
        private fun applyRotation() {
            if (!this::rootView.isInitialized) return
            val p = rootView.parent as? View ?: return
            val pw = p.width
            val ph = p.height
            if (pw <= 0 || ph <= 0) return
            val lp = rootView.layoutParams as? FrameLayout.LayoutParams ?: return
            val fake = landscapeWanted && ph > pw
            if (fake) {
                if (lp.width != ph || lp.height != pw || rootView.rotation != 90f) {
                    lp.width = ph; lp.height = pw; lp.gravity = Gravity.CENTER
                    rootView.layoutParams = lp
                    rootView.rotation = 90f
                }
            } else if (lp.width != MATCH || lp.height != MATCH || rootView.rotation != 0f) {
                lp.width = MATCH; lp.height = MATCH
                rootView.layoutParams = lp
                rootView.rotation = 0f
            }
        }

        private fun toggleLandscape() {
            if (!Orient.isPortrait(act)) { Util.toast(act, "The screen is already landscape"); return }
            landscapeWanted = !landscapeWanted
            userRot = true
            applyRotation()
            showFlash(if (landscapeWanted) "Landscape" else "Portrait")
        }

        /** Fill: scale the (aspect-fitted) VideoView until it covers the whole screen. */
        private fun applyFit() {
            val mp = player
            val box = vv.parent as? View
            if (!fill || mp == null || box == null || mp.videoWidth <= 0 || mp.videoHeight <= 0 || box.width <= 0) {
                vv.scaleX = 1f; vv.scaleY = 1f
                return
            }
            val rw = box.width.toFloat()
            val rh = box.height.toFloat()
            val fit = minOf(rw / mp.videoWidth, rh / mp.videoHeight)
            val cover = maxOf(rw / mp.videoWidth, rh / mp.videoHeight)
            val sc = if (fit > 0f) cover / fit else 1f
            vv.scaleX = sc; vv.scaleY = sc
        }

        private fun toggleFit() {
            fill = !fill
            fitBtn.text = if (fill) "Fill" else "Fit"
            applyFit()
            showFlash(if (fill) "Fill screen" else "Fit screen")
        }

        private fun endBoost() {
            if (!boost) return
            boost = false
            applySpeed()
        }

        private fun build() {
            val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
            rootView = root

            vv = VideoView(act)
            root.addView(vv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

            // gesture layer
            val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (locked) flashLock() else setControls(!shown)
                    return true
                }
                override fun onLongPress(e: MotionEvent) { // S35: hold = 2x while pressed
                    if (locked || swipe != 0 || !vv.isPlaying) return
                    boost = true
                    try { player?.let { it.playbackParams = it.playbackParams.setSpeed(2f) } } catch (_: Exception) { }
                    showFlash("2x")
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (locked) return true
                    if (e.x < root.width / 2f) jump(-10000) else jump(10000)
                    return true
                }
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    if (locked || e1 == null) return true
                    if (swipe == 0) {
                        swipe = if (abs(e2.y - e1.y) > abs(e2.x - e1.x)) 1 else 2
                        swipeLeft = e1.x < root.width / 2f
                        volAcc = 0f
                    }
                    if (swipe == 1) {
                        val h = root.height.coerceAtLeast(1).toFloat()
                        if (swipeLeft) changeBrightness(dy / h * 1.4f) else changeVolume(dy / h * 1.4f)
                    } else {
                        val d = try { vv.duration } catch (e: Exception) { 0 }
                        if (d > 0) {
                            val base = if (seekTarget >= 0) seekTarget else vv.currentPosition
                            seekTarget = (base - (dx * d / (root.width.coerceAtLeast(1) * 3f)).toInt()).coerceIn(0, d)
                            showFlash(fmt(seekTarget))
                        }
                    }
                    return true
                }
            })
            val layer = View(act)
            layer.setOnTouchListener { _, e ->
                gd.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (swipe == 2 && seekTarget >= 0) {
                        vv.seekTo(seekTarget)
                        seek.progress = seekTarget
                        tCur.text = fmt(seekTarget)
                    }
                    swipe = 0
                    seekTarget = -1
                    endBoost() // S35
                }
                true
            }
            root.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))

            flash = TextView(act).apply {
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = round(0x99000000.toInt(), 28)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                alpha = 0f
            }
            root.addView(flash, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

            // controls = top bar + bottom block
            val ctl = FrameLayout(act)
            controls = ctl

            val top = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(24), dp(8), dp(12))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
            }
            top.addView(icon(R.drawable.ic_back, 48) { dismiss() })
            top.addView(TextView(act).apply {
                text = title
                textSize = 16f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(icon(R.drawable.ic_screen, 48) { toggleLandscape() }) // S35: turns the picture, not the Activity
            ctl.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

            val bottom = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(28), dp(16), dp(18))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
            }
            val srow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            tCur = TextView(act).apply { text = "00:00"; textSize = 12f; setTextColor(Color.WHITE) }
            tTotal = TextView(act).apply { text = "00:00"; textSize = 12f; setTextColor(Color.WHITE) }
            seek = SeekBar(act).apply {
                progressTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary))
                thumbTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary))
                progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) tCur.text = fmt(p) }
                    override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true; main.removeCallbacks(hide) }
                    override fun onStopTrackingTouch(sb: SeekBar?) { dragging = false; vv.seekTo(progress); poke() }
                })
            }
            srow.addView(tCur)
            srow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            srow.addView(tTotal)
            bottom.addView(srow, LinearLayout.LayoutParams(MATCH, WRAP))

            val brow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            lockBtn = icon(R.drawable.ic_lock) { setLocked(true) }
            val left = LinearLayout(act).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            left.addView(lockBtn)
            val mid = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            mid.addView(pill("-10") { jump(-10000) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            playBtn = icon(R.drawable.ic_pause, 64) { togglePlay() }
            playBtn.setColorFilter(act.getColor(R.color.mg_on_primary)) // S35: accent circle
            playBtn.background = round(act.getColor(R.color.mg_primary), 32)
            mid.addView(playBtn, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(14); marginEnd = dp(14) })
            mid.addView(pill("+10") { jump(10000) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            val right = LinearLayout(act).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            fitBtn = pill("Fit") { toggleFit() }
            right.addView(fitBtn, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
            speedBtn = pill("1x") { showSpeed() }
            right.addView(speedBtn, LinearLayout.LayoutParams(WRAP, dp(40)))
            brow.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            brow.addView(mid, LinearLayout.LayoutParams(WRAP, WRAP))
            brow.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(brow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            ctl.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            root.addView(ctl, FrameLayout.LayoutParams(MATCH, MATCH))

            vv.setOnPreparedListener { mp ->
                player = mp
                seek.max = vv.duration
                tTotal.text = fmt(vv.duration)
                applySpeed()
                // S35: wide video on a portrait screen starts landscape (the rotate button undoes it)
                if (!userRot && !landscapeWanted && mp.videoWidth > mp.videoHeight && Orient.isPortrait(act)) {
                    landscapeWanted = true
                    applyRotation()
                }
                applyFit()
                val at = resumePrefs.getInt(uri.toString(), 0)
                if (at > 5000 && at < vv.duration - 5000) {
                    vv.seekTo(at)
                    showFlash("Resumed " + fmt(at))
                }
                vv.start()
                updatePlay()
                poke()
            }
            vv.setOnCompletionListener { ended = true; updatePlay(); setControls(true) }
            vv.setOnErrorListener { _, _, _ ->
                // S29 FIX: some phones report an error at the very end of a video: do not close the player then
                val d = try { vv.duration } catch (e: Exception) { 0 }
                val p = try { vv.currentPosition } catch (e: Exception) { 0 }
                if (ended || (d > 0 && p >= d - 2500)) {
                    ended = true
                    updatePlay()
                    setControls(true)
                } else {
                    Util.toast(act, "Cannot play this file")
                    dismiss()
                }
                true
            }
            setContentView(root, FrameLayout.LayoutParams(MATCH, MATCH)) // S35: FrameLayout params so applyRotation can resize it
            (root.parent as? View)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyRotation() // S35: also handles the first layout (startLand) and a real rotation
                applyFit()
            }
            vv.setVideoURI(uri)
            main.post(tick)
        }

        // ---- controls

        private fun setControls(on: Boolean) {
            shown = on
            main.removeCallbacks(hide)
            if (on) {
                controls.visibility = View.VISIBLE
                controls.animate().alpha(1f).setDuration(160).start()
            } else { // INVISIBLE views get no touches, so hidden buttons never eat a tap
                controls.animate().alpha(0f).setDuration(160).withEndAction { if (!shown) controls.visibility = View.INVISIBLE }.start()
            }
            if (on) main.postDelayed(hide, 3500)
        }

        /** Any button press / progress: keep the bar visible a bit longer. */
        private fun poke() {
            if (!shown || locked) return
            main.removeCallbacks(hide)
            main.postDelayed(hide, 3500)
        }

        private fun setLocked(on: Boolean) {
            locked = on
            if (on) {
                setControls(false)
                flashLock()
            } else {
                lockBtn.setColorFilter(Color.WHITE)
                setControls(true)
            }
        }

        private var unlockView: ImageView? = null

        /** While locked: a tap shows a small unlock button for 2 seconds. */
        private fun flashLock() {
            var u = unlockView
            if (u == null) {
                u = ImageView(act).apply {
                    setImageResource(R.drawable.ic_lock)
                    setColorFilter(act.getColor(R.color.mg_on_primary))
                    scaleType = ImageView.ScaleType.CENTER
                    background = round(act.getColor(R.color.mg_primary), 28)
                    alpha = 0f
                    setOnClickListener { alpha = 0f; visibility = View.GONE; setLocked(false) }
                }
                (vv.parent as? FrameLayout)?.addView(u, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START).apply {
                    marginStart = dp(20); bottomMargin = dp(40)
                })
                unlockView = u
            }
            u.visibility = View.VISIBLE
            u.animate().alpha(1f).setDuration(120).start()
            main.removeCallbacks(hideUnlock)
            main.postDelayed(hideUnlock, 2000)
        }

        private val hideUnlock = Runnable {
            unlockView?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction { unlockView?.visibility = View.GONE }?.start()
        }

        private fun togglePlay() {
            if (ended) { ended = false; vv.seekTo(0) } // S29: play again from the start after the end
            if (vv.isPlaying) vv.pause() else vv.start()
            updatePlay()
        }

        private fun updatePlay() {
            playBtn.setImageResource(if (vv.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }

        private fun jump(ms: Int) {
            val d = vv.duration
            val to = (vv.currentPosition + ms).coerceIn(0, if (d > 0) d else Int.MAX_VALUE)
            vv.seekTo(to)
            seek.progress = to
            tCur.text = fmt(to)
            flash.text = if (ms < 0) "-10s" else "+10s"
            flash.animate().cancel()
            flash.alpha = 1f
            flash.animate().alpha(0f).setStartDelay(450).setDuration(250).start()
        }

        // ---- S30 swipe + resume

        private fun showFlash(t: String) {
            flash.text = t
            flash.animate().cancel()
            flash.alpha = 1f
            flash.animate().alpha(0f).setStartDelay(700).setDuration(250).start()
        }

        private fun changeBrightness(delta: Float) {
            val w = window ?: return
            val lp = w.attributes
            val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
            val v = (cur + delta).coerceIn(0.02f, 1f)
            lp.screenBrightness = v
            w.attributes = lp
            showFlash("Brightness " + Math.round(v * 100) + "%")
        }

        private fun changeVolume(delta: Float) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            volAcc += delta * max
            val step = volAcc.toInt()
            if (step != 0) {
                volAcc -= step
                val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceIn(0, max)
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (_: Exception) { }
            }
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            showFlash("Volume " + (now * 100 / max.coerceAtLeast(1)) + "%")
        }

        /** Remember where a video stopped (not near the start or the end); forget it when finished. */
        private fun saveResume() {
            try {
                val d = vv.duration
                val p = vv.currentPosition
                val e = resumePrefs.edit()
                if (!ended && d > 0 && p > 5000 && p < d - 5000) e.putInt(uri.toString(), p) else e.remove(uri.toString())
                e.apply()
            } catch (_: Exception) { }
        }

        // ---- speed

        private fun speedLabel(s: Float) = if (s % 1f == 0f) s.toInt().toString() + "x" else String.format("%.2fx", s).replace(Regex("0+x$"), "x")

        private fun setSpeed(s: Float) {
            speed = (Math.round(s / 0.25f) * 0.25f).coerceIn(0.25f, 6f)
            speedBtn.text = speedLabel(speed)
            applySpeed()
        }

        private fun applySpeed() {
            val mp = player ?: return
            try {
                val was = vv.isPlaying
                mp.playbackParams = mp.playbackParams.setSpeed(speed)
                if (!was && vv.isPlaying) vv.pause()
            } catch (e: Exception) {
                Util.toast(act, "This speed is not supported here")
            }
        }

        private fun showSpeed() {
            val dlg = BottomSheetDialog(act)
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(24))
            }
            val value = TextView(act).apply {
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val bar = SeekBar(act).apply {
                max = 23 // 0.25x .. 6x in 0.25 steps
                progressTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary))
                thumbTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary))
            }
            fun sync() {
                value.text = String.format("%.2fx", speed)
                bar.progress = (Math.round(speed / 0.25f) - 1).coerceIn(0, 23)
            }
            fun step(label: String, d: Float) = TextView(act).apply {
                text = label
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_text))
                background = round(act.getColor(R.color.mg_surface_variant), 24)
                setOnClickListener { setSpeed(speed + d); sync() }
            }
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(step("-", -0.25f), LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(step("+", 0.25f), LinearLayout.LayoutParams(dp(48), dp(48)))
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { setSpeed((p + 1) * 0.25f); value.text = String.format("%.2fx", speed) }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f)) {
                chips.addView(TextView(act).apply {
                    text = speedLabel(v)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(act.getColor(R.color.mg_text))
                    background = round(act.getColor(R.color.mg_surface_variant), 18)
                    setOnClickListener { setSpeed(v); sync() }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            }
            col.addView(chips, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
            col.addView(TextView(act).apply {
                text = "Reset to 1x"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_primary))
                setOnClickListener { setSpeed(1f); sync() }
            }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(8) })
            sync()
            dlg.setContentView(col)
            dlg.behavior.skipCollapsed = true
            dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dlg.show()
        }
    }
}

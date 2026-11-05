package com.jabra.mgb

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * PLAYER PLAN P1: translucent settings panel over the video. Landscape (width >= 480 dp): slides in from the RIGHT,
 * full height. Portrait / narrow: bottom sheet (max 62% height). Pages are a stack: open(page) / push(page) / pop() / close().
 * Back arrow, system back and a tap on the dimmed video all pop / close. The video keeps playing.
 * A page = title + build lambda (Builder: section, row, radio, toggle, note, custom). refresh() rebuilds the current page.
 */
class PlayerPanel(internal val act: PlayerActivity, private val host: FrameLayout) {
    class Page(val title: String, val build: Builder.() -> Unit)

    /** Short callbacks only: a page is rebuilt from scratch on refresh(), so keep state in the activity / prefs. */
    class Builder(private val p: PlayerPanel, private val list: LinearLayout) {
        private val act get() = p.act
        private fun dp(n: Int) = Util.dp(act, n)
        private val accent get() = act.getColor(R.color.mg_primary)

        fun section(label: String) {
            list.addView(TextView(act).apply {
                text = label.uppercase()
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
                setTextColor(accent)
                setPadding(dp(22), dp(14), dp(22), dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun note(text: String) {
            list.addView(TextView(act).apply {
                this.text = text
                textSize = 12.5f
                setTextColor(0x99FFFFFF.toInt())
                setPadding(dp(22), dp(4), dp(22), dp(8))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun line(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(22), dp(6), dp(22), dp(6))
            p.ripple()?.let { foreground = it }
        }

        private fun add(v: View) {
            list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        /** icon = 0 for none. value = right text. chevron = this row opens another page / dialog. */
        fun row(icon: Int, label: String, value: String = "", chevron: Boolean = false, onClick: (() -> Unit)? = null) {
            val l = line()
            if (icon != 0) l.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(0xD9FFFFFF.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(16) })
            l.addView(TextView(act).apply {
                text = label
                textSize = 15.5f
                setTextColor(Color.WHITE)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value.isNotEmpty()) l.addView(TextView(act).apply {
                text = value
                textSize = 13f
                setTextColor(0x99FFFFFF.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(140)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            if (chevron) l.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(0x99FFFFFF.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(4) })
            if (onClick != null) l.setOnClickListener { onClick() }
            add(l)
        }

        /** Single choice row: selected = accent text + check. */
        fun radio(label: String, sub: String? = null, checked: Boolean, onClick: () -> Unit) {
            val l = line()
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = label
                textSize = 15.5f
                setTextColor(if (checked) accent else Color.WHITE)
            })
            if (!sub.isNullOrBlank()) col.addView(TextView(act).apply {
                text = sub
                textSize = 12.5f
                setTextColor(0x99FFFFFF.toInt())
            })
            l.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (checked) l.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(accent)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            l.setOnClickListener { onClick() }
            add(l)
        }

        /** On / Off row: the value text turns accent when on. Haptic tick, then the page is rebuilt. */
        fun toggle(icon: Int, label: String, on: Boolean, onToggle: () -> Unit) {
            val l = line()
            if (icon != 0) l.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(0xD9FFFFFF.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(16) })
            l.addView(TextView(act).apply {
                text = label
                textSize = 15.5f
                setTextColor(Color.WHITE)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            l.addView(TextView(act).apply {
                text = if (on) "On" else "Off"
                textSize = 13f
                setTextColor(if (on) accent else 0x99FFFFFF.toInt())
            })
            l.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onToggle()
                p.refresh()
            }
            add(l)
        }

        fun custom(v: View) = add(v)
    }

    private class MaxScroll(ctx: android.content.Context) : ScrollView(ctx) {
        var maxH = 0 // 0 = no limit (the parent decides)
        override fun onMeasure(w: Int, h: Int) {
            if (maxH > 0) super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)) else super.onMeasure(w, h)
        }
    }

    /** Called after a normal (animated) close, not after close(quiet = true). The activity brings the controls back here. */
    var onClosed: (() -> Unit)? = null

    private val stack = ArrayList<Page>()
    private var opened = false
    private var side = false
    private fun dp(n: Int) = Util.dp(act, n)

    val isOpen: Boolean get() = opened

    private val layer = FrameLayout(act).apply {
        visibility = View.GONE
        setBackgroundColor(0x26000000)
        isClickable = true
        setOnClickListener { close() }
    }
    private val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true // taps inside the panel never reach the dim layer
    }
    private val titleTv = TextView(act).apply {
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val scroll = MaxScroll(act)
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    init {
        val header = FrameLayout(act)
        header.addView(titleTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(56); rightMargin = dp(56)
        })
        header.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Back"
            ripple()?.let { foreground = it }
            setOnClickListener { pop() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(10) })
        box.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layer.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun ripple(): Drawable? {
        val o = TypedValue()
        return if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, o, true)) act.getDrawable(o.resourceId) else null
    }

    fun open(page: Page) {
        stack.clear()
        stack.add(page)
        layout()
        render(true)
        box.animate().cancel()
        layer.animate().cancel()
        val wasOpen = opened
        opened = true
        layer.visibility = View.VISIBLE
        if (wasOpen) { layer.alpha = 1f; box.translationX = 0f; box.translationY = 0f; return }
        if (TabPrefs.reduce(act)) { layer.alpha = 1f; box.translationX = 0f; box.translationY = 0f; return }
        layer.alpha = 0f
        if (side) { box.translationX = dp(400).toFloat(); box.translationY = 0f }
        else { box.translationY = act.resources.displayMetrics.heightPixels * 0.7f; box.translationX = 0f }
        layer.animate().alpha(1f).setDuration(200).start()
        box.animate().translationX(0f).translationY(0f).setDuration(200).start()
    }

    fun push(page: Page) {
        if (!opened) return
        stack.add(page)
        render(true)
    }

    /** One page back; from the first page it closes the panel. */
    fun pop() {
        if (!opened) return
        if (stack.size <= 1) { close(); return }
        stack.removeAt(stack.size - 1)
        render(true)
    }

    fun refresh() { if (opened && stack.isNotEmpty()) render(false) }

    /** quiet = instant and no onClosed (used for PiP / onStop). */
    fun close(quiet: Boolean = false) {
        if (!opened) return
        opened = false
        stack.clear()
        box.animate().cancel()
        layer.animate().cancel()
        if (quiet || TabPrefs.reduce(act)) {
            layer.visibility = View.GONE
            layer.alpha = 1f; box.translationX = 0f; box.translationY = 0f
        } else {
            layer.animate().alpha(0f).setDuration(160).start()
            val a = if (side) box.animate().translationX(dp(400).toFloat()) else box.animate().translationY(act.resources.displayMetrics.heightPixels * 0.7f)
            a.setDuration(160).withEndAction {
                if (!opened) { layer.visibility = View.GONE; layer.alpha = 1f; box.translationX = 0f; box.translationY = 0f }
            }.start()
        }
        if (!quiet) onClosed?.invoke()
    }

    private fun render(resetScroll: Boolean) {
        val page = stack.lastOrNull() ?: return
        titleTv.text = page.title
        list.removeAllViews()
        page.build(Builder(this, list))
        if (resetScroll) scroll.scrollTo(0, 0)
    }

    /** Side (right) in landscape wide screens, bottom sheet otherwise. Insets keep it out of cutouts. */
    private fun layout() {
        val dm = act.resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        side = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && wDp >= 480f
        val r = dp(22).toFloat()
        val bg = GradientDrawable().apply { setColor(0xE61B1C20.toInt()) }
        val lp: FrameLayout.LayoutParams
        val sp: LinearLayout.LayoutParams
        if (side) {
            bg.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            lp = FrameLayout.LayoutParams(minOf((dm.widthPixels * 0.46f).toInt(), dp(380)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            scroll.maxH = 0
            sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            lp = FrameLayout.LayoutParams(minOf(dm.widthPixels, dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            scroll.maxH = (dm.heightPixels * 0.62f).toInt()
            sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        box.background = bg
        box.layoutParams = lp
        scroll.layoutParams = sp
        val ins = ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
        val right = if (side) (ins?.right ?: 0) else 0
        box.setPadding(0, dp(6) + (if (side) (ins?.top ?: 0) else 0), right, dp(8) + (ins?.bottom ?: 0))
    }
}

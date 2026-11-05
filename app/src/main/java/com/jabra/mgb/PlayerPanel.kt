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
import android.widget.SeekBar
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils
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

    /** Grid chip (P2). accentText = accent coloured text (saved custom values). onLong = optional long press action. onClick is LAST so a trailing lambda is the click. */
    class Chip(val label: String, val selected: Boolean = false, val accentText: Boolean = false, val onLong: (() -> Unit)? = null, val onClick: () -> Unit)

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

        /** P11: the next row added is the one the page scrolls to when it opens. */
        fun mark() { p.scrollMark = list.childCount }

        /** True when the panel sits on the right (landscape): pages may use more columns. */
        val wide: Boolean get() = p.side

        private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

        /** Chip grid, equal columns, 40 dp high pills. Selected = accent 26% fill + accent text. */
        fun chips(items: List<Chip>, cols: Int) {
            val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
            var i = 0
            while (i < items.size) {
                val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until cols) {
                    val lp = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    if (i + c < items.size) {
                        val ch = items[i + c]
                        r.addView(TextView(act).apply {
                            text = ch.label
                            textSize = 14f
                            gravity = Gravity.CENTER
                            maxLines = 1
                            setTextColor(if (ch.selected || ch.accentText) accent else Color.WHITE)
                            background = round(if (ch.selected) ColorUtils.setAlphaComponent(accent, 0x42) else 0x22FFFFFF, 20)
                            setOnClickListener { _ -> ch.onClick() }
                            val lg = ch.onLong
                            if (lg != null) setOnLongClickListener { v ->
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                lg()
                                true
                            }
                        }, lp)
                    } else r.addView(View(act), lp)
                }
                wrap.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                i += cols
            }
            add(wrap)
        }

        /**
         * Slider card: label + value, "-" / "+" step buttons, bar, min / max text. onChange = live while dragging (and on - / +),
         * onDone = finger lifted or button tapped (the page is rebuilt after it so chips show the new state).
         */
        fun slider(label: String, min: Float, max: Float, step: Float, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit, onDone: (Float) -> Unit) {
            val n = Math.round((max - min) / step)
            var cur = value.coerceIn(min, max)
            fun at(pr: Int) = min + pr * step
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(0x14FFFFFF, 16)
                setPadding(dp(16), dp(12), dp(16), dp(10))
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply { text = label; textSize = 14f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueTv = TextView(act).apply {
                text = fmt(cur); textSize = 14f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            head.addView(valueTv)
            card.addView(head)
            val bar = SeekBar(act).apply {
                this.max = n // P3.2: 'max' is also the Float parameter of slider(), so say this.max (SeekBar.max, Int)
                progress = Math.round((cur - min) / step)
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            }
            fun stepBtn(sym: String, d: Int) = TextView(act).apply {
                text = sym
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = round(0x14FFFFFF, 18)
                setOnClickListener {
                    val np = (bar.progress + d).coerceIn(0, n)
                    bar.progress = np
                    cur = at(np)
                    valueTv.text = fmt(cur)
                    onChange(cur)
                    onDone(cur)
                    p.refresh()
                }
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    cur = at(prog)
                    valueTv.text = fmt(cur)
                    onChange(cur)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { onDone(cur); p.refresh() }
            })
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(stepBtn("\u2212", -1), LinearLayout.LayoutParams(dp(36), dp(36)))
            line.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
            line.addView(stepBtn("+", 1), LinearLayout.LayoutParams(dp(36), dp(36)))
            card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            val ends = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            ends.addView(TextView(act).apply { text = fmt(min); textSize = 10.5f; setTextColor(0x80FFFFFF.toInt()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ends.addView(TextView(act).apply { text = fmt(max); textSize = 10.5f; setTextColor(0x80FFFFFF.toInt()) })
            card.addView(ends, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(42); rightMargin = dp(42) })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) })
        }
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
    internal var scrollMark = -1
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
        scrollMark = -1
        list.removeAllViews()
        page.build(Builder(this, list))
        if (resetScroll) {
            val m = scrollMark
            if (m >= 0) scroll.post { scroll.scrollTo(0, ((list.getChildAt(m)?.top ?: 0) - dp(104)).coerceAtLeast(0)) }
            else scroll.scrollTo(0, 0)
        }
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

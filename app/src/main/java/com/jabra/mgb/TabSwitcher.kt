package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

/**
 * S31: Thazir tab switcher (full-screen Dialog, replaces the old bottom sheet). Own design:
 * a top switch (Tabs | Private) with a 3-dot menu, a 2-column grid of cards (colour avatar made from the site name,
 * title, host, close), and a bottom bar: Done, big + (new tab in the shown mode), Close all.
 * No tab groups yet. Tab thumbnails are not taken (cards show the avatar instead).
 */
object TabSwitcher {
    private const val TAG = "Thazir.switcher"
    private var current: Screen? = null

    fun show(act: Activity, tm: TabManager) {
        if (act.isFinishing || act.isDestroyed) return
        try { current?.dismiss() } catch (_: Exception) { }
        try {
            val s = Screen(act, tm)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    private class Screen(private val act: Activity, private val tm: TabManager) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var priv = tm.active?.priv == true
        private lateinit var seg: LinearLayout
        private lateinit var grid: LinearLayout
        private lateinit var empty: TextView

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(res: Int) = act.getColor(res)

        init { setOnDismissListener { if (current === this) current = null } }

        private fun round(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(c(R.color.mg_bg)))
            }
        }

        private fun count(p: Boolean) = tm.allTabs().count { it.priv == p }

        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(c(R.color.mg_bg))
            }
            // top: switch + menu
            val top = FrameLayout(act)
            seg = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = round(c(R.color.mg_surface_variant), 24)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            top.addView(seg, FrameLayout.LayoutParams(WRAP, dp(48), Gravity.CENTER))
            top.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more)
                setColorFilter(c(R.color.mg_text))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Menu"
                setOnClickListener { menu() }
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
            root.addView(top, LinearLayout.LayoutParams(MATCH, dp(72)))

            // body
            grid = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(16))
            }
            val sv = ScrollView(act)
            sv.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
            val bodyFrame = FrameLayout(act)
            bodyFrame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            empty = TextView(act).apply {
                text = "No private tabs.\nTap + to open one."
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(c(R.color.mg_text_dim))
                visibility = View.GONE
            }
            bodyFrame.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(bodyFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))

            // bottom bar
            val bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(12))
            }
            fun side(res: Int, desc: String, on: () -> Unit) = ImageView(act).apply {
                setImageResource(res)
                setColorFilter(c(R.color.mg_text))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = desc
                setOnClickListener { on() }
            }
            bar.addView(side(R.drawable.ic_back, "Done") { dismiss() }, LinearLayout.LayoutParams(dp(56), dp(52)))
            bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_add)
                setColorFilter(c(R.color.mg_on_primary))
                scaleType = ImageView.ScaleType.CENTER
                background = round(c(R.color.mg_primary), 26)
                contentDescription = "New tab"
                setOnClickListener { newTab(priv) }
            }, LinearLayout.LayoutParams(dp(96), dp(52)))
            bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(side(R.drawable.ic_trash, "Close all") { closeAll() }, LinearLayout.LayoutParams(dp(56), dp(52)))
            root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

            setContentView(root)
            render()
        }

        private fun newTab(p: Boolean) {
            tm.newTab(TabManager.HOME_PAGE, 0, true, p)
            dismiss()
        }

        private fun menu() {
            MaterialAlertDialogBuilder(act)
                .setItems(arrayOf("New tab", "New private tab", if (priv) "Close all private tabs" else "Close all tabs")) { _, i ->
                    when (i) {
                        0 -> newTab(false)
                        1 -> newTab(true)
                        else -> closeAll()
                    }
                }
                .show()
        }

        private fun closeAll() {
            val mine = tm.allTabs().filter { it.priv == priv }
            if (mine.isEmpty()) return
            MaterialAlertDialogBuilder(act)
                .setMessage("Close " + mine.size + (if (priv) " private" else "") + " tab" + (if (mine.size == 1) "" else "s") + "?")
                .setPositiveButton("Close") { _, _ ->
                    for (t in mine) tm.close(t)
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun segItem(label: String, icon: Int, sel: Boolean, on: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = if (sel) round(if (priv) c(R.color.mg_primary) else c(R.color.mg_bg), 20) else null
                setOnClickListener { on() }
            }
            val fg = if (sel && priv) c(R.color.mg_on_primary) else if (sel) c(R.color.mg_text) else c(R.color.mg_text_dim)
            row.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
            row.addView(TextView(act).apply {
                text = label
                textSize = 14f
                setTextColor(fg)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            return row
        }

        private fun render() {
            seg.removeAllViews()
            seg.addView(segItem(count(false).toString(), R.drawable.ic_list, !priv) { priv = false; render() }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(count(true).toString(), R.drawable.ic_lock, priv) { priv = true; render() }, LinearLayout.LayoutParams(WRAP, dp(40)))
            grid.removeAllViews()
            val list = tm.allTabs().filter { it.priv == priv }
            empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            var i = 0
            while (i < list.size) {
                val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0..1) {
                    val t = list.getOrNull(i + k)
                    val lp = LinearLayout.LayoutParams(0, dp(132), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    line.addView(if (t != null) card(t) else View(act), lp)
                }
                grid.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
                i += 2
            }
        }

        private fun card(t: TabManager.Tab): View {
            val host = if (t.home) "" else Util.host(t.url)
            val title = if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { host }.ifBlank { "New tab" }
            val isCur = t === tm.active
            val box = FrameLayout(act).apply {
                background = round(c(R.color.mg_surface), 22, if (isCur) 2 else 0, c(R.color.mg_primary))
                setOnClickListener { tm.switchTo(t); dismiss() }
            }
            // avatar: letter on a colour made from the host
            val seed = if (t.home) "thazir" else host
            val hue = (abs(seed.hashCode()) % 360).toFloat()
            val letter = (if (t.home) "T" else host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?")
            box.addView(TextView(act).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.72f))) }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
            box.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(c(R.color.mg_text_dim))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Close tab"
                setOnClickListener { tm.close(t); render() }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(4), 0) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = title
                textSize = 14f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(TextView(act).apply {
                text = if (t.home) "Home" else host
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text_dim))
            })
            box.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(12)) })
            return box
        }
    }
}

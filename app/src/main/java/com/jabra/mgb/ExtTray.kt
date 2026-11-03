package com.jabra.mgb

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.io.File

/**
 * S10: Thazir Extensions tray. Opened from the 3-dot menu (puzzle button in the bottom row).
 *  - bottom sheet, 4-column grid of the extensions that are ON: icon (manifest icon, else a colour tile with the first letter) + 2-line name
 *  - tap = popup page of the extension (else options page, else a toast) in the extension window; the tray closes first
 *  - "Manage Extensions" = ExtListScreen (add / on-off / remove), header button does the same
 *  - Nothing is installed or changed here; it only reads ExtRegistry and opens existing screens
 */
object ExtTray {
    private const val TAG = "MGB.exttray"
    private const val COLS = 4

    fun show(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        try {
            ExtRegistry.init(act)
            build(act)
        } catch (e: Exception) {
            Log.e(TAG, "show failed", e)
            Util.toast(act, "Could not open Extensions")
        }
    }

    private fun build(act: Activity) {
        val dlg = BottomSheetDialog(act)
        fun dp(v: Int) = Util.dp(act, v)
        fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        val cAccent = act.getColor(R.color.mg_primary)

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        col.addView(View(act).apply { background = round(cLine, 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "Thazir Extensions"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cAccent)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_H, 1f))
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(cText)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Manage extensions"
            setOnClickListener { dlg.dismiss(); ExtListScreen.open(act) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(head, LinearLayout.LayoutParams(MATCH_W, dp(44)))

        val list = ExtRegistry.all().filter { it.enabled }
        val grid = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cCard, 22)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        if (list.isEmpty()) {
            grid.addView(TextView(act).apply {
                text = if (ExtRegistry.all().isEmpty()) "No extensions yet" else "All extensions are off"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(cDim)
            }, LinearLayout.LayoutParams(MATCH_W, dp(96)))
        } else {
            var row: LinearLayout? = null
            val tiles = ArrayList<View>()
            list.forEachIndexed { i, e ->
                if (i % COLS == 0) {
                    row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row, LinearLayout.LayoutParams(MATCH_W, WRAP_H))
                }
                val t = tile(act, e) { dlg.dismiss(); openExt(act, e) }
                tiles.add(t)
                row!!.addView(t, LinearLayout.LayoutParams(0, WRAP_H, 1f))
            }
            val rem = (COLS - list.size % COLS) % COLS // empty cells keep the last row aligned
            repeat(rem) { row!!.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f)) }
            dlg.setOnShowListener {
                val ease = android.view.animation.DecelerateInterpolator(1.6f)
                tiles.forEachIndexed { i, v ->
                    v.alpha = 0f; v.scaleX = 0.85f; v.scaleY = 0.85f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(40L + i * 30L).setDuration(220).setInterpolator(ease).start()
                }
                dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                    GradientDrawable().apply {
                        setColor(act.getColor(R.color.mg_bg))
                        val r = dp(28).toFloat()
                        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    }
            }
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH_W, WRAP_H).apply { topMargin = dp(6) })

        col.addView(TextView(act).apply {
            text = "Manage Extensions"
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cAccent)
            background = round(cCard, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener { dlg.dismiss(); ExtListScreen.open(act) }
        }, LinearLayout.LayoutParams(MATCH_W, dp(48)).apply { topMargin = dp(12) })
        col.addView(TextView(act).apply {
            text = "Powered by Thazir"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(cDim)
            setPadding(0, dp(10), 0, 0)
        })

        val sv = NestedScrollView(act)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(col, ViewGroup.LayoutParams(MATCH_W, WRAP_H))
        dlg.setContentView(sv)
        dlg.behavior.skipCollapsed = true
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }

    private const val MATCH_W = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP_H = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun openExt(act: Activity, e: ExtRegistry.Info) {
        val page = e.popup ?: e.options
        if (page == null) { Util.toast(act, e.name + " has no popup"); return }
        if (!ExtScreen.openExtPage(act, e.id, page)) Util.toast(act, "Could not open " + e.name)
    }

    private fun tile(act: Activity, e: ExtRegistry.Info, onTap: () -> Unit): View {
        fun dp(v: Int) = Util.dp(act, v)
        val t = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            isClickable = true
            isFocusable = true
            contentDescription = e.name
            setOnClickListener { onTap() }
        }
        val size = dp(48)
        val bmp = iconOf(e)
        if (bmp != null) {
            t.addView(ImageView(act).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(size, size))
        } else {
            t.addView(TextView(act).apply { // no icon: colour tile from the id, first letter of the name
                text = e.name.trim().take(1).uppercase().ifEmpty { "?" }
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    setColor(tint(e.id))
                    cornerRadius = dp(14).toFloat()
                }
            }, LinearLayout.LayoutParams(size, size))
        }
        t.addView(TextView(act).apply {
            text = e.name
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(act.getColor(R.color.mg_text))
        }, LinearLayout.LayoutParams(MATCH_W, WRAP_H).apply { topMargin = dp(6) })
        return t
    }

    private fun tint(id: String): Int {
        val h = ((id.hashCode() and 0x7fffffff) % 360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(h, 0.55f, 0.72f))
    }

    /** Biggest png / jpg / webp icon from the manifest (icons, else action / browser_action default_icon). null = none usable (svg etc.). */
    private fun iconOf(e: ExtRegistry.Info): Bitmap? {
        return try {
            val m = JSONObject(File(e.dir, "manifest.json").readText(Charsets.UTF_8).removePrefix("﻿"))
            var icons = m.optJSONObject("icons")
            if (icons == null) {
                val a = m.optJSONObject("action") ?: m.optJSONObject("browser_action")
                val d = a?.opt("default_icon")
                icons = if (d is JSONObject) d else if (d is String) JSONObject().put("48", d) else null
            }
            if (icons == null) return null
            val keys = icons.keys().asSequence().sortedByDescending { it.toIntOrNull() ?: 0 }.toList()
            for (k in keys) {
                val rel = icons.optString(k).trimStart('/')
                if (rel.isBlank() || rel.contains("..") || rel.endsWith(".svg", true)) continue
                val f = File(e.dir, rel)
                if (!f.isFile || f.length() > 2_000_000) continue
                val o = BitmapFactory.Options().apply { inSampleSize = 1 }
                val b = BitmapFactory.decodeFile(f.path, o)
                if (b != null) return b
            }
            null
        } catch (ex: Exception) {
            null
        }
    }
}

package com.jabra.mgb

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.282: Brave-style site permissions. Settings > Privacy > Site permissions opens one sheet:
 * a card per type (Camera / Microphone / Location) with "N allowed, M blocked" and a default pill (Ask first / Blocked);
 * tap a card for the sites of that type, tap a status chip to flip Allowed / Blocked, tap x to forget the site.
 * Data stays in SitePerms (prefs mgb_perm "host|what"); the default lives in prefs mgb_perm_def (what -> 0 = block).
 */
object SitePermsUi {
    private val CATS = listOf(
        Triple("camera", "Camera", R.drawable.ic_perm_camera),
        Triple("microphone", "Microphone", R.drawable.ic_perm_mic),
        Triple("location", "Location", R.drawable.ic_perm_location)
    )

    fun show(act: FragmentActivity, done: () -> Unit) {
        try { Sheet(act, done).show() } catch (e: Exception) { android.util.Log.e("MGB.perms", "site ui", e) }
    }

    private fun has(what: String, cat: String) = what.split(" and ").contains(cat)

    private class Sheet(private val act: FragmentActivity, private val done: () -> Unit) : BottomSheetDialog(act) {
        private var cat: String? = null
        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(id: Int) = act.getColor(id)
        private fun round(color: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), sc)
        }
        private fun tint(id: Int, a: Float) = ColorUtils.blendARGB(c(R.color.mg_surface), c(id), a)

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setOnShowListener { try { behavior.skipCollapsed = true; behavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) { } }
            render()
        }

        private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        private fun render() {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
            val k = cat
            if (k == null) overview(col) else sites(col, k)
            val cap = object : LinearLayout(act) { // 560 dp cap on wide screens
                override fun onMeasure(w: Int, h: Int) {
                    val size = MeasureSpec.getSize(w); val cp = dp(560)
                    super.onMeasure(if (size > cp) MeasureSpec.makeMeasureSpec(cp, MeasureSpec.EXACTLY) else w, h)
                }
            }.apply { orientation = LinearLayout.VERTICAL; addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            setContentView(ScrollView(act).apply {
                addView(cap, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            })
        }

        private fun overview(col: LinearLayout) {
            col.addView(text("Site permissions", 20f, c(R.color.mg_text), true))
            col.addView(text("Choose what sites may ask for. Tap a type to see the sites.", 12f, c(R.color.mg_text_dim)).apply { setPadding(0, dp(2), 0, dp(12)) })
            val all = SitePerms.entries(act)
            for ((key, label, ic) in CATS) {
                val mine = all.filter { has(it.second, key) }
                val allowed = mine.count { it.third }
                val blocked = mine.size - allowed
                val card = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = round(c(R.color.mg_surface), 20, 1, c(R.color.mg_outline))
                    minimumHeight = dp(72); setPadding(dp(12), dp(8), dp(10), dp(8))
                    isClickable = true; contentDescription = "$label, ${mine.size} saved"
                    setOnClickListener { cat = key; render() }
                }
                card.addView(ImageView(act).apply {
                    setImageResource(ic); setColorFilter(c(R.color.mg_primary)); scaleType = ImageView.ScaleType.CENTER
                    background = round(tint(R.color.mg_primary, 0.14f), 20)
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(text(label, 15f, c(R.color.mg_text), true))
                mid.addView(text(if (mine.isEmpty()) "No saved sites" else "$allowed allowed · $blocked blocked", 12f, c(R.color.mg_text_dim)))
                card.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val blockDef = SitePerms.blockedByDefault(act, key)
                card.addView(text(if (blockDef) "Blocked" else "Ask first", 12f, if (blockDef) c(R.color.mg_error) else c(R.color.mg_primary), true).apply {
                    gravity = Gravity.CENTER
                    background = round(tint(if (blockDef) R.color.mg_error else R.color.mg_primary, 0.14f), 16)
                    setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(36)
                    contentDescription = "Default for $label: ${if (blockDef) "blocked" else "ask first"}. Tap to change"
                    setOnClickListener {
                        SitePerms.setDefaultBlock(act, key, !blockDef)
                        Util.toast(act, if (!blockDef) "$label: new sites are blocked" else "$label: new sites ask first")
                        render()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
                col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
            col.addView(text("Default: Ask first = the site asks you (as before). Blocked = never ask, always refuse, unless you allowed that site.", 11f, c(R.color.mg_text_dim)).apply { setPadding(dp(4), dp(2), dp(4), dp(8)) })
            if (all.isNotEmpty()) col.addView(text("Reset all saved answers", 14f, c(R.color.mg_error), true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(48)
                setOnClickListener {
                    MaterialAlertDialogBuilder(act).setMessage("Forget all saved site permissions?")
                        .setPositiveButton("Reset") { _, _ -> all.forEach { SitePerms.set(act, it.first, it.second, null) }; done(); render() }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        }

        private fun sites(col: LinearLayout, key: String) {
            val info = CATS.first { it.first == key }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(c(R.color.mg_text)); scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Back"; setOnClickListener { cat = null; render() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })
            head.addView(text(info.second, 20f, c(R.color.mg_text), true))
            col.addView(head)
            val mine = SitePerms.entries(act).filter { has(it.second, key) }
            if (mine.isEmpty()) col.addView(text("No sites yet. When a site asks for ${info.second.lowercase()}, tick \"Remember for this site\" and it appears here.", 13f, c(R.color.mg_text_dim)).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) })
            for ((host, what, ok) in mine) {
                val line = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = round(c(R.color.mg_surface), 16, 1, c(R.color.mg_outline))
                    minimumHeight = dp(56); setPadding(dp(14), dp(6), dp(4), dp(6))
                }
                val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(text(host, 15f, c(R.color.mg_text)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (what != key) mid.addView(text(what, 11f, c(R.color.mg_text_dim)))
                line.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val tone = if (ok) R.color.mg_success else R.color.mg_error
                line.addView(text(if (ok) "Allowed" else "Blocked", 12f, c(tone), true).apply {
                    gravity = Gravity.CENTER; background = round(tint(tone, 0.16f), 16)
                    setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(36)
                    contentDescription = "$host ${if (ok) "allowed" else "blocked"}. Tap to change"
                    setOnClickListener { SitePerms.set(act, host, what, !ok); done(); render() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
                line.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_close); setColorFilter(c(R.color.mg_text_dim)); scaleType = ImageView.ScaleType.CENTER
                    contentDescription = "Forget $host"; setOnClickListener { SitePerms.set(act, host, what, null); done(); render() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
    }
}

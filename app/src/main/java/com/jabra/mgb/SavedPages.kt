package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** T4: full-screen "Saved pages" (replaces the T3 list dialog). Skeleton copied from SiteSettings.open. No thumbnails (safe cut). */
object SavedPages {

    fun open(act: Activity, tabs: TabManager) {
        fun dp(n: Int) = Util.dp(act, n)
        fun col(id: Int) = act.getColor(id)
        val dlg = Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.mg_bg)) }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(col(R.color.mg_text))
            setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Back"
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        head.addView(TextView(act).apply {
            text = "Saved pages"; textSize = 20f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(col(R.color.mg_text)); setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val search = EditText(act).apply {
            hint = "Search saved pages"; textSize = 15f; setSingleLine(true)
            setTextColor(col(R.color.mg_text)); setHintTextColor(col(R.color.mg_text_dim))
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply { setColor(col(R.color.mg_surface)); cornerRadius = dp(28).toFloat() }
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(24)) }
        root.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        fun card() = GradientDrawable().apply {
            setColor(col(R.color.mg_surface)); cornerRadius = dp(20).toFloat()
            setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(col(R.color.mg_outline), 0x99))
        }
        lateinit var refresh: () -> Unit

        fun rename(e: PageSave.Entry) {
            val et = EditText(act).apply { setText(e.title); setSingleLine(true); setSelectAllOnFocus(true); setTextColor(col(R.color.mg_text)) }
            val box = FrameLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
            MaterialAlertDialogBuilder(act).setTitle("Rename").setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isNotEmpty()) { PageSave.rename(act, e.id, n); refresh() }
                }
                .setNegativeButton("Cancel", null).show()
        }

        fun share(e: PageSave.Entry) {
            val src = PageSave.file(act, e.id)
            if (!src.exists()) { Util.toast(act, "File is missing"); return }
            try {
                val d = File(act.cacheDir, "updates"); d.mkdirs()
                val f = File(d, (e.title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifEmpty { "page" }) + ".mht")
                src.copyTo(f, true)
                val u = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/x-mimearchive").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (ex: Exception) { Util.toast(act, "Cannot share") }
        }

        fun delete(e: PageSave.Entry) {
            MaterialAlertDialogBuilder(act).setTitle("Delete saved page?").setMessage(e.title)
                .setPositiveButton("Delete") { _, _ -> PageSave.remove(act, e.id); refresh() }
                .setNegativeButton("Cancel", null).show()
        }

        fun menu(e: PageSave.Entry) {
            val items = ArrayList<ToolsSheet.Item>()
            items.add(ToolsSheet.Item(R.drawable.ic_open, "Open in new tab", "Offline copy") { dlg.dismiss(); PageSave.open(act, tabs, e) })
            items.add(ToolsSheet.Item(R.drawable.ic_edit, "Rename", "Change the title") { rename(e) })
            items.add(ToolsSheet.Item(R.drawable.ic_share, "Share file", ".mht archive") { share(e) })
            if (e.url.startsWith("http")) items.add(ToolsSheet.Item(R.drawable.ic_ext, "Open original site", Util.host(e.url)) { dlg.dismiss(); tabs.newTab(e.url) })
            items.add(ToolsSheet.Item(R.drawable.ic_trash, "Delete", "Remove the file") { delete(e) })
            ToolsSheet.show(act, e.title, Util.host(e.url), listOf(ToolsSheet.Group("Actions", items)))
        }

        refresh = refresh@{
            list.removeAllViews()
            val all = PageSave.list(act)
            val q = search.text.toString().trim().lowercase()
            val shown = all.filter { q.isEmpty() || it.title.lowercase().contains(q) || Util.host(it.url).lowercase().contains(q) }
            // header card: count + total size + Delete all
            val hc = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12)); background = card()
            }
            hc.addView(TextView(act).apply {
                text = all.size.toString() + (if (all.size == 1) " page, " else " pages, ") + Util.fmtBytes(all.sumOf { it.size })
                textSize = 15f; setTextColor(col(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (all.isNotEmpty()) hc.addView(TextView(act).apply {
                text = "Delete all"; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(col(R.color.mg_primary)); setPadding(dp(8), dp(8), dp(4), dp(8))
                setOnClickListener {
                    MaterialAlertDialogBuilder(act).setTitle("Delete all saved pages?").setMessage(all.size.toString() + " files will be removed.")
                        .setPositiveButton("Delete all") { _, _ -> PageSave.removeAll(act); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            list.addView(hc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            if (shown.isEmpty()) {
                list.addView(TextView(act).apply {
                    text = if (all.isEmpty()) "No saved pages yet. Open a web page and use Save page in the menu." else "No page matches."
                    textSize = 14f; setTextColor(col(R.color.mg_text_dim)); setPadding(dp(6), dp(12), dp(6), 0)
                })
                return@refresh
            }
            for (e in shown) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(6), dp(10)); background = card()
                    minimumHeight = dp(56)
                    setOnClickListener { dlg.dismiss(); PageSave.open(act, tabs, e) }
                    setOnLongClickListener { menu(e); true }
                }
                val col1 = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                col1.addView(TextView(act).apply { text = e.title; textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(col(R.color.mg_text)) })
                col1.addView(TextView(act).apply {
                    text = Util.host(e.url) + " · " + df.format(Date(e.time)) + " · " + Util.fmtBytes(e.size)
                    textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(col(R.color.mg_text_dim))
                })
                row.addView(col1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_more); setColorFilter(col(R.color.mg_text_dim))
                    setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "More"
                    setOnClickListener { menu(e) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
        refresh()
        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.show()
    }
}

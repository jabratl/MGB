package com.jabra.mgb

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/** S42: link menu > Preview page. A small look at the page in a floating window; Open moves it to a new tab. */
object PagePreview {
    fun show(ctx: Context, url: String, onOpen: () -> Unit) {
        if (!url.startsWith("http")) return
        fun dp(v: Int) = Util.dp(ctx, v)
        val dlg = Dialog(ctx, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(ctx.getColor(R.color.mg_bg)); cornerRadius = dp(16).toFloat() }
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
        }
        val title = TextView(ctx).apply {
            text = Util.host(url)
            setTextColor(ctx.getColor(R.color.mg_text))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun pill(label: String, click: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextColor(ctx.getColor(R.color.mg_primary))
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { click() }
        }
        val web = WebView(ctx)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return true
                return !(u.startsWith("http://") || u.startsWith("https://")) // no app links from a preview
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, t: String?) { if (!t.isNullOrBlank()) title.text = t }
        }
        head.addView(title)
        head.addView(pill("Open") { dlg.dismiss(); onOpen() })
        head.addView(pill("Close") { dlg.dismiss() })
        root.addView(head)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg.setContentView(root)
        dlg.setOnDismissListener {
            try { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) { }
        }
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = ctx.resources.displayMetrics
            setLayout((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.78f).toInt())
            setGravity(Gravity.BOTTOM)
            attributes = attributes.also { it.verticalMargin = 0.03f; it.dimAmount = 0.5f }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.show()
        web.loadUrl(url)
    }
}

package com.jabra.mgb

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 0.0.281: Settings > General > Device permissions. One sheet that shows every Android permission the app can use, whether it is
 * allowed, and a button to allow it (Android's own question), or to open the right Android settings page when Android will not
 * ask again. The sheet refreshes itself when you come back from Android settings. No prefs of the app are touched
 * (the "asked" memory lives in its own file mgb_perm, so Settings reset does not matter).
 */
object DevicePerms {
    private const val SP = "mgb_perm"
    private const val REQ = 7101

    fun open(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        try { Sheet(act).show() } catch (e: Exception) { android.util.Log.e("MGB.perms", "open", e) }
    }

    private class Item(val title: String, val hint: String, val ok: Boolean, val action: String?, val run: () -> Unit)

    private fun declared(a: Context): Set<String> = try {
        a.packageManager.getPackageInfo(a.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    private fun appSettings(a: Activity) {
        try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName))) }
        catch (e: Exception) { Util.toast(a, "Cannot open Android settings") }
    }

    private fun runtime(a: Activity, title: String, hint: String, perm: String, dec: Set<String>): Item {
        val ok = ContextCompat.checkSelfPermission(a, perm) == PackageManager.PERMISSION_GRANTED
        if (!ok && perm !in dec) return Item(title, "$hint (this build does not include it)", false, null) { }
        return Item(title, hint, ok, "Allow") {
            val sp = a.getSharedPreferences(SP, Context.MODE_PRIVATE)
            val asked = sp.getBoolean(perm, false)
            if (asked && !ActivityCompat.shouldShowRequestPermissionRationale(a, perm)) appSettings(a) // Android will not ask again
            else { sp.edit().putBoolean(perm, true).apply(); ActivityCompat.requestPermissions(a, arrayOf(perm), REQ) }
        }
    }

    private fun items(a: Activity): List<Item> {
        val dec = declared(a)
        val out = ArrayList<Item>()
        if (Build.VERSION.SDK_INT >= 33) out.add(runtime(a, "Notifications", "Download progress, finished downloads, music controls, and website notifications you allowed (only while Thazir is open)", Manifest.permission.POST_NOTIFICATIONS, dec))
        out.add(runtime(a, "Camera", "Video calls and taking a photo for a post on websites", Manifest.permission.CAMERA, dec))
        out.add(runtime(a, "Microphone", "Voice calls and voice messages on websites", Manifest.permission.RECORD_AUDIO, dec))
        out.add(runtime(a, "Location", "Maps and \"near me\" on websites (each site still asks once)", Manifest.permission.ACCESS_FINE_LOCATION, dec))
        if (Build.VERSION.SDK_INT < 29) out.add(runtime(a, "Storage", "Save downloads (Android 9 and older)", Manifest.permission.WRITE_EXTERNAL_STORAGE, dec))
        if (Build.VERSION.SDK_INT >= 26) {
            val ok = a.packageManager.canRequestPackageInstalls()
            out.add(Item("Install apps", "Install an .apk you downloaded, and app updates", ok, "Open") {
                try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
                catch (e: Exception) { appSettings(a) }
            })
        }
        if (Build.VERSION.SDK_INT >= 23) {
            val ok = (a.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(a.packageName) == true
            out.add(Item("Battery: unrestricted", "Long downloads and music keep going with the screen off. Choose Thazir in the list", ok, "Open") {
                try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { appSettings(a) }
            })
        }
        return out
    }

    private class Sheet(private val act: Activity) : BottomSheetDialog(act) {
        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(id: Int) = act.getColor(id)

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setOnShowListener {
                try { behavior.skipCollapsed = true; behavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) { }
            }
            render()
        }

        // back from the Android settings page or from Android's question: show the new state
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus && isShowing) render()
        }

        private fun render() {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(16), dp(14), dp(18))
            }
            col.addView(TextView(act).apply {
                text = "Device permissions"
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(c(R.color.mg_text))
            })
            col.addView(TextView(act).apply {
                text = "Tap Allow and Android asks. Nothing is switched on by itself."
                textSize = 12f
                setTextColor(c(R.color.mg_text_dim))
                setPadding(0, dp(2), 0, dp(10))
            })
            val list = items(act)
            val real = list.filter { it.ok || it.action != null } // 0.0.282: summary counts what this build can use
            val okN = real.count { it.ok }
            col.addView(TextView(act).apply {
                text = "$okN of ${real.size} allowed"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(c(R.color.mg_text))
            })
            col.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(c(R.color.mg_surface_variant)); cornerRadius = dp(3).toFloat() }
                if (real.isNotEmpty() && okN > 0) addView(View(act).apply { background = GradientDrawable().apply { setColor(c(R.color.mg_success)); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(0, dp(6), okN.toFloat()))
                if (real.size > okN) addView(View(act), LinearLayout.LayoutParams(0, dp(6), (real.size - okN).toFloat()))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6); bottomMargin = dp(10) })
            for (it in list) col.addView(row(it))
            col.addView(TextView(act).apply {
                text = "Open Android app settings"
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(c(R.color.mg_primary))
                minimumHeight = dp(48)
                setOnClickListener { appSettings(act) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            val cap = object : LinearLayout(act) { // 560 dp cap on wide screens
                override fun onMeasure(w: Int, h: Int) {
                    val size = MeasureSpec.getSize(w)
                    val cp = dp(560)
                    super.onMeasure(if (size > cp) MeasureSpec.makeMeasureSpec(cp, MeasureSpec.EXACTLY) else w, h)
                }
            }.apply { orientation = LinearLayout.VERTICAL; addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            setContentView(ScrollView(act).apply {
                isFillViewport = false
                addView(cap, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            })
        }

        private fun row(it: Item): View {
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(10), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { setColor(c(R.color.mg_surface)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), c(R.color.mg_outline)) } // 0.0.282: card rows
            }
            val ic = when {
                it.title.startsWith("Notif") -> R.drawable.ic_perm_bell
                it.title == "Camera" -> R.drawable.ic_perm_camera
                it.title == "Microphone" -> R.drawable.ic_perm_mic
                it.title == "Location" -> R.drawable.ic_perm_location
                it.title.startsWith("Battery") -> R.drawable.ic_clock
                else -> R.drawable.ic_download
            }
            line.addView(android.widget.ImageView(act).apply {
                setImageResource(ic)
                setColorFilter(if (it.ok) c(R.color.mg_success) else c(R.color.mg_text_dim))
                scaleType = android.widget.ImageView.ScaleType.CENTER
                background = GradientDrawable().apply { setColor(androidx.core.graphics.ColorUtils.blendARGB(c(R.color.mg_surface), if (it.ok) c(R.color.mg_success) else c(R.color.mg_text_dim), 0.14f)); cornerRadius = dp(20).toFloat() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(act).apply { text = it.title; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(c(R.color.mg_text)) })
            box.addView(TextView(act).apply { text = it.hint; textSize = 12f; maxLines = 3; setTextColor(c(R.color.mg_text_dim)) })
            line.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (it.ok) {
                line.addView(TextView(act).apply {
                    text = "Allowed"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(c(R.color.mg_success))
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(androidx.core.graphics.ColorUtils.blendARGB(c(R.color.mg_surface), c(R.color.mg_success), 0.16f)); cornerRadius = dp(16).toFloat() }
                    minimumHeight = dp(32)
                    setPadding(dp(12), 0, dp(12), 0)
                    contentDescription = "${it.title} allowed. Tap to open Android settings" // 0.0.282: manage / turn off in Android settings
                    setOnClickListener { _ -> appSettings(act) }
                })
            } else if (it.action != null) {
                line.addView(TextView(act).apply {
                    text = it.action
                    textSize = 13f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(c(R.color.mg_on_primary))
                    background = GradientDrawable().apply { setColor(c(R.color.mg_primary)); cornerRadius = dp(18).toFloat() }
                    minimumHeight = dp(40)
                    minimumWidth = dp(72)
                    setPadding(dp(16), 0, dp(16), 0)
                    setOnClickListener { _ -> it.run() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
            } else {
                line.addView(TextView(act).apply { text = "Not in this build"; textSize = 12f; setTextColor(c(R.color.mg_text_dim)); setPadding(dp(8), 0, dp(4), 0) })
            }
            return line
        }
    }
}

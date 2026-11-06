package com.jabra.mgb

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton

/**
 * BIOMETRIC LOCK FOR SOCIAL TABS (0.0.260, D7). Switch sm_lock (default OFF) in mgb_ui.
 * A full-screen cover + the system fingerprint / face / screen-lock prompt is shown when a SOCIAL tab (Social mode ON, site switch ON)
 * is in front and the app was away for 1 minute or more (also at cold start). Normal tabs are never locked, but while the cover is
 * shown nothing else is reachable (Back = leave the app, same as AppLock). Skipped when App lock (S45) is ON: that one already covers everything.
 * Own small code + own pref; Vault* / PasswordStore / AppLock are NOT touched. No screen lock on the phone = the switch cannot be turned ON,
 * and a removed screen lock never traps the person (needs = false).
 */
object SocialLock {
    private const val KEY = "sm_lock"
    private const val DELAY_MS = 60_000L

    private var needs = true       // true at cold start and after the person was away DELAY_MS or more
    private var leftAt = 0L
    private var dialog: Dialog? = null
    private var prompting = false

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)

    /** ON only when the phone has a screen lock / biometric; returns false (and stays OFF) otherwise. */
    fun setOn(c: Context, v: Boolean): Boolean {
        if (v && !AppLock.available(c)) { Util.toast(c, "Set a screen lock or fingerprint on the phone first"); return false }
        sp(c).edit().putBoolean(KEY, v).apply()
        if (v) needs = true
        return true
    }

    private fun isSocial(c: Context, tab: TabManager.Tab?): Boolean {
        if (tab == null || !SocialMode.on(c)) return false
        val p = SocialProfiles.of(tab.url) ?: return false
        return SocialProfiles.siteOn(c, p.id)
    }

    /** MainActivity.onStart. */
    fun onStart(act: FragmentActivity, tab: TabManager.Tab?) {
        if (!on(act)) { dismiss(); return }
        if (!needs && leftAt > 0L && SystemClock.elapsedRealtime() - leftAt >= DELAY_MS) needs = true
        check(act, tab)
    }

    /** MainActivity.onStop: the cover is rebuilt on the next onStart (needs stays true until a real unlock). */
    fun onStop() {
        leftAt = SystemClock.elapsedRealtime()
        dismiss()
    }

    /** MainActivity.onActiveChanged (switching to a social tab while locked shows the cover). */
    fun check(act: FragmentActivity, tab: TabManager.Tab?) {
        try {
            if (!on(act) || !needs) return
            if (AppLock.on(act)) return // the whole app is already locked by App lock
            if (!isSocial(act, tab)) return
            if (act.isFinishing || act.isDestroyed || dialog?.isShowing == true) return
            if (!AppLock.available(act)) { needs = false; return } // screen lock removed on the phone: do not trap the person
            show(act)
        } catch (e: Exception) {
            android.util.Log.w("Thazir.lock", "check failed", e)
        }
    }

    private fun dismiss() {
        try { dialog?.dismiss() } catch (e: Exception) { }
        dialog = null
    }

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK

    private fun authenticate(act: FragmentActivity) {
        if (prompting) return
        prompting = true
        try {
            val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { prompting = false; needs = false; dismiss() }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { prompting = false } // cover stays, button tries again
            })
            val b = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock social tabs").setAllowedAuthenticators(authenticators())
            if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText("Cancel")
            prompt.authenticate(b.build())
        } catch (e: Exception) {
            prompting = false
        }
    }

    private fun show(act: FragmentActivity) {
        val dp = { v: Int -> Util.dp(act, v) }
        val d = object : Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() { act.moveTaskToBack(true) }
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
                if (keyCode == KeyEvent.KEYCODE_BACK) { act.moveTaskToBack(true); true } else super.onKeyDown(keyCode, event)
        }
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(act.getColor(R.color.mg_bg))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        col.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(act.getColor(R.color.mg_primary))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        col.addView(TextView(act).apply {
            text = "Social tabs are locked"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val btn = MaterialButton(act).apply {
            text = "Unlock"
            setOnClickListener { authenticate(act) }
        }
        col.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(24) })
        d.setContentView(col)
        dialog = d
        d.show()
        authenticate(act)
    }
}

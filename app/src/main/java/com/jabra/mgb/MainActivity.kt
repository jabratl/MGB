package com.jabra.mgb

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** PLACEHOLDER (S0). Replaced in S2 by the real browser screen. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Media Grabber Browser\nv${BuildConfig.VERSION_NAME}"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(getColor(R.color.mg_text))
            setBackgroundColor(getColor(R.color.mg_bg))
        }
        setContentView(tv)
    }
}

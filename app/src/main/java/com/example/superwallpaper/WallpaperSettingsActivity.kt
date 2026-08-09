package com.example.superwallpaper

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class WallpaperSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Super Wallpaper Settings"
            textSize = 22f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        val glowCheckbox = CheckBox(this).apply {
            text = "Enable Dynamic Lighting"
            isChecked = prefs.getBoolean("pref_enable_glow", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("pref_enable_glow", isChecked).apply()
            }
        }
        layout.addView(glowCheckbox)

        setContentView(layout)
    }
}

package com.example.superwallpaper

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*

class WallpaperSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        layout.addView(TextView(this).apply {
            text = "Super Wallpaper Settings"
            textSize = 24f
            setPadding(0, 0, 0, 48)
        })

        // 1. Shape Type
        layout.addView(TextView(this).apply { text = "Shape Type"; textSize = 16f; setPadding(0, 24, 0, 8) })
        val shapeSpinner = Spinner(this)
        val shapes = arrayOf("Fluid Waves", "Glowing Metaballs", "Frosted Glass Discs", "Abstract Floral")
        shapeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapes)
        shapeSpinner.setSelection(prefs.getInt("pref_shape_type", 1))
        shapeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                editor.putInt("pref_shape_type", pos).apply()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        layout.addView(shapeSpinner)

        // 2. Color Theme
        layout.addView(TextView(this).apply { text = "Color Theme"; textSize = 16f; setPadding(0, 48, 0, 8) })
        val themeSpinner = Spinner(this)
        val themes = arrayOf("Emerald Glow", "Sunset Crimson", "Ocean Sapphire", "Amethyst Purple")
        themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
        themeSpinner.setSelection(prefs.getInt("pref_color_theme", 0))
        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                editor.putInt("pref_color_theme", pos).apply()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        layout.addView(themeSpinner)

        // 3. Wave Amplitude
        layout.addView(TextView(this).apply { text = "Shape Size / Amplitude"; textSize = 16f; setPadding(0, 48, 0, 8) })
        val amplitudeBar = SeekBar(this).apply { max = 100 }
        amplitudeBar.progress = (prefs.getFloat("pref_wave_amplitude", 0.35f) * 100).toInt()
        amplitudeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, user: Boolean) {
                editor.putFloat("pref_wave_amplitude", prog / 100f).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(amplitudeBar)

        // 4. Animation Speed
        layout.addView(TextView(this).apply { text = "Animation Speed"; textSize = 16f; setPadding(0, 48, 0, 8) })
        val speedBar = SeekBar(this).apply { max = 100 }
        speedBar.progress = (prefs.getFloat("pref_animation_speed", 0.015f) * 1000).toInt()
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, user: Boolean) {
                editor.putFloat("pref_animation_speed", prog / 1000f).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(speedBar)

        setContentView(layout)
    }
}

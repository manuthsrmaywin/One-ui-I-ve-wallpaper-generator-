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

        // Create the main layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        // Title
        layout.addView(TextView(this).apply {
            text = "Super Wallpaper Settings"
            textSize = 24f
            setPadding(0, 0, 0, 48)
        })

        // 1. Shape Type Dropdown (Spinner)
        layout.addView(TextView(this).apply { 
            text = "Shape Type"
            textSize = 16f
            setPadding(0, 24, 0, 8) 
        })
        val shapeSpinner = Spinner(this)
        val shapes = arrayOf("Wave Mesh", "Volumetric Orb", "Torus Knot", "Particle Cloud")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapes)
        shapeSpinner.adapter = adapter
        shapeSpinner.setSelection(prefs.getInt("pref_shape_type", 1)) // Default to Volumetric Orb
        shapeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                editor.putInt("pref_shape_type", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        layout.addView(shapeSpinner)

        // 2. Wave Amplitude Slider
        layout.addView(TextView(this).apply { 
            text = "Wave Amplitude (Height)"
            textSize = 16f
            setPadding(0, 48, 0, 8) 
        })
        val amplitudeBar = SeekBar(this).apply { max = 100 }
        // Convert stored float (0.0 - 1.0) to integer (0 - 100) for the slider
        amplitudeBar.progress = (prefs.getFloat("pref_wave_amplitude", 0.35f) * 100).toInt()
        amplitudeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                editor.putFloat("pref_wave_amplitude", progress / 100f).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        layout.addView(amplitudeBar)

        // 3. Animation Speed Slider
        layout.addView(TextView(this).apply { 
            text = "Animation Speed"
            textSize = 16f
            setPadding(0, 48, 0, 8) 
        })
        val speedBar = SeekBar(this).apply { max = 100 }
        // Convert stored float (0.0 - 0.1) to integer (0 - 100) for the slider
        speedBar.progress = (prefs.getFloat("pref_animation_speed", 0.015f) * 1000).toInt()
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                editor.putFloat("pref_animation_speed", progress / 1000f).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        layout.addView(speedBar)

        // Apply the layout to the screen
        setContentView(layout)
    }
}

package com.example.superwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.sin

class SuperWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return WallpaperEngine()
    }

    private inner class WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        
        private val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        private val paint = Paint().apply {
            color = Color.parseColor("#00E5FF") // Cyan glow
            isAntiAlias = true
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }

        private var shapeType = 1
        private var waveAmplitude = 0.35f
        private var animationSpeed = 0.015f
        private var phase = 0f

        private val handler = Handler(Looper.getMainLooper())
        private val drawRunnable = Runnable { drawFrame() }
        private var isVisible = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacks(drawRunnable)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            updateSettings()
        }

        private fun updateSettings() {
            shapeType = prefs.getInt("pref_shape_type", 1)
            waveAmplitude = prefs.getFloat("pref_wave_amplitude", 0.35f)
            animationSpeed = prefs.getFloat("pref_animation_speed", 0.015f)
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    drawShapes(canvas)
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }

            phase += animationSpeed
            if (isVisible) handler.postDelayed(drawRunnable, 16) // Runs at ~60 FPS
        }

        private fun drawShapes(canvas: Canvas) {
            canvas.drawColor(Color.parseColor("#0A0A0A")) // Deep dark background

            val cx = canvas.width / 2f
            val cy = canvas.height / 2f
            val baseRadius = canvas.width / 3f
            val amp = waveAmplitude * 400f // Scale amplitude slider to screen pixels

            when (shapeType) {
                0 -> { // Wave Mesh
                    paint.style = Paint.Style.STROKE
                    for (i in -6..6) {
                        val yOffset = cy + (i * 60)
                        var lastX = 0f
                        var lastY = yOffset + sin(phase + (i * 0.5f)) * amp
                        for (x in 0..canvas.width step 30) {
                            val y = yOffset + sin(phase + (x * 0.01f) + (i * 0.5f)) * amp
                            canvas.drawLine(lastX, lastY, x.toFloat(), y, paint)
                            lastX = x.toFloat()
                            lastY = y
                        }
                    }
                }
                1 -> { // Volumetric Orb
                    paint.style = Paint.Style.STROKE
                    for (i in 1..20) {
                        val radius = (baseRadius / 20) * i
                        val pulse = sin(phase + i * 0.3f) * amp
                        canvas.drawCircle(cx, cy, radius + pulse, paint)
                    }
                }
                2 -> { // Torus Knot (Lissajous Curve)
                    paint.style = Paint.Style.STROKE
                    var lastX = cx + sin(phase) * (baseRadius + amp)
                    var lastY = cy + cos(phase) * (baseRadius + amp)
                    for (i in 1..150) {
                        val t = phase + i * 0.05f
                        val x = cx + sin(3 * t) * (baseRadius + amp * sin(t))
                        val y = cy + sin(2 * t) * (baseRadius + amp * cos(t))
                        canvas.drawLine(lastX, lastY, x, y, paint)
                        lastX = x
                        lastY = y
                    }
                }
                3 -> { // Particle Cloud
                    paint.style = Paint.Style.FILL
                    for (i in 0..100) {
                        val t = phase * (1f + (i % 5) * 0.5f) + i
                        val radiusFactor = baseRadius + amp * (i % 4)
                        val px = cx + cos(t) * radiusFactor * cos(i.toFloat())
                        val py = cy + sin(t) * radiusFactor * sin(i.toFloat())
                        canvas.drawCircle(px, py, 6f, paint)
                    }
                }
            }
        }
    }
}

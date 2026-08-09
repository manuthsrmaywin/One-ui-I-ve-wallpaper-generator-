package com.example.superwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
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
        
        private val solidPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        private val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 8f }
        private val glassPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; alpha = 180 }

        private var shapeType = 1
        private var colorTheme = 0
        private var waveAmplitude = 0.35f
        private var animationSpeed = 0.015f
        private var phase = 0f

        // Transition Animation Variables (Xiaomi Saturn Style)
        private var currentTransition = 0f 
        private var targetTransition = 0f

        private val handler = Handler(Looper.getMainLooper())
        private val drawRunnable = Runnable { drawFrame() }
        private var isVisible = false

        // Listen for lock/unlock events
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Snap immediately to locked state while screen is off
                        targetTransition = 0f
                        currentTransition = 0f 
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // Trigger fluid transition to home screen state when unlocked
                        targetTransition = 1f 
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
            
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            baseContext.registerReceiver(screenReceiver, filter)
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            baseContext.unregisterReceiver(screenReceiver)
            handler.removeCallbacks(drawRunnable)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) drawFrame() else handler.removeCallbacks(drawRunnable)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            updateSettings()
        }

        private fun updateSettings() {
            shapeType = prefs.getInt("pref_shape_type", 1)
            colorTheme = prefs.getInt("pref_color_theme", 0)
            waveAmplitude = prefs.getFloat("pref_wave_amplitude", 0.35f)
            animationSpeed = prefs.getFloat("pref_animation_speed", 0.015f)
        }

        // Get colors based on the selected theme
        private fun getThemeColors(): Pair<Int, IntArray> {
            return when (colorTheme) {
                0 -> Pair(Color.parseColor("#051208"), intArrayOf(Color.parseColor("#A8FF78"), Color.parseColor("#123517"), Color.TRANSPARENT)) // Emerald
                1 -> Pair(Color.parseColor("#1A0805"), intArrayOf(Color.parseColor("#FF6B6B"), Color.parseColor("#590909"), Color.TRANSPARENT)) // Sunset
                2 -> Pair(Color.parseColor("#050F1A"), intArrayOf(Color.parseColor("#4FACFE"), Color.parseColor("#003870"), Color.TRANSPARENT)) // Ocean
                else -> Pair(Color.parseColor("#10051A"), intArrayOf(Color.parseColor("#C471ED"), Color.parseColor("#2C0747"), Color.TRANSPARENT)) // Amethyst
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            
            // Interpolate smoothly between Lock Screen (0.0) and Home Screen (1.0)
            currentTransition += (targetTransition - currentTransition) * 0.06f

            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    drawFluidShapes(canvas)
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }

            // Speed up slightly during the transition for extra kinetic feel
            val dynamicSpeed = animationSpeed + (Math.abs(targetTransition - currentTransition) * 0.05f)
            phase += dynamicSpeed
            
            if (isVisible) handler.postDelayed(drawRunnable, 16) 
        }

        private fun drawFluidShapes(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            
            // Calculate dynamic positions based on the transition state
            // Locked: Centered and smaller. Unlocked: Fills the screen and moves down slightly.
            val scale = 0.5f + (currentTransition * 0.5f) 
            val cx = w / 2f
            val cy = (h / 2.5f) + (currentTransition * 200f) 
            
            val amp = waveAmplitude * 500f * scale
            val (bgColor, gradColors) = getThemeColors()
            
            canvas.drawColor(bgColor)

            when (shapeType) {
                1 -> { 
                    // Glowing Metaballs
                    for (i in 0..4) {
                        val radius = ((w / 3f) - (i * 50f)) * scale
                        // Parallax effect: items move more dramatically during transition based on their depth (i)
                        val x = cx + sin(phase + i) * amp * (0.8f + (1f - currentTransition) * i * 0.2f)
                        val y = cy + cos(phase * 0.8f + i) * amp
                        
                        val gradient = RadialGradient(
                            x - radius/3, y - radius/3, radius,
                            gradColors, floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
                        )
                        solidPaint.shader = gradient
                        canvas.drawCircle(x, y, radius, solidPaint)
                    }
                    solidPaint.shader = null
                }
                
                2 -> { 
                    // Frosted Glass Discs
                    for (i in 0..2) {
                        val rx = (w / 2.5f) * scale
                        val ry = (w / 5f) * scale
                        
                        // Give a 3D expanding offset during unlock
                        val spread = 1f + (1f - currentTransition)
                        val offsetX = sin(phase + i * 2) * amp * 0.5f * spread
                        val offsetY = cos(phase + i * 1.5f) * amp * 0.8f * spread
                        
                        glassPaint.color = gradColors[0]
                        glassPaint.alpha = 150 - (i * 30)
                        
                        canvas.save()
                        canvas.translate(cx + offsetX, cy + offsetY)
                        // Spin into place during transition
                        canvas.rotate((phase * 20f) + (i * 45f) + ((1f - currentTransition) * 90f))
                        
                        val rect = RectF(-rx, -ry, rx, ry)
                        canvas.drawOval(rect, glassPaint)
                        
                        strokePaint.color = Color.WHITE
                        strokePaint.alpha = (80 * currentTransition).toInt() // Rim light fades in
                        strokePaint.strokeWidth = 3f
                        canvas.drawOval(rect, strokePaint)
                        
                        canvas.restore()
                    }
                }
                // (Wave and Floral logic remains the same structurally, but utilizes the new cx/cy/scale variables)
                else -> {
                    // Abstract Floral Fallback
                    solidPaint.color = gradColors[0]
                    solidPaint.alpha = 50
                    
                    val baseRadius = (w / 3f) * scale
                    for (i in 1..25) {
                        val path = Path()
                        val layerScale = 1f - (i * 0.03f)
                        val currentPhase = phase - (i * 0.05f)
                        
                        // Rotates wildly on lock screen, calms down on home screen
                        val transitionRotation = (1f - currentTransition) * i * 0.5f
                        
                        for (angle in 0..360 step 5) {
                            val rad = Math.toRadians(angle.toDouble()).toFloat() + transitionRotation
                            val r = baseRadius * layerScale + (sin(rad * 5) * amp * 0.3f) + (cos(currentPhase + rad * 3) * 50f * scale)
                            val px = cx + cos(rad) * r
                            val py = cy + sin(rad) * r
                            if (angle == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        canvas.drawPath(path, solidPaint)
                    }
                }
            }
        }
    }
}

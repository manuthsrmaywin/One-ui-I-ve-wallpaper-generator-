package com.example.superwallpaper

import android.content.Context
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
        
        // Setup distinct paints for different effects
        private val solidPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        private val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 8f }
        private val glassPaint = Paint().apply { 
            isAntiAlias = true
            style = Paint.Style.FILL
            alpha = 180 
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
            if (visible) drawFrame() else handler.removeCallbacks(drawRunnable)
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
                    drawFluidShapes(canvas)
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }

            phase += animationSpeed
            if (isVisible) handler.postDelayed(drawRunnable, 16) // ~60 FPS
        }

        private fun drawFluidShapes(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val amp = waveAmplitude * 500f 

            when (shapeType) {
                0 -> { 
                    // Mimic Reference 1: Fluid Gold Waves
                    canvas.drawColor(Color.parseColor("#1A1813")) // Deep warm dark
                    
                    val path = Path()
                    // Back wave (darker gold)
                    solidPaint.color = Color.parseColor("#664920")
                    path.moveTo(0f, h)
                    path.lineTo(0f, cy + sin(phase) * amp)
                    path.cubicTo(w * 0.33f, cy - cos(phase) * amp, w * 0.66f, cy + sin(phase*1.2f) * amp, w, cy - cos(phase*0.8f) * amp)
                    path.lineTo(w, h)
                    canvas.drawPath(path, solidPaint)

                    // Front wave (bright metallic gold)
                    path.reset()
                    solidPaint.color = Color.parseColor("#D4AF37")
                    path.moveTo(0f, h)
                    path.lineTo(0f, cy + 100f + sin(phase * 1.1f) * amp)
                    path.cubicTo(w * 0.33f, cy + 100f + cos(phase*0.9f) * amp, w * 0.66f, cy + 100f - sin(phase) * amp, w, cy + 100f + sin(phase*1.3f) * amp)
                    path.lineTo(w, h)
                    
                    // Add a metallic rim light effect
                    strokePaint.color = Color.parseColor("#FFF7D6")
                    canvas.drawPath(path, strokePaint)
                    canvas.drawPath(path, solidPaint)
                }
                
                1 -> { 
                    // Mimic Reference 2: Glowing Green Metaballs
                    canvas.drawColor(Color.parseColor("#051208"))
                    
                    for (i in 0..4) {
                        val radius = (w / 3f) - (i * 50f)
                        val x = cx + sin(phase + i) * amp * 0.8f
                        val y = cy + cos(phase * 0.8f + i) * amp
                        
                        // Fake 3D volume with a radial gradient
                        val gradient = RadialGradient(
                            x - radius/3, y - radius/3, radius,
                            intArrayOf(Color.parseColor("#A8FF78"), Color.parseColor("#123517"), Color.TRANSPARENT),
                            floatArrayOf(0f, 0.7f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        solidPaint.shader = gradient
                        canvas.drawCircle(x, y, radius, solidPaint)
                    }
                    solidPaint.shader = null // Reset
                }
                
                2 -> { 
                    // Mimic Reference 3/4: Floating Frosted Glass Discs
                    canvas.drawColor(Color.parseColor("#121212"))
                    
                    val colors = arrayOf("#5588FF", "#FF5588", "#8855FF")
                    for (i in 0..2) {
                        val rx = w / 2.5f
                        val ry = w / 5f
                        
                        val offsetX = sin(phase + i * 2) * amp * 0.5f
                        val offsetY = cos(phase + i * 1.5f) * amp * 0.8f
                        
                        glassPaint.color = Color.parseColor(colors[i])
                        glassPaint.alpha = 180
                        
                        canvas.save()
                        canvas.translate(cx + offsetX, cy + offsetY)
                        canvas.rotate((phase * 20f) + (i * 45f))
                        
                        // Draw the "glass" oval
                        val rect = RectF(-rx, -ry, rx, ry)
                        canvas.drawOval(rect, glassPaint)
                        
                        // Draw a white rim to simulate glass edge reflection
                        strokePaint.color = Color.WHITE
                        strokePaint.alpha = 100
                        strokePaint.strokeWidth = 3f
                        canvas.drawOval(rect, strokePaint)
                        
                        canvas.restore()
                    }
                }
                
                3 -> { 
                    // Mimic Reference 5/6: Abstract Floral Math Shape
                    canvas.drawColor(Color.BLACK)
                    solidPaint.color = Color.parseColor("#446699")
                    solidPaint.alpha = 50
                    
                    val baseRadius = w / 3f
                    for (i in 1..25) {
                        val path = Path()
                        val layerScale = 1f - (i * 0.03f)
                        val currentPhase = phase - (i * 0.05f)
                        
                        for (angle in 0..360 step 5) {
                            val rad = Math.toRadians(angle.toDouble()).toFloat()
                            // Parametric math to create flower-like folds
                            val r = baseRadius * layerScale + (sin(rad * 5) * amp * 0.3f) + (cos(currentPhase + rad * 3) * 50f)
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

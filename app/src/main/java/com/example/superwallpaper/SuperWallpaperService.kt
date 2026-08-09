package com.example.superwallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SuperWallpaperService : WallpaperService() {

    enum class ShapeType { WAVE_MESH, VOLUMETRIC_ORB, TORUS_KNOT, PARTICLE_CLOUD }

    data class WallpaperConfig(
        val shapeType: ShapeType = ShapeType.VOLUMETRIC_ORB,
        val primaryColorDark: FloatArray = floatArrayOf(0.1f, 0.45f, 0.95f, 1.0f),
        val secondaryColorDark: FloatArray = floatArrayOf(0.6f, 0.1f, 0.85f, 1.0f),
        val primaryColorLight: FloatArray = floatArrayOf(0.2f, 0.6f, 1.0f, 1.0f),
        val secondaryColorLight: FloatArray = floatArrayOf(0.9f, 0.4f, 0.7f, 1.0f),
        val animationSpeed: Float = 0.015f,
        val fluidityDamping: Float = 0.08f,
        val volumetricDensity: Float = 1.2f,
        val waveAmplitude: Float = 0.35f
    )

    override fun onCreateEngine(): Engine {
        return SuperWallpaperEngine()
    }

    inner class SuperWallpaperEngine : Engine(), GLSurfaceView.Renderer, SharedPreferences.OnSharedPreferenceChangeListener {

        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var keyguardManager: KeyguardManager? = null
        private var prefs: SharedPreferences? = null

        private var isLocked = true
        private var isDarkMode = true
        
        var config = WallpaperConfig()

        private var xOffset = 0.5f
        private var targetOffset = 0.5f
        private var targetZoom = 5.0f
        private var currentZoom = 5.0f
        
        private var touchX = -1.0f
        private var touchY = -1.0f
        private var touchTime = 0.0f
        private var time = 0.0f

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)

        private var program = 0
        private val gridCols = 40
        private val gridRows = 40
        private var indexCount = 0
        private var isProgramValid = false

        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var indexBuffer: ShortBuffer

        private val vertexShaderCode = """
            uniform mat4 uMVPMatrix;
            uniform float uTime;
            uniform float uXOffset;
            uniform int uShapeType;
            uniform float uAmplitude;
            
            attribute vec3 aPosition;
            varying vec3 vPosition;
            varying float vDisplacement;

            void main() {
                vec3 pos = aPosition;
                float disp = sin(pos.x * 2.0 + uTime * 1.5 + uXOffset * 3.0) * uAmplitude +
                            cos(pos.y * 2.0 + uTime * 1.0) * (uAmplitude * 0.7);
                pos.z += disp;
                
                vPosition = pos;
                vDisplacement = disp;
                gl_Position = uMVPMatrix * vec4(pos, 1.0);
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            precision mediump float;
            
            uniform vec4 uPrimaryColor;
            uniform vec4 uSecondaryColor;
            uniform float uTime;
            uniform float uDensity;
            uniform vec2 uTouchPos;
            uniform float uTouchTime;
            uniform int uShapeType;
            
            varying vec3 vPosition;
            varying float vDisplacement;

            void main() {
                float factor = clamp(vDisplacement + 0.5, 0.0, 1.0);
                vec4 color = mix(uPrimaryColor, uSecondaryColor, factor);
                
                if (uTouchTime > 0.0) {
                    float dist = distance(vPosition.xy, uTouchPos);
                    float rippleRadius = uTouchTime * 3.5;
                    float rippleWidth = 0.35;
                    float ripple = smoothstep(rippleRadius - rippleWidth, rippleRadius, dist) - 
                                   smoothstep(rippleRadius, rippleRadius + rippleWidth, dist);
                    float fade = max(0.0, 1.0 - (uTouchTime * 0.7));
                    color.rgb += vec3(0.3, 0.7, 1.0) * ripple * fade;
                }

                color.rgb += vec3(pow(factor, 3.0) * 0.3);
                gl_FragColor = color;
            }
        """.trimIndent()

        private val userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        isLocked = false
                        targetZoom = 4.0f
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isLocked = true
                        targetZoom = 5.5f
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            // Initialize Preferences and Listener for Google Wallpapers Settings
            prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            prefs?.registerOnSharedPreferenceChangeListener(this)
            loadUserPreferences()

            isLocked = keyguardManager?.isKeyguardLocked ?: true
            targetZoom = if (isLocked) 5.5f else 4.0f
            currentZoom = targetZoom

            updateThemeState()

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            try {
                registerReceiver(userPresentReceiver, filter)
            } catch (e: Exception) {
                Log.e("SuperWallpaper", "Receiver registration failed", e)
            }

            generateMeshGrid()

            glSurfaceView = WallpaperGLSurfaceView(this@SuperWallpaperService, surfaceHolder).apply {
                setEGLContextClientVersion(2)
                setRenderer(this@SuperWallpaperEngine)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        private fun loadUserPreferences() {
            prefs?.let { p ->
                val speed = p.getFloat("pref_animation_speed", 0.015f)
                val amplitude = p.getFloat("pref_wave_amplitude", 0.35f)
                val density = p.getFloat("pref_volumetric_density", 1.2f)
                val shapeIndex = p.getInt("pref_shape_type", ShapeType.VOLUMETRIC_ORB.ordinal)
                
                val shape = ShapeType.values().getOrElse(shapeIndex) { ShapeType.VOLUMETRIC_ORB }

                config = config.copy(
                    animationSpeed = speed,
                    waveAmplitude = amplitude,
                    volumetricDensity = density,
                    shapeType = shape
                )
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            loadUserPreferences()
        }

        private fun updateThemeState() {
            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        }

        private fun generateMeshGrid() {
            val vertices = ArrayList<Float>()
            val indices = ArrayList<Short>()

            val width = 7.0f
            val height = 7.0f

            for (r in 0..gridRows) {
                val y = (r.toFloat() / gridRows) * height - (height / 2.0f)
                for (c in 0..gridCols) {
                    val x = (c.toFloat() / gridCols) * width - (width / 2.0f)
                    vertices.add(x)
                    vertices.add(y)
                    vertices.add(0.0f)
                }
            }

            for (r in 0 until gridRows) {
                for (c in 0 until gridCols) {
                    val row1 = r * (gridCols + 1)
                    val row2 = (r + 1) * (gridCols + 1)

                    indices.add((row1 + c).toShort())
                    indices.add((row2 + c).toShort())
                    indices.add((row1 + c + 1).toShort())

                    indices.add((row1 + c + 1).toShort())
                    indices.add((row2 + c).toShort())
                    indices.add((row2 + c + 1).toShort())
                }
            }

            indexCount = indices.size

            val vBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vBuffer.put(vertices.toFloatArray()).position(0)
            vertexBuffer = vBuffer

            val iBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            iBuffer.put(indices.toShortArray()).position(0)
            indexBuffer = iBuffer
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            targetOffset = xOffset
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event?.action == MotionEvent.ACTION_DOWN) {
                val viewWidth = glSurfaceView?.width ?: 1
                val viewHeight = glSurfaceView?.height ?: 1
                touchX = ((event.x / viewWidth) - 0.5f) * 7.0f
                touchY = -((event.y / viewHeight) - 0.5f) * 7.0f
                touchTime = 0.01f
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glSurfaceView?.surfaceCreated(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            glSurfaceView?.surfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glSurfaceView?.surfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                updateThemeState()
                loadUserPreferences()
                isLocked = keyguardManager?.isKeyguardLocked ?: true
                targetZoom = if (isLocked) 5.5f else 4.0f
                glSurfaceView?.onResume()
            } else {
                glSurfaceView?.onPause()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            try { unregisterReceiver(userPresentReceiver) } catch (e: Exception) {}
            glSurfaceView?.onDestroy()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            setClearColorForTheme()
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            if (vShader != 0 && fShader != 0) {
                program = GLES20.glCreateProgram().also { p ->
                    GLES20.glAttachShader(p, vShader)
                    GLES20.glAttachShader(p, fShader)
                    GLES20.glLinkProgram(p)

                    val linkStatus = IntArray(1)
                    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
                    if (linkStatus[0] != 0) {
                        isProgramValid = true
                    } else {
                        Log.e("SuperWallpaper", "Program Link Error: " + GLES20.glGetProgramInfoLog(p))
                    }
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            val w = if (width <= 0) 1080 else width
            val h = if (height <= 0) 2400 else height
            GLES20.glViewport(0, 0, w, h)
            val ratio: Float = w.toFloat() / h.toFloat()
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 50f)
        }

        override fun onDrawFrame(gl: GL10?) {
            setClearColorForTheme()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            if (!isProgramValid) return

            time += config.animationSpeed

            if (touchTime > 0.0f) {
                touchTime += 0.02f
                if (touchTime > 1.8f) touchTime = 0.0f
            }

            currentZoom += (targetZoom - currentZoom) * config.fluidityDamping
            xOffset += (targetOffset - xOffset) * config.fluidityDamping

            GLES20.glUseProgram(program)

            Matrix.setLookAtM(viewMatrix, 0, 0f, -2.0f, currentZoom, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val swipeAngle = (xOffset - 0.5f) * 30.0f
            Matrix.setRotateM(rotationMatrix, 0, swipeAngle, 0.0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, rotationMatrix, 0)

            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            if (posHandle >= 0) {
                GLES20.glEnableVertexAttribArray(posHandle)
                GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            }

            setUniform1f("uTime", time)
            setUniform1f("uXOffset", xOffset)
            setUniform1i("uShapeType", config.shapeType.ordinal)
            setUniform1f("uAmplitude", config.waveAmplitude)
            setUniform1f("uDensity", config.volumetricDensity)
            setUniform2f("uTouchPos", touchX, touchY)
            setUniform1f("uTouchTime", touchTime)

            val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            if (mvpHandle >= 0) {
                GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            }

            val primary = if (isDarkMode) config.primaryColorDark else config.primaryColorLight
            val secondary = if (isDarkMode) config.secondaryColorDark else config.secondaryColorLight

            val pHandle = GLES20.glGetUniformLocation(program, "uPrimaryColor")
            if (pHandle >= 0) GLES20.glUniform4fv(pHandle, 1, primary, 0)

            val sHandle = GLES20.glGetUniformLocation(program, "uSecondaryColor")
            if (sHandle >= 0) GLES20.glUniform4fv(sHandle, 1, secondary, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            if (posHandle >= 0) {
                GLES20.glDisableVertexAttribArray(posHandle)
            }
        }

        private fun setUniform1f(name: String, value: Float) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform1f(loc, value)
        }

        private fun setUniform1i(name: String, value: Int) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform1i(loc, value)
        }

        private fun setUniform2f(name: String, x: Float, y: Float) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc >= 0) GLES20.glUniform2f(loc, x, y)
        }

        private fun setClearColorForTheme() {
            if (isDarkMode) {
                GLES20.glClearColor(0.03f, 0.04f, 0.08f, 1.0f)
            } else {
                GLES20.glClearColor(0.92f, 0.95f, 0.98f, 1.0f)
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0

            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("SuperWallpaper", "Shader Compilation Error ($type): " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        inner class WallpaperGLSurfaceView(context: Context, private val holder: SurfaceHolder?) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder {
                return holder ?: super.getHolder()
            }

            fun onDestroy() {
                super.onDetachedFromWindow()
            }
        }
    }
}

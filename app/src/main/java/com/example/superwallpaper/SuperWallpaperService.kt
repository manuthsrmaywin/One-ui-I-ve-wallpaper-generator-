package com.example.superwallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SuperWallpaperService : WallpaperService() {

    // --- Configuration Preset Data Model ---
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

    inner class SuperWallpaperEngine : Engine(), GLSurfaceView.Renderer {

        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var keyguardManager: KeyguardManager? = null

        // System & Theme States
        private var isLocked = true
        private var isDarkMode = true
        
        // Active Configuration
        var config = WallpaperConfig()

        // Interpolated Motion Values
        private var xOffset = 0.5f
        private var targetOffset = 0.5f
        private var targetZoom = 5.0f
        private var currentZoom = 5.0f
        
        private var touchX = -1.0f
        private var touchY = -1.0f
        private var touchTime = 0.0f
        private var time = 0.0f

        // Matrices
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)

        private var program = 0
        private val gridCols = 50
        private val gridRows = 50
        private var indexCount = 0

        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var indexBuffer: ShortBuffer

        // Advanced Shader: Handles dynamic shape deformation, wave displacement, and volumetric lighting
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
                float disp = 0.0;
                
                if (uShapeType == 0) { 
                    // WAVE_MESH
                    disp = sin(pos.x * 2.0 + uTime * 1.5 + uXOffset * 4.0) * uAmplitude +
                           cos(pos.y * 2.0 + uTime * 1.0) * (uAmplitude * 0.75);
                    pos.z += disp;
                } else if (uShapeType == 1 || uShapeType == 2) { 
                    // VOLUMETRIC_ORB / TORUS_KNOT Morphing
                    float angle = atan(pos.y, pos.x);
                    float dist = length(pos.xy);
                    disp = sin(dist * 4.0 - uTime * 2.0) * uAmplitude;
                    pos.z += disp;
                }
                
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
                vec4 color = mix(uPrimaryColor, uSecondaryColor, vDisplacement + 0.5);
                
                // Volumetric Glow / Raymarched feel calculation
                if (uShapeType == 1) {
                    float radialGlow = 1.0 - smoothstep(0.0, 2.5 * uDensity, length(vPosition.xy));
                    color.rgb += uSecondaryColor.rgb * radialGlow * 0.6;
                }
                
                // Touch Ripple Effect
                if (uTouchTime > 0.0) {
                    float dist = distance(vPosition.xy, uTouchPos);
                    float rippleRadius = uTouchTime * 3.5;
                    float rippleWidth = 0.35;
                    float ripple = smoothstep(rippleRadius - rippleWidth, rippleRadius, dist) - 
                                   smoothstep(rippleRadius, rippleRadius + rippleWidth, dist);
                    float fade = max(0.0, 1.0 - (uTouchTime * 0.7));
                    color.rgb += vec3(0.3, 0.7, 1.0) * ripple * fade;
                }

                // Specular sheen along movement edges
                float sheen = pow(clamp(vDisplacement + 0.4, 0.0, 1.0), 3.0) * 0.4;
                color.rgb += vec3(sheen);

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

            isLocked = keyguardManager?.isKeyguardLocked ?: true
            targetZoom = if (isLocked) 5.5f else 4.0f
            currentZoom = targetZoom

            updateThemeState()

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(userPresentReceiver, filter)

            generateMeshGrid()

            glSurfaceView = WallpaperGLSurfaceView(this@SuperWallpaperService, surfaceHolder).apply {
                setEGLContextClientVersion(2)
                setRenderer(this@SuperWallpaperEngine)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
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
                isLocked = keyguardManager?.isKeyguardLocked ?: true
                targetZoom = if (isLocked) 5.5f else 4.0f
                glSurfaceView?.onResume()
            } else {
                glSurfaceView?.onPause()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            try { unregisterReceiver(userPresentReceiver) } catch (e: Exception) {}
            glSurfaceView?.onDestroy()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            setClearColorForTheme()
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height.toFloat()
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 20f)
        }

        override fun onDrawFrame(gl: GL10?) {
            time += config.animationSpeed

            if (touchTime > 0.0f) {
                touchTime += 0.02f
                if (touchTime > 1.8f) touchTime = 0.0f
            }

            // Smooth interpolation using configurable damping
            currentZoom += (targetZoom - currentZoom) * config.fluidityDamping
            xOffset += (targetOffset - xOffset) * config.fluidityDamping

            setClearColorForTheme()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            GLES20.glUseProgram(program)

            Matrix.setLookAtM(viewMatrix, 0, 0f, -2.0f, currentZoom, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val swipeAngle = (xOffset - 0.5f) * 30.0f
            Matrix.setRotateM(rotationMatrix, 0, swipeAngle, 0.0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, rotationMatrix, 0)

            GLES20.glGetAttribLocation(program, "aPosition").also {
                GLES20.glEnableVertexAttribArray(it)
                GLES20.glVertexAttribPointer(it, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            }

            // Pass Configuration Values to Shader Uniforms
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), time)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uXOffset"), xOffset)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uShapeType"), config.shapeType.ordinal)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAmplitude"), config.waveAmplitude)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDensity"), config.volumetricDensity)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTouchPos"), touchX, touchY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTouchTime"), touchTime)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVPMatrix"), 1, false, mvpMatrix, 0)

            // Dynamic Palette Swapping based on Light/Dark System Theme
            val primary = if (isDarkMode) config.primaryColorDark else config.primaryColorLight
            val secondary = if (isDarkMode) config.secondaryColorDark else config.secondaryColorLight

            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uPrimaryColor"), 1, primary, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uSecondaryColor"), 1, secondary, 0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        }

        private fun setClearColorForTheme() {
            if (isDarkMode) {
                GLES20.glClearColor(0.03f, 0.04f, 0.08f, 1.0f)
            } else {
                GLES20.glClearColor(0.92f, 0.95f, 0.98f, 1.0f)
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
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

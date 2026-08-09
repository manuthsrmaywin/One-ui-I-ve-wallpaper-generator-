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

    override fun onCreateEngine(): Engine {
        return SuperWallpaperEngine()
    }

    inner class SuperWallpaperEngine : Engine(), GLSurfaceView.Renderer {

        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var keyguardManager: KeyguardManager? = null

        private var isLocked = true
        private var isDarkMode = true
        
        // Page swipe & transition tracking
        private var xOffset = 0.5f
        private var targetOffset = 0.5f
        private var targetZoom = 6.0f
        private var currentZoom = 6.0f
        
        // Touch interaction
        private var touchX = -1.0f
        private var touchY = -1.0f
        private var touchTime = 0.0f

        // Matrix transformations
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)

        private var time = 0.0f
        private var program = 0

        // Geometry buffers for mesh grid
        private val gridCols = 40
        private val gridRows = 40
        private var indexCount = 0

        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var indexBuffer: ShortBuffer

        // Shaders handling procedural wave deformation & lighting
        private val vertexShaderCode = """
            uniform mat4 uMVPMatrix;
            uniform float uTime;
            uniform float uXOffset;
            attribute vec3 aPosition;
            varying vec3 vPosition;
            varying float vWave;

            void main() {
                vec3 pos = aPosition;
                
                // Calculate fluid wave deformation directly in vertex shader
                float wave1 = sin(pos.x * 1.5 + uTime * 1.2 + uXOffset * 3.0) * 0.4;
                float wave2 = cos(pos.y * 1.8 + uTime * 0.9) * 0.3;
                float wave3 = sin((pos.x + pos.y) * 1.0 + uTime * 1.5) * 0.2;
                
                pos.z += wave1 + wave2 + wave3;
                
                vPosition = pos;
                vWave = pos.z;
                
                gl_Position = uMVPMatrix * vec4(pos, 1.0);
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            precision mediump float;
            uniform vec4 uBaseColor;
            uniform vec4 uWaveColor;
            uniform float uTime;
            uniform vec2 uTouchPos;
            uniform float uTouchTime;
            
            varying vec3 vPosition;
            varying float vWave;

            void main() {
                // Blend color based on wave height
                float mixFactor = smoothstep(-0.5, 0.6, vWave);
                vec4 color = mix(uBaseColor, uWaveColor, mixFactor);
                
                // Add soft specular sheen along wave peaks
                float highlight = pow(mixFactor, 3.0) * 0.35;
                color.rgb += vec3(highlight);

                // Touch ripple effect calculation
                if (uTouchTime > 0.0) {
                    float dist = distance(vPosition.xy, uTouchPos);
                    float rippleRadius = uTouchTime * 3.0;
                    float rippleWidth = 0.4;
                    float ripple = smoothstep(rippleRadius - rippleWidth, rippleRadius, dist) - 
                                   smoothstep(rippleRadius, rippleRadius + rippleWidth, dist);
                    float fade = max(0.0, 1.0 - (uTouchTime * 0.8));
                    color.rgb += vec3(0.2, 0.5, 0.9) * ripple * fade;
                }

                gl_FragColor = color;
            }
        """.trimIndent()

        private val userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        isLocked = false
                        targetZoom = 4.5f
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isLocked = true
                        targetZoom = 6.0f
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            isLocked = keyguardManager?.isKeyguardLocked ?: true
            targetZoom = if (isLocked) 6.0f else 4.5f
            currentZoom = targetZoom

            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

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

        // Programmatically generates a 3D plane grid mesh
        private fun generateMeshGrid() {
            val vertices = ArrayList<Float>()
            val indices = ArrayList<Short>()

            val width = 6.0f
            val height = 6.0f

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
                // Map screen tap to normalized 3D mesh space
                val viewWidth = glSurfaceView?.width ?: 1
                val viewHeight = glSurfaceView?.height ?: 1
                touchX = ((event.x / viewWidth) - 0.5f) * 6.0f
                touchY = -((event.y / viewHeight) - 0.5f) * 6.0f
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
                isLocked = keyguardManager?.isKeyguardLocked ?: true
                targetZoom = if (isLocked) 6.0f else 4.5f
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
            time += 0.015f
            if (touchTime > 0.0f) {
                touchTime += 0.02f
                if (touchTime > 1.5f) touchTime = 0.0f
            }

            // Smooth interpolation for unlock zoom & page swipe
            currentZoom += (targetZoom - currentZoom) * 0.06f
            xOffset += (targetOffset - xOffset) * 0.1f

            setClearColorForTheme()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            GLES20.glUseProgram(program)

            // Tilt the camera angled down for a dynamic perspective
            Matrix.setLookAtM(viewMatrix, 0, 0f, -2.5f, currentZoom, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            // Apply horizontal rotation when swiping home pages
            val swipeAngle = (xOffset - 0.5f) * 25.0f
            Matrix.setRotateM(rotationMatrix, 0, swipeAngle, 0.0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, rotationMatrix, 0)

            GLES20.glGetAttribLocation(program, "aPosition").also {
                GLES20.glEnableVertexAttribArray(it)
                GLES20.glVertexAttribPointer(it, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            }

            // Uniforms setup
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), time)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uXOffset"), xOffset)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTouchPos"), touchX, touchY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTouchTime"), touchTime)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVPMatrix"), 1, false, mvpMatrix, 0)

            // Correct light/dark color assignments
            if (isDarkMode) {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uBaseColor"), 0.05f, 0.08f, 0.18f, 1.0f)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uWaveColor"), 0.15f, 0.45f, 0.95f, 1.0f)
            } else {
                GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uBaseColor"), 0.85f, 0.92f, 1.0f, 1.0f)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uWaveColor"), 0.2f, 0.55f, 0.9f, 1.0f)
            }

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        }

        private fun setClearColorForTheme() {
            if (isDarkMode) {
                GLES20.glClearColor(0.04f, 0.05f, 0.1f, 1.0f) // Deep dark canvas
            } else {
                GLES20.glClearColor(0.92f, 0.95f, 0.98f, 1.0f) // Clean light canvas
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

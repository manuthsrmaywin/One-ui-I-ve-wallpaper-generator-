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
        private var targetZoom = 7.0f
        private var currentZoom = 7.0f

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)

        private var angle = 0.0f
        private var program = 0

        private lateinit var vertexBuffer: FloatBuffer
        private lateinit var drawListBuffer: ShortBuffer

        // 3D Cube Vertices & Colors
        private val cubeCoords = floatArrayOf(
            -1.0f,  1.0f,  1.0f,
            -1.0f, -1.0f,  1.0f,
             1.0f, -1.0f,  1.0f,
             1.0f,  1.0f,  1.0f,
            -1.0f,  1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f,
             1.0f, -1.0f, -1.0f,
             1.0f,  1.0f, -1.0f
        )

        private val drawOrder = shortArrayOf(
            0, 1, 2, 0, 2, 3, // front
            4, 5, 1, 4, 1, 0, // left
            3, 2, 6, 3, 6, 7, // right
            4, 0, 3, 4, 3, 7, // top
            1, 5, 6, 1, 6, 2, // bottom
            7, 6, 5, 7, 5, 4  // back
        )

        private val vertexShaderCode =
            "attribute vec4 vPosition;" +
            "uniform mat4 uMVPMatrix;" +
            "void main() {" +
            "  gl_Position = uMVPMatrix * vPosition;" +
            "}"

        private val fragmentShaderCode =
            "precision mediump float;" +
            "uniform vec4 vColor;" +
            "void main() {" +
            "  gl_FragColor = vColor;" +
            "}"

        private val userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        isLocked = false
                        targetZoom = 4.0f
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isLocked = true
                        targetZoom = 7.0f
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            isLocked = keyguardManager?.isKeyguardLocked ?: true
            targetZoom = if (isLocked) 7.0f else 4.0f
            currentZoom = targetZoom

            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(userPresentReceiver, filter)

            // Setup OpenGL Buffers
            val bb = ByteBuffer.allocateDirect(cubeCoords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer().apply {
                put(cubeCoords)
                position(0)
            }

            val dlb = ByteBuffer.allocateDirect(drawOrder.size * 2)
            dlb.order(ByteOrder.nativeOrder())
            drawListBuffer = dlb.asShortBuffer().apply {
                put(drawOrder)
                position(0)
            }

            glSurfaceView = WallpaperGLSurfaceView(this@SuperWallpaperService).apply {
                setEGLContextClientVersion(2)
                setRenderer(this@SuperWallpaperEngine)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                isLocked = keyguardManager?.isKeyguardLocked ?: true
                targetZoom = if (isLocked) 7.0f else 4.0f
                glSurfaceView?.onResume()
            } else {
                glSurfaceView?.onPause()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            try {
                unregisterReceiver(userPresentReceiver)
            } catch (e: Exception) {}
            glSurfaceView?.onDestroy()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            setClearColorForTheme()
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height.toFloat()
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 20f)
        }

        override fun onDrawFrame(gl: GL10?) {
            currentZoom += (targetZoom - currentZoom) * 0.08f

            setClearColorForTheme()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            GLES20.glUseProgram(program)

            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, currentZoom, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            angle += 0.8f
            Matrix.setRotateM(rotationMatrix, 0, angle, 0.4f, 1.0f, 0.2f)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, rotationMatrix, 0)

            val positionHandle = GLES20.glGetAttribLocation(program, "vPosition").also {
                GLES20.glEnableVertexAttribArray(it)
                GLES20.glVertexAttribPointer(it, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            }

            val colorHandle = GLES20.glGetUniformLocation(program, "vColor").also {
                val color = if (isDarkMode) floatArrayOf(0.3f, 0.6f, 1.0f, 1.0f) else floatArrayOf(0.1f, 0.4f, 0.8f, 1.0f)
                GLES20.glUniform4fv(it, 1, color, 0)
            }

            val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix").also {
                GLES20.glUniformMatrix4fv(it, 1, false, mvpMatrix, 0)
            }

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.size, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        private fun setClearColorForTheme() {
            if (isDarkMode) {
                GLES20.glClearColor(0.08f, 0.09f, 0.15f, 1.0f)
            } else {
                GLES20.glClearColor(0.9f, 0.95f, 1.0f, 1.0f)
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }

        internal inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun onDestroy() { super.onDetachedFromWindow() }
        }
    }
}
        private val rotationMatrix = FloatArray(16)

        private var angle = 0.0f

        private val userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        isLocked = false
                        targetZoom = 4.0f
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isLocked = true
                        targetZoom = 7.0f
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            
            isLocked = keyguardManager?.isKeyguardLocked ?: true
            targetZoom = if (isLocked) 7.0f else 4.0f
            currentZoom = targetZoom

            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(userPresentReceiver, filter)

            glSurfaceView = WallpaperGLSurfaceView(this@SuperWallpaperService).apply {
                setEGLContextClientVersion(2)
                setRenderer(this@SuperWallpaperEngine)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                isLocked = keyguardManager?.isKeyguardLocked ?: true
                targetZoom = if (isLocked) 7.0f else 4.0f
                glSurfaceView?.onResume()
            } else {
                glSurfaceView?.onPause()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            try {
                unregisterReceiver(userPresentReceiver)
            } catch (e: Exception) {}
            glSurfaceView?.onDestroy()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            setClearColorForTheme()
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height.toFloat()
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 20f)
        }

        override fun onDrawFrame(gl: GL10?) {
            currentZoom += (targetZoom - currentZoom) * 0.08f

            setClearColorForTheme()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, currentZoom, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            angle += 0.4f
            Matrix.setRotateM(rotationMatrix, 0, angle, 0.2f, 1.0f, 0.0f)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, rotationMatrix, 0)
        }

        private fun setClearColorForTheme() {
            if (isDarkMode) {
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            } else {
                GLES20.glClearColor(0.9f, 0.95f, 1.0f, 1.0f)
            }
        }

        internal inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun onDestroy() { super.onDetachedFromWindow() }
        }
    }
}

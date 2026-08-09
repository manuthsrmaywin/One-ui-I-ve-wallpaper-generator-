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

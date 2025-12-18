package com.voicelink.connect.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource

/**
 * Manages screen capture for WebRTC video sharing.
 * Uses MediaProjection API to capture the device screen and feed it to WebRTC.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val eglBase: EglBase
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
        
        // Video resolution and frame rate settings
        // Lower resolution for better performance and bandwidth
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
    }

    sealed class State {
        data object Idle : State()
        data object Capturing : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var mediaProjectionPermissionData: Intent? = null
    private var mediaProjectionPermissionResultCode: Int = 0

    /**
     * Store the MediaProjection permission result for later use.
     * Call this immediately after receiving the permission result.
     */
    fun storePermissionResult(resultCode: Int, data: Intent) {
        mediaProjectionPermissionResultCode = resultCode
        mediaProjectionPermissionData = data
        Log.d(TAG, "MediaProjection permission stored")
    }

    /**
     * Check if we have stored permission to start capture.
     */
    fun hasPermission(): Boolean = mediaProjectionPermissionData != null

    /**
     * Create and initialize the screen capturer.
     * Must be called after storePermissionResult().
     * 
     * @param videoSource The WebRTC VideoSource to attach the capturer to
     * @return true if initialization was successful
     */
    fun initialize(videoSource: VideoSource): Boolean {
        Log.d(TAG, "Initializing ScreenCaptureManager")
        
        val permissionData = mediaProjectionPermissionData
        if (permissionData == null) {
            Log.e(TAG, "No MediaProjection permission stored")
            _state.value = State.Error("MediaProjection permission required")
            return false
        }

        try {
            this.videoSource = videoSource
            
            // Create MediaProjection callback
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopCapture()
                }
            }

            // Create the screen capturer
            screenCapturer = ScreenCapturerAndroid(
                permissionData,
                mediaProjectionCallback
            )

            // Create surface texture helper for video processing
            surfaceTextureHelper = SurfaceTextureHelper.create(
                "ScreenCaptureThread",
                eglBase.eglBaseContext
            )

            // Initialize the capturer
            screenCapturer?.initialize(
                surfaceTextureHelper,
                context,
                videoSource.capturerObserver
            )

            Log.i(TAG, "ScreenCaptureManager initialized successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ScreenCaptureManager", e)
            _state.value = State.Error(e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Start capturing the screen.
     * Must call initialize() first.
     */
    fun startCapture(): Boolean {
        Log.d(TAG, "Starting screen capture")
        
        val capturer = screenCapturer
        if (capturer == null) {
            Log.e(TAG, "Screen capturer not initialized")
            _state.value = State.Error("Screen capturer not initialized")
            return false
        }

        try {
            // Get display metrics for resolution
            val displayMetrics = getDisplayMetrics()
            
            // Calculate scaled dimensions maintaining aspect ratio
            val (width, height) = calculateScaledDimensions(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            )

            Log.d(TAG, "Starting capture at ${width}x${height} @ ${VIDEO_FPS}fps")
            capturer.startCapture(width, height, VIDEO_FPS)
            
            _state.value = State.Capturing
            _isCapturing.value = true
            
            Log.i(TAG, "Screen capture started")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture", e)
            _state.value = State.Error(e.message ?: "Failed to start capture")
            return false
        }
    }

    /**
     * Stop capturing the screen.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping screen capture")
        
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer", e)
        }
        
        _state.value = State.Idle
        _isCapturing.value = false
        
        Log.i(TAG, "Screen capture stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        Log.d(TAG, "Releasing ScreenCaptureManager")
        
        stopCapture()
        
        try {
            screenCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer", e)
        }
        screenCapturer = null
        
        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing surface texture helper", e)
        }
        surfaceTextureHelper = null
        
        videoSource = null
        mediaProjectionPermissionData = null
        
        _state.value = State.Idle
        _isCapturing.value = false
        
        Log.i(TAG, "ScreenCaptureManager released")
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }

    /**
     * Calculate scaled dimensions maintaining aspect ratio.
     * Ensures the output fits within maxWidth x maxHeight.
     */
    private fun calculateScaledDimensions(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        
        var width = maxWidth
        var height = (width / aspectRatio).toInt()
        
        if (height > maxHeight) {
            height = maxHeight
            width = (height * aspectRatio).toInt()
        }
        
        // Ensure dimensions are even (required by some encoders)
        width = width and 0xFFFE
        height = height and 0xFFFE
        
        return Pair(width, height)
    }
}

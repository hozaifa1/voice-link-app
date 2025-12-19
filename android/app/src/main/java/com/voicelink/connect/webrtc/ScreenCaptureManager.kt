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
        
        // SD quality settings (default - saves bandwidth)
        private const val SD_MAX_DIMENSION = 720
        private const val SD_FPS = 24
        
        // HD quality settings (premium - higher bandwidth)
        private const val HD_MAX_DIMENSION = 1080
        private const val HD_FPS = 30
    }
    
    // HD mode flag
    private var isHdMode = false

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
     * Set HD mode for screen capture.
     */
    fun setHdMode(enabled: Boolean) {
        isHdMode = enabled
        Log.d(TAG, "HD mode set to: $enabled")
    }

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
     * Preserves screen orientation (portrait/landscape) for proper display on receiver.
     */
    fun startCapture(): Boolean {
        Log.d(TAG, "Starting screen capture (HD mode: $isHdMode)")
        
        val capturer = screenCapturer
        if (capturer == null) {
            Log.e(TAG, "Screen capturer not initialized")
            _state.value = State.Error("Screen capturer not initialized")
            return false
        }

        try {
            // Get display metrics for resolution
            val displayMetrics = getDisplayMetrics()
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // Determine quality settings based on HD mode
            val maxDimension = if (isHdMode) HD_MAX_DIMENSION else SD_MAX_DIMENSION
            val fps = if (isHdMode) HD_FPS else SD_FPS
            
            // Calculate scaled dimensions preserving orientation
            // This ensures portrait stays portrait, landscape stays landscape
            val (width, height) = calculateScaledDimensionsPreservingOrientation(
                screenWidth,
                screenHeight,
                maxDimension
            )

            Log.d(TAG, "Starting capture at ${width}x${height} @ ${fps}fps (screen: ${screenWidth}x${screenHeight})")
            capturer.startCapture(width, height, fps)
            
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
     * Calculate scaled dimensions preserving the original orientation.
     * For portrait screens: height > width
     * For landscape screens: width > height
     * The longest dimension is scaled to maxDimension.
     */
    private fun calculateScaledDimensionsPreservingOrientation(
        originalWidth: Int,
        originalHeight: Int,
        maxDimension: Int
    ): Pair<Int, Int> {
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val isPortrait = originalHeight > originalWidth
        
        var width: Int
        var height: Int
        
        if (isPortrait) {
            // Portrait: scale height to max, calculate width
            height = maxDimension
            width = (height * aspectRatio).toInt()
        } else {
            // Landscape: scale width to max, calculate height
            width = maxDimension
            height = (width / aspectRatio).toInt()
        }
        
        // Ensure dimensions are even (required by some encoders)
        width = width and 0xFFFE
        height = height and 0xFFFE
        
        return Pair(width, height)
    }
}

package com.voicelink.connect.audio

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton manager for audio capture state.
 * Coordinates between UI, service, and HybridAudioSource.
 */
object AudioCaptureManager {
    private const val TAG = "AudioCaptureManager"

    sealed class CaptureState {
        data object Idle : CaptureState()
        data object WaitingForPermission : CaptureState()
        data object Starting : CaptureState()
        data object Capturing : CaptureState()
        data class Error(val message: String) : CaptureState()
        data object Stopped : CaptureState()
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _captureStats = MutableStateFlow(CaptureStats())
    val captureStats: StateFlow<CaptureStats> = _captureStats.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var hybridAudioSource: HybridAudioSource? = null
    private var captureStartTime: Long = 0L
    private var pcmBytesProcessed: Long = 0L
    private var scope: CoroutineScope? = null
    private var audioLevelJob: Job? = null

    data class CaptureStats(
        val durationMs: Long = 0L,
        val bytesProcessed: Long = 0L,
        val isCapturing: Boolean = false
    )

    fun requestMediaProjection() {
        _captureState.value = CaptureState.WaitingForPermission
    }

    fun onMediaProjectionResult(
        context: Context,
        resultCode: Int,
        data: Intent
    ): Boolean {
        Log.d(TAG, "MediaProjection result received: $resultCode")
        
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager

        return try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection != null) {
                Log.i(TAG, "MediaProjection obtained successfully")
                true
            } else {
                Log.e(TAG, "MediaProjection is null")
                _captureState.value = CaptureState.Error("Failed to obtain MediaProjection")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MediaProjection", e)
            _captureState.value = CaptureState.Error(e.message ?: "Unknown error")
            false
        }
    }

    fun startCapture(): Boolean {
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection not available")
            _captureState.value = CaptureState.Error("MediaProjection not available. Please grant permission.")
            return false
        }

        _captureState.value = CaptureState.Starting

        try {
            // Register MediaProjection callback for lifecycle events
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopCapture()
                }
            }, null)

            // Create and start HybridAudioSource
            hybridAudioSource = HybridAudioSource(projection).apply {
                onMixedPcmData = { data, length ->
                    pcmBytesProcessed += length
                    updateStats()
                }
            }

            // Collect audio level
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            hybridAudioSource?.let { source ->
                // Forward audio level updates
                audioLevelJob = scope?.launch {
                    source.audioLevel.collect { level ->
                        _audioLevel.value = level
                    }
                }
            }

            val started = hybridAudioSource?.start() == true
            if (started) {
                captureStartTime = System.currentTimeMillis()
                pcmBytesProcessed = 0L
                _captureState.value = CaptureState.Capturing
                Log.i(TAG, "Audio capture started")
                return true
            } else {
                _captureState.value = CaptureState.Error("Failed to start audio capture")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            _captureState.value = CaptureState.Error(e.message ?: "Unknown error")
            return false
        }
    }

    fun stopCapture() {
        Log.i(TAG, "Stopping capture")
        
        audioLevelJob?.cancel()
        audioLevelJob = null
        
        scope?.cancel()
        scope = null
        
        hybridAudioSource?.stop()
        hybridAudioSource = null

        mediaProjection?.stop()
        mediaProjection = null

        _audioLevel.value = 0f
        _captureState.value = CaptureState.Stopped
        updateStats()
    }

    fun reset() {
        stopCapture()
        _captureState.value = CaptureState.Idle
        _captureStats.value = CaptureStats()
    }

    private fun updateStats() {
        val duration = if (captureStartTime > 0 && _captureState.value == CaptureState.Capturing) {
            System.currentTimeMillis() - captureStartTime
        } else {
            0L
        }

        _captureStats.value = CaptureStats(
            durationMs = duration,
            bytesProcessed = pcmBytesProcessed,
            isCapturing = _captureState.value == CaptureState.Capturing
        )
    }

    fun isCapturing(): Boolean = _captureState.value == CaptureState.Capturing

    fun hasMediaProjection(): Boolean = mediaProjection != null
}

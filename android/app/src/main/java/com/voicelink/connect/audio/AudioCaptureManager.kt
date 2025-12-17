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
        data object CapturingMicOnly : CaptureState()  // Fallback for emulators
        data class Error(val message: String) : CaptureState()
        data object Stopped : CaptureState()
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _captureStats = MutableStateFlow(CaptureStats())
    val captureStats: StateFlow<CaptureStats> = _captureStats.asStateFlow()

    private val _warningMessage = MutableStateFlow<String?>(null)
    val warningMessage: StateFlow<String?> = _warningMessage.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var hybridAudioSource: HybridAudioSource? = null
    private var captureStartTime: Long = 0L
    private var pcmBytesProcessed: Long = 0L
    private var scope: CoroutineScope? = null
    private var audioLevelJob: Job? = null
    private var errorJob: Job? = null

    data class CaptureStats(
        val durationMs: Long = 0L,
        val bytesProcessed: Long = 0L,
        val isCapturing: Boolean = false
    )

    fun requestMediaProjection() {
        _captureState.value = CaptureState.WaitingForPermission
    }

    /**
     * Start mic-only capture without MediaProjection.
     * Useful for testing on emulators.
     */
    fun startMicOnlyCapture(): Boolean {
        Log.d(TAG, "startMicOnlyCapture() called")
        mediaProjection = null  // Ensure no projection
        return startCapture()
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
        Log.d(TAG, "startCapture() called, mediaProjection=$mediaProjection")
        
        _captureState.value = CaptureState.Starting
        
        try {
            val projection = mediaProjection
            
            // If we have MediaProjection, register callback
            // Note: On emulators, onStop may be called immediately - we handle this gracefully
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection.Callback.onStop() called")
                    // Only stop if we're actually capturing, not if we're still starting
                    if (_captureState.value == CaptureState.Capturing) {
                        Log.i(TAG, "Stopping capture due to MediaProjection stop")
                        stopCapture()
                    } else {
                        Log.w(TAG, "Ignoring onStop - not in Capturing state (state=${_captureState.value})")
                    }
                }
            }, null)

            // Create audio source - will fall back to mic-only if no projection
            hybridAudioSource = HybridAudioSource(projection).apply {
                onMixedPcmData = { data, length ->
                    pcmBytesProcessed += length
                    updateStats()
                }
            }

            // Collect audio level and errors
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            hybridAudioSource?.let { source ->
                // Forward audio level updates
                audioLevelJob = scope?.launch {
                    source.audioLevel.collect { level ->
                        _audioLevel.value = level
                    }
                }
                // Forward error/warning messages
                errorJob = scope?.launch {
                    source.lastError.collect { error ->
                        _warningMessage.value = error
                    }
                }
            }

            Log.d(TAG, "Starting HybridAudioSource...")
            val started = hybridAudioSource?.start() == true
            Log.d(TAG, "HybridAudioSource.start() returned: $started")
            
            if (started) {
                captureStartTime = System.currentTimeMillis()
                pcmBytesProcessed = 0L
                // Check if we're in mic-only mode
                val sourceState = hybridAudioSource?.state?.value
                Log.d(TAG, "HybridAudioSource state: $sourceState")
                val isMicOnly = sourceState is HybridAudioSource.State.CapturingMicOnly
                _captureState.value = if (isMicOnly) CaptureState.CapturingMicOnly else CaptureState.Capturing
                Log.i(TAG, "Audio capture started successfully (micOnly=$isMicOnly)")
                return true
            } else {
                val errorMsg = hybridAudioSource?.lastError?.value ?: "Failed to start audio capture"
                Log.e(TAG, "Failed to start: $errorMsg")
                _captureState.value = CaptureState.Error(errorMsg)
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
        
        errorJob?.cancel()
        errorJob = null
        
        scope?.cancel()
        scope = null
        
        hybridAudioSource?.stop()
        hybridAudioSource = null

        mediaProjection?.stop()
        mediaProjection = null

        _audioLevel.value = 0f
        _warningMessage.value = null
        _captureState.value = CaptureState.Stopped
        updateStats()
    }

    fun reset() {
        stopCapture()
        _captureState.value = CaptureState.Idle
        _captureStats.value = CaptureStats()
    }

    private fun updateStats() {
        val isActive = _captureState.value == CaptureState.Capturing || 
                       _captureState.value == CaptureState.CapturingMicOnly
        val duration = if (captureStartTime > 0 && isActive) {
            System.currentTimeMillis() - captureStartTime
        } else {
            0L
        }

        _captureStats.value = CaptureStats(
            durationMs = duration,
            bytesProcessed = pcmBytesProcessed,
            isCapturing = isActive
        )
    }

    fun isCapturing(): Boolean = _captureState.value == CaptureState.Capturing || 
                                 _captureState.value == CaptureState.CapturingMicOnly

    fun hasMediaProjection(): Boolean = mediaProjection != null
}

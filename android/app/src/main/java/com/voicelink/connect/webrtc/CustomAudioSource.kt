package com.voicelink.connect.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.voicelink.connect.audio.AudioCaptureManager
import com.voicelink.connect.audio.AudioConfig
import com.voicelink.connect.audio.HybridAudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Custom audio source that captures both mic and system audio via MediaProjection.
 * This integrates HybridAudioSource with WebRTC's audio pipeline.
 */
class CustomAudioSource(private val context: Context) {
    companion object {
        private const val TAG = "CustomAudioSource"
    }

    sealed class State {
        data object Idle : State()
        data object MicOnly : State()
        data object FullCapture : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var hybridAudioSource: HybridAudioSource? = null
    private var mediaProjection: MediaProjection? = null
    
    // Buffer to hold mixed audio data for WebRTC
    private val audioBuffer = ByteArray(AudioConfig.BUFFER_SIZE_BYTES)
    private var bufferReadPos = 0
    private var bufferWritePos = 0
    private var bufferAvailable = 0
    private val bufferLock = Object()

    /**
     * Initialize with MediaProjection for system audio capture.
     * Call this after receiving MediaProjection permission.
     */
    fun initWithMediaProjection(resultCode: Int, data: Intent): Boolean {
        Log.d(TAG, "Initializing with MediaProjection")
        
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection != null) {
                Log.i(TAG, "MediaProjection obtained successfully")
                startCapture()
                return true
            } else {
                Log.e(TAG, "MediaProjection is null")
                _state.value = State.Error("Failed to obtain MediaProjection")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MediaProjection", e)
            _state.value = State.Error(e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Start mic-only capture (no system audio).
     */
    fun startMicOnly(): Boolean {
        Log.d(TAG, "Starting mic-only capture")
        mediaProjection = null
        return startCapture()
    }

    private fun startCapture(): Boolean {
        try {
            hybridAudioSource = HybridAudioSource(mediaProjection).apply {
                onMixedPcmData = { data, length ->
                    // Write mixed audio to buffer for WebRTC to read
                    synchronized(bufferLock) {
                        val toCopy = minOf(length, audioBuffer.size - bufferAvailable)
                        if (toCopy > 0) {
                            System.arraycopy(data, 0, audioBuffer, bufferWritePos, toCopy)
                            bufferWritePos = (bufferWritePos + toCopy) % audioBuffer.size
                            bufferAvailable += toCopy
                        }
                    }
                }
            }

            val started = hybridAudioSource?.start() == true
            if (started) {
                val isMicOnly = hybridAudioSource?.state?.value is HybridAudioSource.State.CapturingMicOnly
                _state.value = if (isMicOnly) State.MicOnly else State.FullCapture
                Log.i(TAG, "Audio capture started (micOnly=$isMicOnly)")
                return true
            } else {
                _state.value = State.Error("Failed to start audio capture")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            _state.value = State.Error(e.message ?: "Unknown error")
            return false
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping audio capture")
        hybridAudioSource?.stop()
        hybridAudioSource = null
        mediaProjection?.stop()
        mediaProjection = null
        _state.value = State.Idle
        
        synchronized(bufferLock) {
            bufferReadPos = 0
            bufferWritePos = 0
            bufferAvailable = 0
        }
    }

    /**
     * Read audio data for WebRTC. Called by custom audio record.
     */
    fun readAudioData(buffer: ByteArray, length: Int): Int {
        synchronized(bufferLock) {
            val toRead = minOf(length, bufferAvailable)
            if (toRead > 0) {
                System.arraycopy(audioBuffer, bufferReadPos, buffer, 0, toRead)
                bufferReadPos = (bufferReadPos + toRead) % audioBuffer.size
                bufferAvailable -= toRead
            }
            return toRead
        }
    }

    fun isCapturing(): Boolean = _state.value == State.MicOnly || _state.value == State.FullCapture
    
    fun hasSystemAudio(): Boolean = _state.value == State.FullCapture
}

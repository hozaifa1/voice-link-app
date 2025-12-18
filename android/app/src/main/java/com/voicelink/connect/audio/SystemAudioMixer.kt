package com.voicelink.connect.audio

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * SystemAudioMixer captures system audio via AudioPlaybackCapture API and provides
 * mixing capability to inject system audio into WebRTC's audio pipeline.
 * 
 * This class is designed to work with stream-webrtc-android's AudioRecordDataCallback
 * to mix system audio with microphone audio in real-time.
 * 
 * Requirements:
 * - Android 10 (API 29) or higher
 * - MediaProjection permission
 * - Foreground service with mediaProjection foreground service type
 */
class SystemAudioMixer(private val context: Context) {
    companion object {
        private const val TAG = "SystemAudioMixer"
        
        // Audio configuration matching WebRTC defaults
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 1  // WebRTC typically uses mono for voice
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        
        // Buffer for ~20ms of audio (WebRTC typically uses 10-20ms frames)
        private const val BUFFER_SIZE_MS = 20
        private val BUFFER_SIZE_BYTES = (SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE * BUFFER_SIZE_MS) / 1000
        
        // System audio volume relative to mic (0.0 to 1.0)
        // Can be adjusted for balance between mic and system audio
        private const val SYSTEM_AUDIO_GAIN = 0.8f
        private const val MIC_AUDIO_GAIN = 1.0f
    }

    sealed class State {
        data object Idle : State()
        data object Capturing : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var systemAudioRecord: AudioRecord? = null
    
    // Pre-allocated buffer for reading system audio
    private var systemAudioBuffer: ByteBuffer? = null
    private val bufferLock = Object()

    /**
     * Initialize the system audio capture with MediaProjection.
     * Call this after receiving MediaProjection permission from the user.
     * 
     * @param resultCode The result code from the MediaProjection permission request
     * @param data The Intent data from the MediaProjection permission request
     * @return true if initialization was successful, false otherwise
     */
    @SuppressLint("MissingPermission")
    fun initialize(resultCode: Int, data: Intent): Boolean {
        Log.d(TAG, "Initializing SystemAudioMixer")
        
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection")
                _state.value = State.Error("Failed to get MediaProjection")
                return false
            }
            
            // Create AudioPlaybackCaptureConfiguration
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            // Create AudioFormat matching WebRTC's expected format
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            
            // Calculate minimum buffer size
            val minBufferSize = max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT),
                BUFFER_SIZE_BYTES * 2
            )
            
            // Create AudioRecord for system audio capture
            systemAudioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize)
                .build()
            
            if (systemAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord for system audio")
                release()
                _state.value = State.Error("Failed to initialize system audio capture")
                return false
            }
            
            // Pre-allocate buffer for system audio
            systemAudioBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE_BYTES * 2)
                .order(ByteOrder.nativeOrder())
            
            // Start recording
            systemAudioRecord?.startRecording()
            _state.value = State.Capturing
            _isActive.value = true
            
            Log.i(TAG, "SystemAudioMixer initialized successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SystemAudioMixer", e)
            release()
            _state.value = State.Error(e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Mix system audio into the provided mic audio buffer.
     * This method is designed to be called from AudioRecordDataCallback.onAudioDataRecorded()
     * 
     * The buffer is modified in-place with the mixed audio.
     * 
     * @param micBuffer The ByteBuffer containing mic audio data from WebRTC
     * @param bytesRead The number of bytes in the mic buffer
     */
    fun mixIntoBuffer(micBuffer: ByteBuffer, bytesRead: Int) {
        if (_state.value !is State.Capturing || systemAudioRecord == null) {
            return
        }
        
        synchronized(bufferLock) {
            val sysBuffer = systemAudioBuffer ?: return
            sysBuffer.clear()
            
            // Read system audio (non-blocking to avoid audio glitches)
            val systemBytesRead = systemAudioRecord?.read(sysBuffer, bytesRead, AudioRecord.READ_NON_BLOCKING) ?: 0
            
            if (systemBytesRead <= 0) {
                // No system audio available, keep original mic audio
                return
            }
            
            // Mix the audio
            mixPcm16Buffers(micBuffer, sysBuffer, bytesRead, systemBytesRead)
        }
    }

    /**
     * Mix two PCM 16-bit audio buffers with gain control and clipping protection.
     * The result is written back to buffer1.
     */
    private fun mixPcm16Buffers(
        micBuffer: ByteBuffer,
        systemBuffer: ByteBuffer,
        micBytes: Int,
        systemBytes: Int
    ) {
        val samplesToMix = min(micBytes, systemBytes) / BYTES_PER_SAMPLE
        
        micBuffer.rewind()
        systemBuffer.rewind()
        
        for (i in 0 until samplesToMix) {
            // Read samples as 16-bit signed integers
            val micSample = micBuffer.getShort(i * 2).toInt()
            val systemSample = systemBuffer.getShort(i * 2).toInt()
            
            // Apply gain and mix
            val mixedSample = (micSample * MIC_AUDIO_GAIN + systemSample * SYSTEM_AUDIO_GAIN).toInt()
            
            // Clip to 16-bit range
            val clippedSample = max(-32768, min(32767, mixedSample)).toShort()
            
            // Write back to mic buffer
            micBuffer.putShort(i * 2, clippedSample)
        }
        
        // Reset buffer position
        micBuffer.rewind()
    }

    /**
     * Release all resources.
     */
    fun release() {
        Log.d(TAG, "Releasing SystemAudioMixer")
        
        synchronized(bufferLock) {
            try {
                systemAudioRecord?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
            systemAudioRecord = null
            
            try {
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaProjection", e)
            }
            mediaProjection = null
            
            systemAudioBuffer = null
        }
        
        _state.value = State.Idle
        _isActive.value = false
        Log.i(TAG, "SystemAudioMixer released")
    }
}

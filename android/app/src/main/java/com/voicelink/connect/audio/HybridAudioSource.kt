package com.voicelink.connect.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * HybridAudioSource captures and mixes:
 * - Mic audio (VOICE_COMMUNICATION for AEC)
 * - System audio (AudioPlaybackCapture via MediaProjection)
 * 
 * Mixed PCM is available via callback for WebRTC or other consumers.
 * No audio is stored to disk.
 * 
 * On emulators where AudioPlaybackCapture fails, falls back to mic-only mode.
 */
class HybridAudioSource(
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "HybridAudioSource"
    }

    sealed class State {
        data object Idle : State()
        data object Starting : State()
        data object Capturing : State()
        data object CapturingMicOnly : State()  // Fallback mode
        data class Error(val message: String) : State()
        data object Stopped : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var micRecorder: AudioRecord? = null
    private var systemRecorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var scope: CoroutineScope? = null
    private var micOnlyMode = false

    // Callback for mixed PCM data (for WebRTC)
    var onMixedPcmData: ((ByteArray, Int) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_state.value == State.Capturing || _state.value == State.CapturingMicOnly) {
            Log.w(TAG, "Already capturing")
            return true
        }

        _state.value = State.Starting
        _lastError.value = null
        micOnlyMode = false
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            // Initialize mic recorder with VOICE_COMMUNICATION for AEC
            Log.d(TAG, "Creating mic recorder...")
            micRecorder = createMicRecorder()
            if (micRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Failed to initialize mic recorder. State: ${micRecorder?.state}")
            }
            Log.d(TAG, "Mic recorder initialized successfully")

            // Try to initialize system audio recorder via AudioPlaybackCapture
            try {
                Log.d(TAG, "Creating system audio recorder...")
                systemRecorder = createSystemRecorder()
                if (systemRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("System recorder state: ${systemRecorder?.state}")
                }
                Log.d(TAG, "System audio recorder initialized successfully")
            } catch (e: Exception) {
                // System audio capture failed - fall back to mic only
                Log.w(TAG, "System audio capture not available (likely emulator): ${e.message}")
                _lastError.value = "System audio not available: ${e.message}\nUsing mic-only mode."
                micOnlyMode = true
                systemRecorder?.release()
                systemRecorder = null
            }

            // Start recorders
            micRecorder?.startRecording()
            if (!micOnlyMode) {
                systemRecorder?.startRecording()
            }

            // Start the capture loop
            startCaptureLoop()

            _state.value = if (micOnlyMode) State.CapturingMicOnly else State.Capturing
            Log.i(TAG, "Audio capture started successfully (micOnlyMode=$micOnlyMode)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            _lastError.value = e.message ?: "Unknown error"
            _state.value = State.Error(e.message ?: "Unknown error")
            stop()
            return false
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping audio capture")
        
        captureJob?.cancel()
        captureJob = null

        scope?.cancel()
        scope = null

        micRecorder?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mic recorder", e)
            }
        }
        micRecorder = null

        systemRecorder?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping system recorder", e)
            }
        }
        systemRecorder = null

        _audioLevel.value = 0f
        _state.value = State.Stopped
        Log.i(TAG, "Audio capture stopped")
    }

    @SuppressLint("MissingPermission")
    private fun createMicRecorder(): AudioRecord {
        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_CONFIG_IN,
                AudioConfig.AUDIO_FORMAT
            ),
            AudioConfig.BUFFER_SIZE_BYTES * 2
        )

        return AudioRecord(
            AudioConfig.MIC_AUDIO_SOURCE,
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG_IN,
            AudioConfig.AUDIO_FORMAT,
            bufferSize
        )
    }

    @SuppressLint("MissingPermission")
    private fun createSystemRecorder(): AudioRecord {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioConfig.AUDIO_FORMAT)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioConfig.CHANNEL_CONFIG_IN)
            .build()

        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_CONFIG_IN,
                AudioConfig.AUDIO_FORMAT
            ),
            AudioConfig.BUFFER_SIZE_BYTES * 2
        )

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun startCaptureLoop() {
        val bufferSize = AudioConfig.BUFFER_SIZE_BYTES
        val micBuffer = ByteArray(bufferSize)
        val systemBuffer = ByteArray(bufferSize)
        val mixedBuffer = ByteArray(bufferSize)

        captureJob = scope?.launch {
            Log.d(TAG, "Capture loop started with buffer size: $bufferSize bytes, micOnlyMode=$micOnlyMode")
            
            while (isActive && (_state.value == State.Capturing || _state.value == State.CapturingMicOnly)) {
                try {
                    if (micOnlyMode) {
                        // Mic-only mode (emulator fallback)
                        val micRead = micRecorder?.read(micBuffer, 0, bufferSize) ?: 0
                        if (micRead > 0) {
                            updateAudioLevel(micBuffer, micRead)
                            onMixedPcmData?.invoke(micBuffer, micRead)
                        } else if (micRead < 0) {
                            Log.e(TAG, "Mic read error: $micRead")
                        }
                    } else {
                        // Full hybrid mode - read from both sources
                        val micRead = micRecorder?.read(micBuffer, 0, bufferSize) ?: 0
                        val systemRead = systemRecorder?.read(systemBuffer, 0, bufferSize) ?: 0

                        if (micRead < 0 || systemRead < 0) {
                            Log.e(TAG, "Read error: mic=$micRead, system=$systemRead")
                            continue
                        }

                        // Mix the audio (both are PCM 16-bit)
                        val bytesToMix = min(micRead, systemRead)
                        if (bytesToMix > 0) {
                            mixPcm16(micBuffer, systemBuffer, mixedBuffer, bytesToMix)
                            updateAudioLevel(mixedBuffer, bytesToMix)
                            onMixedPcmData?.invoke(mixedBuffer, bytesToMix)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture loop", e)
                }
            }
            Log.d(TAG, "Capture loop ended")
        }
    }

    /**
     * Mix two PCM 16-bit audio buffers with clipping protection
     */
    private fun mixPcm16(
        buffer1: ByteArray,
        buffer2: ByteArray,
        output: ByteArray,
        length: Int
    ) {
        // Process 2 bytes at a time (16-bit samples)
        var i = 0
        while (i < length - 1) {
            // Convert bytes to 16-bit samples (little-endian)
            val sample1 = (buffer1[i].toInt() and 0xFF) or (buffer1[i + 1].toInt() shl 8)
            val sample2 = (buffer2[i].toInt() and 0xFF) or (buffer2[i + 1].toInt() shl 8)

            // Sign-extend to 32-bit for mixing
            val s1 = if (sample1 > 32767) sample1 - 65536 else sample1
            val s2 = if (sample2 > 32767) sample2 - 65536 else sample2

            // Mix with clipping
            var mixed = s1 + s2
            mixed = max(-32768, min(32767, mixed))

            // Convert back to bytes (little-endian)
            output[i] = (mixed and 0xFF).toByte()
            output[i + 1] = ((mixed shr 8) and 0xFF).toByte()

            i += 2
        }
    }

    /**
     * Calculate RMS audio level for UI visualization
     */
    private fun updateAudioLevel(buffer: ByteArray, length: Int) {
        var sumSquares = 0L
        var i = 0
        while (i < length - 1) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sumSquares += signedSample.toLong() * signedSample.toLong()
            i += 2
        }

        val numSamples = length / 2
        if (numSamples > 0) {
            val rms = kotlin.math.sqrt(sumSquares.toDouble() / numSamples)
            // Normalize to 0-1 range (32768 is max amplitude)
            val level = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
            _audioLevel.value = level
        }
    }
}

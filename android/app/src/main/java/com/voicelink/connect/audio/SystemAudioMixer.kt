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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * SystemAudioMixer captures system audio via AudioPlaybackCapture API and provides
 * priority-based audio switching for WebRTC's audio pipeline.
 * 
 * Behavior:
 * - When system audio is playing (above silence threshold): transmit ONLY system audio
 * - When system audio is silent: transmit ONLY microphone audio
 * 
 * Key fixes for audio quality:
 * - Captures system audio in STEREO (what apps output) and converts to MONO
 * - Matches WebRTC's actual sample rate from the callback
 * - Proper ByteBuffer handling with correct byte order
 * 
 * Requirements:
 * - Android 10 (API 29) or higher
 * - MediaProjection permission
 * - Foreground service with mediaProjection foreground service type
 */
class SystemAudioMixer(private val context: Context) {
    companion object {
        private const val TAG = "SystemAudioMixer"
        
        // Audio configuration - capture in STEREO since apps output stereo
        private const val SAMPLE_RATE = 48000
        private const val CAPTURE_CHANNELS = 2  // Stereo capture
        private const val OUTPUT_CHANNELS = 1   // Mono output for WebRTC
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        
        // Buffer size for ~20ms of stereo audio
        private const val BUFFER_SIZE_MS = 20
        private val STEREO_BUFFER_SIZE_BYTES = (SAMPLE_RATE * CAPTURE_CHANNELS * BYTES_PER_SAMPLE * BUFFER_SIZE_MS) / 1000
        private val MONO_BUFFER_SIZE_BYTES = (SAMPLE_RATE * OUTPUT_CHANNELS * BYTES_PER_SAMPLE * BUFFER_SIZE_MS) / 1000
        
        // Silence detection threshold (RMS value, 0-32768 range)
        // Values below this are considered silence
        private const val SILENCE_THRESHOLD_RMS = 150
        
        // Number of consecutive silent frames before switching to mic
        private const val SILENCE_FRAMES_THRESHOLD = 3
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
    
    // Tracks whether system audio is currently being transmitted
    private val _isSystemAudioPlaying = MutableStateFlow(false)
    val isSystemAudioPlaying: StateFlow<Boolean> = _isSystemAudioPlaying.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var systemAudioRecord: AudioRecord? = null
    
    // Pre-allocated buffers
    private var stereoBuffer: ByteArray? = null      // For reading stereo system audio
    private var monoBuffer: ByteArray? = null        // For converted mono audio
    private val bufferLock = Object()
    
    // Silence detection state
    private var consecutiveSilentFrames = 0

    /**
     * Initialize the system audio capture with MediaProjection.
     * Call this after receiving MediaProjection permission from the user.
     */
    @SuppressLint("MissingPermission")
    fun initialize(resultCode: Int, data: Intent): Boolean {
        Log.d(TAG, "Initializing SystemAudioMixer with intent")
        
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
            
            if (projection == null) {
                Log.e(TAG, "Failed to get MediaProjection")
                _state.value = State.Error("Failed to get MediaProjection")
                return false
            }
            
            return initializeWithProjection(projection)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SystemAudioMixer", e)
            release()
            _state.value = State.Error(e.message ?: "Unknown error")
            return false
        }
    }
    
    /**
     * Initialize the system audio capture with an existing MediaProjection.
     * Use this when sharing a MediaProjection with screen capture.
     */
    @SuppressLint("MissingPermission")
    fun initializeWithProjection(projection: MediaProjection): Boolean {
        Log.d(TAG, "Initializing SystemAudioMixer with shared projection")
        
        try {
            mediaProjection = projection
            
            // Create AudioPlaybackCaptureConfiguration
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            // Capture in STEREO - this is what apps actually output
            // We'll convert to mono later to match WebRTC's format
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            
            // Calculate minimum buffer size for stereo
            val minBufferSize = max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AUDIO_FORMAT),
                STEREO_BUFFER_SIZE_BYTES * 4
            )
            
            Log.d(TAG, "Creating AudioRecord: sampleRate=$SAMPLE_RATE, stereo, bufferSize=$minBufferSize")
            
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
            
            // Pre-allocate buffers
            stereoBuffer = ByteArray(STEREO_BUFFER_SIZE_BYTES * 2)
            monoBuffer = ByteArray(MONO_BUFFER_SIZE_BYTES * 2)
            
            // Start recording
            systemAudioRecord?.startRecording()
            _state.value = State.Capturing
            _isActive.value = true
            consecutiveSilentFrames = 0
            
            Log.i(TAG, "SystemAudioMixer initialized successfully (stereo capture -> mono output)")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SystemAudioMixer with projection", e)
            release()
            _state.value = State.Error(e.message ?: "Unknown error")
            return false
        }
    }

    /**
     * Process audio for WebRTC transmission.
     * 
     * Priority-based switching:
     * - If system audio is playing: replace mic buffer with system audio
     * - If system audio is silent: keep original mic buffer
     * 
     * @param micBuffer The ByteBuffer containing mic audio data from WebRTC
     * @param bytesInBuffer The number of bytes of audio data
     * @param channelCount The number of channels (from WebRTC callback)
     * @param sampleRate The sample rate (from WebRTC callback)
     */
    fun processAudioBuffer(
        micBuffer: ByteBuffer,
        bytesInBuffer: Int,
        channelCount: Int,
        sampleRate: Int
    ) {
        if (_state.value !is State.Capturing || systemAudioRecord == null) {
            return
        }
        
        synchronized(bufferLock) {
            val stereoBuf = stereoBuffer ?: return
            val monoBuf = monoBuffer ?: return
            
            // Calculate how many stereo bytes we need to produce the required mono bytes
            // WebRTC typically sends mono (channelCount=1)
            val monoSamplesNeeded = bytesInBuffer / BYTES_PER_SAMPLE
            val stereoSamplesNeeded = monoSamplesNeeded  // Same number of samples, but 2 channels
            val stereoBytesNeeded = stereoSamplesNeeded * CAPTURE_CHANNELS * BYTES_PER_SAMPLE
            
            // Read stereo system audio
            val stereoBytesRead = systemAudioRecord?.read(
                stereoBuf, 0, min(stereoBytesNeeded, stereoBuf.size), 
                AudioRecord.READ_NON_BLOCKING
            ) ?: 0
            
            if (stereoBytesRead <= 0) {
                // No system audio available
                consecutiveSilentFrames++
                if (consecutiveSilentFrames > SILENCE_FRAMES_THRESHOLD) {
                    _isSystemAudioPlaying.value = false
                }
                return  // Keep original mic audio
            }
            
            // Convert stereo to mono
            val monoBytesConverted = stereoToMono(stereoBuf, monoBuf, stereoBytesRead)
            
            if (monoBytesConverted <= 0) {
                consecutiveSilentFrames++
                if (consecutiveSilentFrames > SILENCE_FRAMES_THRESHOLD) {
                    _isSystemAudioPlaying.value = false
                }
                return
            }
            
            // Calculate RMS to detect silence
            val rms = calculateRms(monoBuf, monoBytesConverted)
            
            if (rms < SILENCE_THRESHOLD_RMS) {
                // System audio is silent, keep mic audio
                consecutiveSilentFrames++
                if (consecutiveSilentFrames > SILENCE_FRAMES_THRESHOLD) {
                    _isSystemAudioPlaying.value = false
                }
                return
            }
            
            // System audio is playing - replace mic buffer with system audio
            consecutiveSilentFrames = 0
            _isSystemAudioPlaying.value = true
            
            // Copy mono system audio to mic buffer
            val bytesToCopy = min(monoBytesConverted, bytesInBuffer)
            micBuffer.clear()
            micBuffer.put(monoBuf, 0, bytesToCopy)
            
            // If we have less system audio than needed, pad with zeros
            if (bytesToCopy < bytesInBuffer) {
                val padding = ByteArray(bytesInBuffer - bytesToCopy)
                micBuffer.put(padding)
            }
            
            micBuffer.rewind()
        }
    }

    /**
     * Convert stereo PCM16 to mono by averaging left and right channels.
     * Returns the number of mono bytes written.
     */
    private fun stereoToMono(stereoData: ByteArray, monoData: ByteArray, stereoBytes: Int): Int {
        val stereoSamples = stereoBytes / (CAPTURE_CHANNELS * BYTES_PER_SAMPLE)
        var monoIndex = 0
        
        for (i in 0 until stereoSamples) {
            val stereoIndex = i * CAPTURE_CHANNELS * BYTES_PER_SAMPLE
            
            // Read left channel (little-endian)
            val leftSample = ((stereoData[stereoIndex + 1].toInt() shl 8) or 
                             (stereoData[stereoIndex].toInt() and 0xFF)).toShort().toInt()
            
            // Read right channel (little-endian)  
            val rightSample = ((stereoData[stereoIndex + 3].toInt() shl 8) or 
                              (stereoData[stereoIndex + 2].toInt() and 0xFF)).toShort().toInt()
            
            // Average to mono
            val monoSample = ((leftSample + rightSample) / 2).toShort()
            
            // Write mono sample (little-endian)
            monoData[monoIndex] = (monoSample.toInt() and 0xFF).toByte()
            monoData[monoIndex + 1] = ((monoSample.toInt() shr 8) and 0xFF).toByte()
            monoIndex += BYTES_PER_SAMPLE
        }
        
        return monoIndex
    }

    /**
     * Calculate RMS (Root Mean Square) of audio data for silence detection.
     * Returns a value in the range 0-32768.
     */
    private fun calculateRms(data: ByteArray, length: Int): Int {
        if (length < 2) return 0
        
        var sumSquares = 0L
        val samples = length / BYTES_PER_SAMPLE
        
        for (i in 0 until samples) {
            val index = i * BYTES_PER_SAMPLE
            val sample = ((data[index + 1].toInt() shl 8) or 
                         (data[index].toInt() and 0xFF)).toShort().toInt()
            sumSquares += sample.toLong() * sample.toLong()
        }
        
        return sqrt(sumSquares.toDouble() / samples).toInt()
    }

    /**
     * Legacy method for backward compatibility.
     * Calls processAudioBuffer with default parameters.
     */
    fun mixIntoBuffer(micBuffer: ByteBuffer, bytesRead: Int) {
        processAudioBuffer(micBuffer, bytesRead, OUTPUT_CHANNELS, SAMPLE_RATE)
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
            
            stereoBuffer = null
            monoBuffer = null
        }
        
        _state.value = State.Idle
        _isActive.value = false
        _isSystemAudioPlaying.value = false
        consecutiveSilentFrames = 0
        Log.i(TAG, "SystemAudioMixer released")
    }
}

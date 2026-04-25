package com.streamsync.app.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

class HornSoundPlayer {
    companion object {
        private const val TAG = "HornSoundPlayer"
        private const val SAMPLE_RATE = 48000
        private const val BYTES_PER_SAMPLE = 2
        private const val HORN_DURATION_MS = 800
        private const val HORN_FREQUENCY_HZ = 440.0
        private const val HORN_FREQUENCY_2_HZ = 554.37
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var hornSoundData: ShortArray? = null
    private var currentPosition = 0
    private val bufferLock = Object()
    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        generateHornSound()
    }

    private fun generateHornSound() {
        val totalSamples = (SAMPLE_RATE * HORN_DURATION_MS) / 1000
        hornSoundData = ShortArray(totalSamples)
        
        for (i in 0 until totalSamples) {
            val time = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / totalSamples
            
            val freq1 = HORN_FREQUENCY_HZ
            val freq2 = HORN_FREQUENCY_2_HZ
            
            val wave1 = sin(2 * PI * freq1 * time)
            val wave2 = sin(2 * PI * freq2 * time)
            val combinedWave = (wave1 + wave2) / 2.0
            
            val envelope = when {
                progress < 0.1 -> progress / 0.1
                progress > 0.7 -> (1.0 - progress) / 0.3
                else -> 1.0
            }
            
            val amplitude = (combinedWave * envelope * 16000).toInt()
            hornSoundData!![i] = amplitude.coerceIn(-32768, 32767).toShort()
        }
        
        Log.d(TAG, "Horn sound generated: $totalSamples samples, ${HORN_DURATION_MS}ms duration")
    }

    fun playHornSound() {
        if (_isPlaying.value) {
            Log.d(TAG, "Horn already playing, ignoring request")
            return
        }

        synchronized(bufferLock) {
            currentPosition = 0
            _isPlaying.value = true
        }

        playJob?.cancel()
        playJob = scope.launch {
            try {
                delay(HORN_DURATION_MS.toLong() + 100)
                synchronized(bufferLock) {
                    _isPlaying.value = false
                    currentPosition = 0
                }
                Log.d(TAG, "Horn sound finished playing")
            } catch (e: Exception) {
                Log.e(TAG, "Error in horn playback", e)
                synchronized(bufferLock) {
                    _isPlaying.value = false
                    currentPosition = 0
                }
            }
        }

        Log.d(TAG, "Horn sound started")
    }

    fun processAudioBuffer(
        micBuffer: ByteBuffer,
        bytesInBuffer: Int,
        channelCount: Int,
        sampleRate: Int
    ): Boolean {
        if (!_isPlaying.value) {
            return false
        }

        synchronized(bufferLock) {
            val hornData = hornSoundData ?: return false
            val samplesNeeded = bytesInBuffer / BYTES_PER_SAMPLE
            
            if (currentPosition >= hornData.size) {
                _isPlaying.value = false
                currentPosition = 0
                return false
            }

            micBuffer.clear()
            
            var samplesWritten = 0
            while (samplesWritten < samplesNeeded && currentPosition < hornData.size) {
                val sample = hornData[currentPosition]
                micBuffer.put((sample.toInt() and 0xFF).toByte())
                micBuffer.put(((sample.toInt() shr 8) and 0xFF).toByte())
                currentPosition++
                samplesWritten++
            }

            if (samplesWritten < samplesNeeded) {
                val padding = ByteArray((samplesNeeded - samplesWritten) * BYTES_PER_SAMPLE)
                micBuffer.put(padding)
            }

            micBuffer.rewind()
            return true
        }
    }

    fun release() {
        playJob?.cancel()
        synchronized(bufferLock) {
            _isPlaying.value = false
            currentPosition = 0
            hornSoundData = null
        }
        Log.d(TAG, "HornSoundPlayer released")
    }
}

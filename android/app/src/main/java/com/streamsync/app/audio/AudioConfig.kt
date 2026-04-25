package com.streamsync.app.audio

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE = 48000
    const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    
    const val BUFFER_SIZE_MS = 10
    
    val BUFFER_SIZE_BYTES: Int
        get() = (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * BUFFER_SIZE_MS) / 1000
    
    const val MIC_AUDIO_SOURCE = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
}

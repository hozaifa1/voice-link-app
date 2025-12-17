package com.voicelink.connect.webrtc

object WebRtcConfig {
    
    val ICE_SERVERS = listOf(
        IceServer(
            urls = listOf("stun:stun.l.google.com:19302"),
            username = null,
            credential = null
        ),
        IceServer(
            urls = listOf("stun:stun1.l.google.com:19302"),
            username = null,
            credential = null
        ),
        IceServer(
            urls = listOf("stun:stun2.l.google.com:19302"),
            username = null,
            credential = null
        )
    )

    const val AUDIO_CODEC = "opus"
    const val AUDIO_SAMPLE_RATE = 48000
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BIT_RATE = 128000

    const val BUFFER_SIZE_MS = 10
}

data class IceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

package com.voicelink.connect.webrtc

object WebRtcConfig {
    
    val ICE_SERVERS = listOf(
        // STUN servers for NAT traversal
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
        // Free TURN server from OpenRelay (for NAT traversal)
        // Works on real devices; emulator-to-emulator has network limitations
        IceServer(
            urls = listOf(
                "turn:openrelay.metered.ca:80",
                "turn:openrelay.metered.ca:443",
                "turn:openrelay.metered.ca:443?transport=tcp"
            ),
            username = "openrelayproject",
            credential = "openrelayproject"
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

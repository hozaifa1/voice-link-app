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
        // Free public TURN servers from Metered (for testing/development)
        // These are required for emulator-to-emulator connections
        IceServer(
            urls = listOf(
                "turn:a.relay.metered.ca:80",
                "turn:a.relay.metered.ca:80?transport=tcp",
                "turn:a.relay.metered.ca:443",
                "turns:a.relay.metered.ca:443"
            ),
            username = "e8dd65c92f8d9b7a47e4d810",
            credential = "uWdEiPiN/kKOb2Jq"
        ),
        IceServer(
            urls = listOf(
                "turn:b.relay.metered.ca:80",
                "turn:b.relay.metered.ca:80?transport=tcp"
            ),
            username = "e8dd65c92f8d9b7a47e4d810",
            credential = "uWdEiPiN/kKOb2Jq"
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

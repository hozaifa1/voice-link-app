package com.streamsync.app.webrtc

object WebRtcConfig {
    
    // Default STUN servers (always free, used alongside TURN)
    val STUN_SERVERS = listOf(
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
            urls = listOf("stun:stun.cloudflare.com:3478"),
            username = null,
            credential = null
        )
    )
    
    // Legacy static ICE servers (fallback if Cloudflare API fails)
    val FALLBACK_ICE_SERVERS = listOf(
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
            urls = listOf("stun:stun.cloudflare.com:3478"),
            username = null,
            credential = null
        ),
        // OpenRelay as backup TURN (limited but works)
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
    
    // Dynamic ICE servers will be fetched from Cloudflare at runtime
    // Use CloudflareTurnService.getCredentials() to get fresh credentials
    @Deprecated("Use CloudflareTurnService.getCredentials() instead for dynamic credentials")
    val ICE_SERVERS = FALLBACK_ICE_SERVERS

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

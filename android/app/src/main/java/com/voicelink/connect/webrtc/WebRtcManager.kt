package com.voicelink.connect.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.voicelink.connect.audio.SystemAudioMixer
import com.voicelink.connect.audio.VoiceNoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.*
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * Manages WebRTC PeerConnection for P2P audio and video streaming.
 * Handles ICE candidates, offers/answers, and audio/video track management.
 * Supports screen sharing via MediaProjection.
 */
class WebRtcManager(
    private val context: Context,
    private val signaling: FirebaseSignaling
) {
    companion object {
        private const val TAG = "WebRtcManager"
        
        // Bitrate constants for video quality
        // SD quality: 720p @ 1.5 Mbps
        private const val SD_MAX_BITRATE_BPS = 1_500_000
        private const val SD_MIN_BITRATE_BPS = 500_000
        
        // HD quality: 1080p @ 4 Mbps
        private const val HD_MAX_BITRATE_BPS = 4_000_000
        private const val HD_MIN_BITRATE_BPS = 1_500_000
    }

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data object Disconnected : ConnectionState()
        data class Failed(val error: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _remoteAudioActive = MutableStateFlow(false)
    val remoteAudioActive: StateFlow<Boolean> = _remoteAudioActive.asStateFlow()

    private val _systemAudioActive = MutableStateFlow(false)
    val systemAudioActive: StateFlow<Boolean> = _systemAudioActive.asStateFlow()

    private val _screenShareActive = MutableStateFlow(false)
    val screenShareActive: StateFlow<Boolean> = _screenShareActive.asStateFlow()
    
    // Remote screen share status - true if remote peer is sharing
    private val _remoteScreenShareActive = MutableStateFlow(false)
    val remoteScreenShareActive: StateFlow<Boolean> = _remoteScreenShareActive.asStateFlow()
    
    // Remote sharer ID - to know who is currently sharing
    private val _remoteSharerId = MutableStateFlow<String?>(null)
    val remoteSharerId: StateFlow<String?> = _remoteSharerId.asStateFlow()
    
    private val _isHdMode = MutableStateFlow(false)
    val isHdMode: StateFlow<Boolean> = _isHdMode.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()
    
    // Remote video aspect ratio (width/height) - used to adapt video view size
    private val _remoteVideoAspectRatio = MutableStateFlow(16f / 9f)
    val remoteVideoAspectRatio: StateFlow<Float> = _remoteVideoAspectRatio.asStateFlow()
    
    // Remote display rotation from sender (0, 90, 180, 270)
    private val _remoteDisplayRotation = MutableStateFlow(0)
    val remoteDisplayRotation: StateFlow<Int> = _remoteDisplayRotation.asStateFlow()
    
    // VideoSink to track remote video dimensions
    private var dimensionTrackingSink: VideoSink? = null

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    
    // System audio mixer for capturing and mixing system audio into WebRTC
    private var systemAudioMixer: SystemAudioMixer? = null
    
    // Voice noise suppressor for microphone audio
    private val voiceNoiseSuppressor = VoiceNoiseSuppressor()
    
    // Audio device module with callback for mixing
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    
    // Screen capture for video sharing
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isInitiator = false
    private var currentRoomId: String? = null
    
    // ICE restart and connection monitoring
    private var iceRestartCount = 0
    private var connectionMonitorJob: Job? = null
    private var rotationMonitorJob: Job? = null
    private var lastIceRestartTime = 0L
    private val maxIceRestarts = 3
    private val iceRestartCooldownMs = 5000L
    private val connectionCheckIntervalMs = 10000L
    private var lastSentRotation = -1

    private val eglBase: EglBase by lazy { EglBase.create() }
    
    // Expose EglBase for video rendering
    fun getEglBaseContext(): EglBase = eglBase

    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")
        
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        // Create audio device module with callback for system audio mixing
        // The AudioRecordDataCallback allows us to modify the mic audio buffer
        // before it's sent to WebRTC, enabling us to mix in system audio
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false) // Mono for voice
            .setUseStereoOutput(true) // Stereo output
            .setAudioRecordDataCallback(object : AudioRecordDataCallback {
                override fun onAudioDataRecorded(
                    audioFormat: Int,
                    channelCount: Int,
                    sampleRate: Int,
                    audioBuffer: ByteBuffer
                ) {
                    val bytesInBuffer = audioBuffer.remaining()
                    
                    // Check if system audio is active
                    val systemAudioActive = systemAudioMixer?.isActive?.value == true
                    
                    if (systemAudioActive) {
                        // Process system audio - uses priority-based switching:
                        // - When system audio is playing: transmit system audio only
                        // - When system audio is silent: transmit mic audio only
                        systemAudioMixer?.processAudioBuffer(audioBuffer, bytesInBuffer, channelCount, sampleRate)
                    } else {
                        // Apply voice noise suppression to microphone audio
                        // This provides WhatsApp-like noise cancellation for voice
                        voiceNoiseSuppressor.processAudio(audioBuffer, bytesInBuffer, channelCount, sampleRate)
                    }
                }
            })
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized successfully")
    }

    /**
     * Enable system audio capture with MediaProjection.
     * Call this after receiving MediaProjection permission result.
     * 
     * System audio will be mixed into the microphone audio stream in real-time
     * via the AudioRecordDataCallback in the JavaAudioDeviceModule.
     */
    fun enableSystemAudio(resultCode: Int, data: Intent): Boolean {
        Log.d(TAG, "Enabling system audio capture")
        
        try {
            // Create and initialize SystemAudioMixer
            if (systemAudioMixer == null) {
                systemAudioMixer = SystemAudioMixer(context)
            }
            
            val success = systemAudioMixer?.initialize(resultCode, data) == true
            if (success) {
                _systemAudioActive.value = true
                Log.i(TAG, "System audio capture enabled successfully")
                return true
            } else {
                Log.e(TAG, "Failed to initialize SystemAudioMixer")
                _systemAudioActive.value = false
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling system audio", e)
            _systemAudioActive.value = false
            return false
        }
    }
    
    /**
     * Enable system audio capture with a shared MediaProjection.
     * Use this when sharing a MediaProjection with screen capture.
     */
    fun enableSystemAudioWithProjection(projection: android.media.projection.MediaProjection): Boolean {
        Log.d(TAG, "Enabling system audio with shared projection")
        
        try {
            if (systemAudioMixer == null) {
                systemAudioMixer = SystemAudioMixer(context)
            }
            
            val success = systemAudioMixer?.initializeWithProjection(projection) == true
            if (success) {
                _systemAudioActive.value = true
                Log.i(TAG, "System audio capture enabled with shared projection")
                return true
            } else {
                Log.e(TAG, "Failed to initialize SystemAudioMixer with projection")
                _systemAudioActive.value = false
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling system audio with projection", e)
            _systemAudioActive.value = false
            return false
        }
    }

    /**
     * Disable system audio capture.
     */
    fun disableSystemAudio() {
        Log.d(TAG, "Disabling system audio")
        systemAudioMixer?.release()
        systemAudioMixer = null
        _systemAudioActive.value = false
    }

    /**
     * Store MediaProjection permission for screen sharing.
     * Call this immediately after receiving the permission result.
     */
    fun storeScreenSharePermission(resultCode: Int, data: Intent) {
        if (screenCaptureManager == null) {
            screenCaptureManager = ScreenCaptureManager(context, eglBase)
        }
        screenCaptureManager?.storePermissionResult(resultCode, data)
    }
    
    /**
     * Set HD mode for screen sharing.
     * This updates both the capture resolution and the encoding bitrate.
     * HD mode can be triggered by either party (sharer or viewer).
     */
    fun setHdMode(enabled: Boolean) {
        _isHdMode.value = enabled
        screenCaptureManager?.setHdMode(enabled)
        
        // Update bitrate on the video sender if screen share is active
        if (_screenShareActive.value) {
            updateVideoBitrate(enabled)
            // Restart capture with new resolution
            restartCaptureWithNewSettings()
        }
        
        // Signal HD mode to remote peer via Firebase
        currentRoomId?.let { roomId ->
            scope.launch {
                signaling.sendHdModeRequest(roomId, enabled, signaling.getCurrentUserId())
            }
        }
        
        Log.d(TAG, "HD mode set to: $enabled")
    }
    
    /**
     * Handle HD mode request from remote peer.
     * If we're the sharer, we should update our capture settings.
     */
    private fun handleRemoteHdModeRequest(enabled: Boolean, requestedBy: String) {
        val myId = signaling.getCurrentUserId()
        
        // Only react if we're the sharer and the request is from the viewer
        if (_screenShareActive.value && requestedBy != myId) {
            Log.d(TAG, "Remote HD mode request: enabled=$enabled")
            _isHdMode.value = enabled
            screenCaptureManager?.setHdMode(enabled)
            updateVideoBitrate(enabled)
            restartCaptureWithNewSettings()
        } else if (!_screenShareActive.value && requestedBy != myId) {
            // We're the viewer, just update our local state
            _isHdMode.value = enabled
        }
    }
    
    /**
     * Restart capture with current settings (used when HD mode changes).
     */
    private fun restartCaptureWithNewSettings() {
        val captureManager = screenCaptureManager ?: return
        if (!captureManager.isCapturing.value) return
        
        try {
            captureManager.stopCapture()
            captureManager.startCapture()
            Log.d(TAG, "Capture restarted with new settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting capture", e)
        }
    }
    
    /**
     * Update the video encoding bitrate based on HD mode.
     * This is crucial for actual HD quality - resolution alone is not enough.
     */
    private fun updateVideoBitrate(isHd: Boolean) {
        val pc = peerConnection ?: return
        
        // Find the video sender
        val videoSender = pc.senders.find { sender ->
            sender.track()?.kind() == "video"
        } ?: run {
            Log.w(TAG, "No video sender found to update bitrate")
            return
        }
        
        try {
            val parameters = videoSender.parameters
            if (parameters.encodings.isEmpty()) {
                Log.w(TAG, "No encodings found in RTP parameters")
                return
            }
            
            // Update bitrate for all encodings
            val maxBitrate = if (isHd) HD_MAX_BITRATE_BPS else SD_MAX_BITRATE_BPS
            val minBitrate = if (isHd) HD_MIN_BITRATE_BPS else SD_MIN_BITRATE_BPS
            
            for (encoding in parameters.encodings) {
                encoding.maxBitrateBps = maxBitrate
                encoding.minBitrateBps = minBitrate
                // For screen sharing, prioritize resolution over frame rate
                encoding.scaleResolutionDownBy = if (isHd) 1.0 else 1.5
            }
            
            val success = videoSender.setParameters(parameters)
            Log.d(TAG, "Video bitrate updated: maxBitrate=${maxBitrate/1_000_000.0}Mbps, success=$success")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating video bitrate", e)
        }
    }

    /**
     * Start monitoring device rotation and send updates to remote peer.
     * This ensures the receiver displays the video in the correct orientation.
     */
    private fun startRotationMonitoring() {
        rotationMonitorJob?.cancel()
        rotationMonitorJob = scope.launch {
            while (isActive) {
                val rotation = getDisplayRotation()
                
                // Only send if rotation changed and we're sharing
                if (rotation != lastSentRotation && _screenShareActive.value) {
                    lastSentRotation = rotation
                    currentRoomId?.let { roomId ->
                        signaling.sendDisplayRotation(roomId, rotation, signaling.getCurrentUserId())
                        Log.d(TAG, "Display rotation sent: $rotation degrees")
                    }
                }
                
                delay(500) // Check every 500ms
            }
        }
    }
    
    /**
     * Stop monitoring device rotation.
     */
    private fun stopRotationMonitoring() {
        rotationMonitorJob?.cancel()
        rotationMonitorJob = null
        lastSentRotation = -1
    }
    
    /**
     * Get current display rotation in degrees (0, 90, 180, 270).
     */
    private fun getDisplayRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        return when (windowManager?.defaultDisplay?.rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
    
    /**
     * Enable screen sharing with system audio.
     * This method handles the MediaProjection properly to share it between
     * screen capture and audio capture.
     * 
     * @param resultCode The result code from MediaProjection permission
     * @param data The intent data from MediaProjection permission
     * @return true if screen sharing started successfully
     */
    fun enableScreenShareWithAudio(resultCode: Int, data: Intent): Boolean {
        Log.d(TAG, "Enabling screen share with audio")
        
        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return false
        }
        
        val pc = peerConnection ?: run {
            Log.e(TAG, "PeerConnection not initialized")
            return false
        }
        
        try {
            // Create screen capture manager if needed
            if (screenCaptureManager == null) {
                screenCaptureManager = ScreenCaptureManager(context, eglBase)
            }
            
            val captureManager = screenCaptureManager!!
            
            // Store the permission for screen capture
            captureManager.storePermissionResult(resultCode, data)
            
            // Apply HD mode setting
            captureManager.setHdMode(_isHdMode.value)
            
            // Create video source
            localVideoSource = factory.createVideoSource(true) // isScreencast = true
            
            // Initialize the screen capturer with the video source
            if (!captureManager.initialize(localVideoSource!!)) {
                Log.e(TAG, "Failed to initialize screen capturer")
                localVideoSource?.dispose()
                localVideoSource = null
                return false
            }
            
            // Create video track
            localVideoTrack = factory.createVideoTrack("video0", localVideoSource)
            
            // Add video track to peer connection
            localVideoTrack?.let { track ->
                pc.addTrack(track, listOf("stream0"))
                Log.d(TAG, "Local video track added")
            }
            
            // Start capturing
            if (!captureManager.startCapture()) {
                Log.e(TAG, "Failed to start screen capture")
                disableScreenShare()
                return false
            }
            
            _screenShareActive.value = true
            
            // Apply bitrate settings based on current HD mode
            updateVideoBitrate(_isHdMode.value)
            
            // Enable system audio capture using the same MediaProjection
            // This ensures share video + share audio work together
            enableSystemAudio(resultCode, data)
            
            // Start monitoring and sending display rotation
            startRotationMonitoring()
            
            // Signal screen share status to remote peer
            currentRoomId?.let { roomId ->
                scope.launch {
                    signaling.sendScreenShareStatus(roomId, true, signaling.getCurrentUserId())
                }
            }
            
            // Trigger renegotiation to add video to the session
            triggerRenegotiation()
            
            Log.i(TAG, "Screen share with audio enabled successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling screen share with audio", e)
            disableScreenShare()
            return false
        }
    }
    
    /**
     * Enable screen sharing.
     * Must call storeScreenSharePermission() first.
     */
    fun enableScreenShare(): Boolean {
        Log.d(TAG, "Enabling screen share")
        
        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return false
        }
        
        val pc = peerConnection ?: run {
            Log.e(TAG, "PeerConnection not initialized")
            return false
        }
        
        try {
            // Create screen capture manager if needed
            if (screenCaptureManager == null) {
                screenCaptureManager = ScreenCaptureManager(context, eglBase)
            }
            
            val captureManager = screenCaptureManager!!
            
            if (!captureManager.hasPermission()) {
                Log.e(TAG, "No screen share permission stored")
                return false
            }
            
            // Apply HD mode setting
            captureManager.setHdMode(_isHdMode.value)
            
            // Create video source
            localVideoSource = factory.createVideoSource(true) // isScreencast = true
            
            // Initialize the screen capturer with the video source
            if (!captureManager.initialize(localVideoSource!!)) {
                Log.e(TAG, "Failed to initialize screen capturer")
                localVideoSource?.dispose()
                localVideoSource = null
                return false
            }
            
            // Create video track
            localVideoTrack = factory.createVideoTrack("video0", localVideoSource)
            
            // Add video track to peer connection
            localVideoTrack?.let { track ->
                pc.addTrack(track, listOf("stream0"))
                Log.d(TAG, "Local video track added")
            }
            
            // Start capturing
            if (!captureManager.startCapture()) {
                Log.e(TAG, "Failed to start screen capture")
                disableScreenShare()
                return false
            }
            
            _screenShareActive.value = true
            
            // Apply bitrate settings based on current HD mode
            updateVideoBitrate(_isHdMode.value)
            
            // Start monitoring and sending display rotation
            startRotationMonitoring()
            
            // Trigger renegotiation to add video to the session
            triggerRenegotiation()
            
            Log.i(TAG, "Screen share enabled successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling screen share", e)
            disableScreenShare()
            return false
        }
    }

    /**
     * Disable screen sharing.
     */
    fun disableScreenShare() {
        Log.d(TAG, "Disabling screen share")
        
        // Stop rotation monitoring
        stopRotationMonitoring()
        
        screenCaptureManager?.release()
        screenCaptureManager = null
        
        localVideoTrack?.let { track ->
            track.setEnabled(false)
            // Remove from peer connection
            peerConnection?.senders?.find { it.track()?.id() == track.id() }?.let { sender ->
                peerConnection?.removeTrack(sender)
            }
            track.dispose()
        }
        localVideoTrack = null
        
        localVideoSource?.dispose()
        localVideoSource = null
        
        _screenShareActive.value = false
        
        // Signal screen share stopped to remote peer
        currentRoomId?.let { roomId ->
            scope.launch {
                signaling.sendScreenShareStatus(roomId, false, signaling.getCurrentUserId())
            }
        }
        
        // Trigger renegotiation to remove video from the session
        if (peerConnection != null && currentRoomId != null) {
            triggerRenegotiation()
        }
    }

    /**
     * Trigger SDP renegotiation when tracks are added/removed.
     * Both initiator and non-initiator can trigger renegotiation.
     */
    private fun triggerRenegotiation() {
        val roomId = currentRoomId ?: return
        val pc = peerConnection ?: return
        
        Log.d(TAG, "Triggering renegotiation (isInitiator: $isInitiator)")
        
        // Create new offer with updated tracks
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Renegotiation offer created")
                    pc.setLocalDescription(SimpleSdpObserver("setLocalDescription (renegotiation)"), it)
                    scope.launch {
                        signaling.sendOffer(roomId, it, isRenegotiation = true, isInitiator = isInitiator)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Renegotiation offer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createRoom(onRoomCreated: (String) -> Unit) {
        isInitiator = true
        _connectionState.value = ConnectionState.Connecting
        
        scope.launch {
            try {
                val roomId = signaling.createRoom()
                currentRoomId = roomId
                Log.d(TAG, "Room created: $roomId")
                
                createPeerConnection(roomId)
                createOffer(roomId)
                
                onRoomCreated(roomId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create room", e)
                _connectionState.value = ConnectionState.Failed(e.message ?: "Failed to create room")
            }
        }
    }

    fun joinRoom(roomId: String, onJoined: (Boolean) -> Unit) {
        isInitiator = false
        currentRoomId = roomId
        _connectionState.value = ConnectionState.Connecting
        
        scope.launch {
            try {
                val roomExists = signaling.roomExists(roomId)
                if (!roomExists) {
                    Log.e(TAG, "Room does not exist: $roomId")
                    _connectionState.value = ConnectionState.Failed("Room not found")
                    onJoined(false)
                    return@launch
                }
                
                createPeerConnection(roomId)
                
                // Get the offer from the room creator
                signaling.getOffer(roomId) { offer ->
                    if (offer != null) {
                        scope.launch {
                            handleRemoteOffer(roomId, offer)
                            onJoined(true)
                        }
                    } else {
                        Log.e(TAG, "No offer found in room")
                        _connectionState.value = ConnectionState.Failed("No offer found")
                        onJoined(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join room", e)
                _connectionState.value = ConnectionState.Failed(e.message ?: "Failed to join room")
                onJoined(false)
            }
        }
    }

    private suspend fun fetchIceServers(): List<IceServer> {
        // Try to get Cloudflare TURN credentials first
        val cloudflareCredentials = CloudflareTurnService.getCredentials()
        
        return if (cloudflareCredentials != null) {
            Log.i(TAG, "Using Cloudflare TURN servers (1TB free/month)")
            // Combine STUN servers with Cloudflare TURN credentials
            WebRtcConfig.STUN_SERVERS + cloudflareCredentials.iceServers
        } else {
            Log.w(TAG, "Cloudflare unavailable, using fallback servers")
            WebRtcConfig.FALLBACK_ICE_SERVERS
        }
    }
    
    private suspend fun createPeerConnection(roomId: String) {
        // Fetch fresh ICE servers (Cloudflare TURN + STUN)
        val iceServerConfigs = fetchIceServers()
        Log.d(TAG, "Using ${iceServerConfigs.size} ICE servers")
        
        val iceServers = iceServerConfigs.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .apply {
                    server.username?.let { setUsername(it) }
                    server.credential?.let { setPassword(it) }
                }
                .createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Enable ICE candidate trickling for faster connection
            iceCandidatePoolSize = 10
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        _connectionState.value = ConnectionState.Connected
                        iceRestartCount = 0 // Reset restart count on successful connection
                        startConnectionMonitoring()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "ICE disconnected, attempting restart...")
                        attemptIceRestart()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.e(TAG, "ICE connection failed, attempting restart...")
                        attemptIceRestart()
                    }
                    PeerConnection.IceConnectionState.CHECKING -> {
                        _connectionState.value = ConnectionState.Connecting
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Local ICE candidate: ${it.sdp}")
                    scope.launch {
                        signaling.sendIceCandidate(roomId, it, isInitiator)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Remote stream added")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "Remote stream removed")
            }

            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "Data channel: ${channel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Remote track added: ${receiver?.track()?.kind()}")
                when (receiver?.track()?.kind()) {
                    "audio" -> {
                        _remoteAudioActive.value = true
                    }
                    "video" -> {
                        val videoTrack = receiver.track() as? VideoTrack
                        videoTrack?.setEnabled(true)
                        
                        // Add a sink to track video dimensions for aspect ratio
                        dimensionTrackingSink?.let { oldSink ->
                            _remoteVideoTrack.value?.removeSink(oldSink)
                        }
                        dimensionTrackingSink = VideoSink { frame ->
                            val width = frame.rotatedWidth
                            val height = frame.rotatedHeight
                            if (width > 0 && height > 0) {
                                val newRatio = width.toFloat() / height.toFloat()
                                if (_remoteVideoAspectRatio.value != newRatio) {
                                    _remoteVideoAspectRatio.value = newRatio
                                    Log.d(TAG, "Remote video dimensions: ${width}x${height}, aspect ratio: $newRatio")
                                }
                            }
                        }
                        videoTrack?.addSink(dimensionTrackingSink)
                        
                        _remoteVideoTrack.value = videoTrack
                        Log.d(TAG, "Remote video track received and enabled")
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d(TAG, "Track received: ${transceiver?.receiver?.track()?.kind()}")
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        
        // Add local audio track
        addLocalAudioTrack()
        
        // Listen for remote ICE candidates
        signaling.listenForIceCandidates(roomId, !isInitiator) { candidate ->
            Log.d(TAG, "Remote ICE candidate received")
            peerConnection?.addIceCandidate(candidate)
        }
        
        // Listen for answers (including renegotiation answers)
        signaling.listenForAnswer(roomId) { answer ->
            Log.d(TAG, "Answer received")
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver("setRemoteDescription (answer)"),
                answer
            )
        }
        
        // Both parties listen for renegotiation offers to support two-way screen sharing
        signaling.listenForRenegotiationOffers(roomId, isInitiator) { offer ->
            Log.d(TAG, "Renegotiation offer received")
            handleRenegotiationOffer(roomId, offer)
        }
        
        // Listen for HD mode changes from either party
        signaling.listenForHdModeChanges(roomId) { enabled, requestedBy ->
            handleRemoteHdModeRequest(enabled, requestedBy)
        }
        
        // Listen for screen share status changes (for auto-stop feature)
        signaling.listenForScreenShareStatus(roomId) { isSharing, sharerId ->
            handleRemoteScreenShareStatus(isSharing, sharerId)
        }
        
        // Listen for display rotation changes from remote peer
        signaling.listenForDisplayRotation(roomId) { rotation, senderId ->
            handleRemoteDisplayRotation(rotation, senderId)
        }
    }
    
    /**
     * Handle display rotation update from remote peer.
     * Updates the rotation state so receiver can apply proper transformation.
     */
    private fun handleRemoteDisplayRotation(rotation: Int, senderId: String) {
        val myId = signaling.getCurrentUserId()
        
        // Only apply if it's from the remote peer (not our own signal)
        if (senderId != myId) {
            _remoteDisplayRotation.value = rotation
            Log.d(TAG, "Remote display rotation updated: $rotation degrees")
        }
    }
    
    /**
     * Handle screen share status update from remote peer.
     * Used to auto-stop local sharing when remote peer starts sharing.
     */
    private fun handleRemoteScreenShareStatus(isSharing: Boolean, sharerId: String) {
        val myId = signaling.getCurrentUserId()
        
        _remoteScreenShareActive.value = isSharing
        _remoteSharerId.value = if (isSharing) sharerId else null
        
        // If remote started sharing and we're also sharing, stop our sharing
        // Use disableScreenShareSilent to avoid triggering renegotiation (remote will handle it)
        if (isSharing && sharerId != myId && _screenShareActive.value) {
            Log.d(TAG, "Remote peer started sharing, stopping local share silently")
            disableScreenShareSilent()
        }
    }
    
    /**
     * Disable screen sharing without triggering renegotiation.
     * Used when remote peer starts sharing and we need to auto-stop.
     */
    private fun disableScreenShareSilent() {
        Log.d(TAG, "Disabling screen share silently (no renegotiation)")
        
        // Stop rotation monitoring
        stopRotationMonitoring()
        
        screenCaptureManager?.release()
        screenCaptureManager = null
        
        localVideoTrack?.let { track ->
            track.setEnabled(false)
            peerConnection?.senders?.find { it.track()?.id() == track.id() }?.let { sender ->
                peerConnection?.removeTrack(sender)
            }
            track.dispose()
        }
        localVideoTrack = null
        
        localVideoSource?.dispose()
        localVideoSource = null
        
        _screenShareActive.value = false
        
        // Also stop system audio when auto-stopping
        disableSystemAudio()
        
        // Signal screen share stopped to remote peer
        currentRoomId?.let { roomId ->
            scope.launch {
                signaling.sendScreenShareStatus(roomId, false, signaling.getCurrentUserId())
            }
        }
        
        // DO NOT trigger renegotiation here - remote peer's renegotiation will handle it
    }
    
    /**
     * Check if remote peer is currently sharing.
     * Used to show warning before starting to share.
     */
    fun isRemotePeerSharing(): Boolean {
        return _remoteScreenShareActive.value
    }
    
    /**
     * Get the ID of the current sharer (if any).
     */
    fun getCurrentSharerId(): String? {
        return _remoteSharerId.value
    }
    
    /**
     * Handle a renegotiation offer from the remote peer.
     */
    private fun handleRenegotiationOffer(roomId: String, offer: SessionDescription) {
        val pc = peerConnection ?: return
        
        pc.setRemoteDescription(
            SimpleSdpObserver("setRemoteDescription (renegotiation offer)"),
            offer
        )
        
        // Create answer for the renegotiation offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Renegotiation answer created")
                    pc.setLocalDescription(SimpleSdpObserver("setLocalDescription (renegotiation answer)"), it)
                    scope.launch {
                        signaling.sendAnswer(roomId, it)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Renegotiation answer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun addLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("stream0"))
            Log.d(TAG, "Local audio track added")
        }
    }

    private fun createOffer(roomId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Offer created")
                    peerConnection?.setLocalDescription(SimpleSdpObserver("setLocalDescription (offer)"), it)
                    scope.launch {
                        signaling.sendOffer(roomId, it)
                    }
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
                _connectionState.value = ConnectionState.Failed("Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun handleRemoteOffer(roomId: String, offer: SessionDescription) {
        peerConnection?.setRemoteDescription(
            SimpleSdpObserver("setRemoteDescription (offer)"),
            offer
        )
        createAnswer(roomId)
    }

    private fun createAnswer(roomId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    Log.d(TAG, "Answer created")
                    peerConnection?.setLocalDescription(SimpleSdpObserver("setLocalDescription (answer)"), it)
                    scope.launch {
                        signaling.sendAnswer(roomId, it)
                    }
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
                _connectionState.value = ConnectionState.Failed("Failed to create answer: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun attemptIceRestart() {
        val now = System.currentTimeMillis()
        
        // Check cooldown period
        if (now - lastIceRestartTime < iceRestartCooldownMs) {
            Log.d(TAG, "ICE restart on cooldown, skipping")
            return
        }
        
        // Check restart count
        if (iceRestartCount >= maxIceRestarts) {
            Log.e(TAG, "Max ICE restarts ($maxIceRestarts) exceeded, connection failed")
            _connectionState.value = ConnectionState.Failed("Connection lost - please rejoin")
            return
        }
        
        iceRestartCount++
        lastIceRestartTime = now
        Log.d(TAG, "Attempting ICE restart #$iceRestartCount")
        
        scope.launch {
            try {
                performIceRestart()
            } catch (e: Exception) {
                Log.e(TAG, "ICE restart failed", e)
                if (iceRestartCount >= maxIceRestarts) {
                    _connectionState.value = ConnectionState.Failed("Connection lost - please rejoin")
                }
            }
        }
    }
    
    private fun performIceRestart() {
        val pc = peerConnection ?: run {
            Log.e(TAG, "No peer connection for ICE restart")
            return
        }
        
        val roomId = currentRoomId ?: run {
            Log.e(TAG, "No room ID for ICE restart")
            return
        }
        
        // Create new offer with ICE restart flag
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        
        if (isInitiator) {
            // Initiator creates new offer with ICE restart
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        Log.d(TAG, "ICE restart offer created")
                        pc.setLocalDescription(SimpleSdpObserver("setLocalDescription (ICE restart)"), it)
                        scope.launch {
                            signaling.sendOffer(roomId, it)
                        }
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Failed to create ICE restart offer: $error")
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } else {
            // Non-initiator just restarts ICE gathering
            pc.restartIce()
        }
    }
    
    private fun startConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            while (isActive) {
                delay(connectionCheckIntervalMs)
                checkConnectionHealth()
            }
        }
    }
    
    private fun stopConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
    }
    
    private fun checkConnectionHealth() {
        val pc = peerConnection ?: return
        
        when (pc.iceConnectionState()) {
            PeerConnection.IceConnectionState.DISCONNECTED,
            PeerConnection.IceConnectionState.FAILED -> {
                Log.w(TAG, "Connection health check: connection degraded")
                attemptIceRestart()
            }
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                // Connection is healthy
                Log.v(TAG, "Connection health check: OK")
            }
            else -> {
                Log.d(TAG, "Connection health check: state=${pc.iceConnectionState()}")
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        
        stopConnectionMonitoring()
        stopRotationMonitoring()
        disableScreenShare()
        disableSystemAudio()
        
        currentRoomId?.let { roomId ->
            scope.launch {
                signaling.leaveRoom(roomId)
            }
        }
        
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        
        audioSource?.dispose()
        audioSource = null
        
        peerConnection?.close()
        peerConnection = null
        
        currentRoomId = null
        iceRestartCount = 0
        _connectionState.value = ConnectionState.Idle
        _remoteAudioActive.value = false
        _systemAudioActive.value = false
        _screenShareActive.value = false
        
        // Clean up dimension tracking sink
        dimensionTrackingSink?.let { sink ->
            _remoteVideoTrack.value?.removeSink(sink)
        }
        dimensionTrackingSink = null
        _remoteVideoTrack.value = null
        _remoteVideoAspectRatio.value = 16f / 9f // Reset to default
        _remoteDisplayRotation.value = 0 // Reset rotation
    }

    fun release() {
        stopConnectionMonitoring()
        stopRotationMonitoring()
        disconnect()
        
        audioDeviceModule?.release()
        audioDeviceModule = null
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        
        try {
            eglBase.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing EGL base", e)
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Audio enabled: $enabled")
    }

    private class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            Log.d(TAG, "$tag: onCreateSuccess")
        }

        override fun onSetSuccess() {
            Log.d(TAG, "$tag: onSetSuccess")
        }

        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$tag: onCreateFailure - $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$tag: onSetFailure - $error")
        }

        companion object {
            private const val TAG = "SdpObserver"
        }
    }
}

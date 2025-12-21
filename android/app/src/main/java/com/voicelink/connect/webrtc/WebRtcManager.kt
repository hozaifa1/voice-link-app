package com.voicelink.connect.webrtc

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
        // SD quality: 1080p @ 5 Mbps (good quality for screen sharing)
        private const val SD_MAX_BITRATE_BPS = 5_000_000
        private const val SD_MIN_BITRATE_BPS = 2_000_000
        
        // HD quality: 1080p @ 10 Mbps (high quality for screen sharing)
        private const val HD_MAX_BITRATE_BPS = 10_000_000
        private const val HD_MIN_BITRATE_BPS = 4_000_000
        
        // Framerate limits for screen sharing
        private const val SD_MAX_FRAMERATE = 24
        private const val HD_MAX_FRAMERATE = 30
    }

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data object Disconnected : ConnectionState()
        data class Failed(val error: String) : ConnectionState()
    }
    
    sealed class ParticipantEvent {
        data class Joined(val userId: String, val timestamp: Long = System.currentTimeMillis()) : ParticipantEvent()
        data class Left(val userId: String, val timestamp: Long = System.currentTimeMillis()) : ParticipantEvent()
        data class StartedSharing(val userId: String, val timestamp: Long = System.currentTimeMillis()) : ParticipantEvent()
        data class StoppedSharing(val userId: String, val timestamp: Long = System.currentTimeMillis()) : ParticipantEvent()
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
    
    // Participant tracking
    private val _participants = MutableStateFlow<List<FirebaseSignaling.Participant>>(emptyList())
    val participants: StateFlow<List<FirebaseSignaling.Participant>> = _participants.asStateFlow()
    
    // Participant events (for notifications)
    private val _participantEvent = MutableStateFlow<ParticipantEvent?>(null)
    val participantEvent: StateFlow<ParticipantEvent?> = _participantEvent.asStateFlow()
    
    private val _isHdMode = MutableStateFlow(false)
    val isHdMode: StateFlow<Boolean> = _isHdMode.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()
    
    private var remoteAudioTrack: AudioTrack? = null
    
    // Silent App mode - mutes all incoming audio
    private val _isSilentMode = MutableStateFlow(false)
    val isSilentMode: StateFlow<Boolean> = _isSilentMode.asStateFlow()
    
    // Mute Me mode - mutes microphone output
    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()
    
    // Loudspeaker mode - routes audio to loudspeaker instead of earpiece
    private val _isLoudspeakerOn = MutableStateFlow(false)
    val isLoudspeakerOn: StateFlow<Boolean> = _isLoudspeakerOn.asStateFlow()
    
    // Remote video aspect ratio (width/height) - used to adapt video view size
    private val _remoteVideoAspectRatio = MutableStateFlow(16f / 9f)
    val remoteVideoAspectRatio: StateFlow<Float> = _remoteVideoAspectRatio.asStateFlow()
    
    // VideoSink to track remote video dimensions
    private var dimensionTrackingSink: VideoSink? = null
    
    // AudioManager for controlling loudspeaker
    private var audioManager: AudioManager? = null

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
    
    // Job for monitoring system audio playing state
    private var systemAudioMonitorJob: Job? = null
    
    // ICE restart and connection monitoring
    private var iceRestartCount = 0
    private var connectionMonitorJob: Job? = null
    private var lastIceRestartTime = 0L
    private val maxIceRestarts = 3
    private val iceRestartCooldownMs = 5000L
    private val connectionCheckIntervalMs = 10000L

    private val eglBase: EglBase by lazy { EglBase.create() }
    
    // Expose EglBase for video rendering
    fun getEglBaseContext(): EglBase = eglBase

    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")
        
        // Initialize AudioManager for loudspeaker control
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
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
            .setUseHardwareNoiseSuppressor(false) // Disabled: using custom software noise suppressor
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
                // Start monitoring system audio playing state for seamless switching
                startSystemAudioMonitoring()
                Log.i(TAG, "System audio capture enabled successfully, monitoring audio playing state")
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
                // Start monitoring system audio playing state for seamless switching
                startSystemAudioMonitoring()
                Log.i(TAG, "System audio capture enabled with shared projection, monitoring audio playing state")
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
        // Stop monitoring
        systemAudioMonitorJob?.cancel()
        systemAudioMonitorJob = null
        
        systemAudioMixer?.release()
        systemAudioMixer = null
        _systemAudioActive.value = false
        // Unmute remote audio when system audio stops
        muteRemoteAudio(false)
    }
    
    /**
     * Monitor system audio playing state for seamless audio switching.
     * When system audio is playing, mute remote audio.
     * When system audio is paused/silent, unmute remote audio so sender hears receiver.
     */
    private fun startSystemAudioMonitoring() {
        systemAudioMonitorJob?.cancel()
        systemAudioMonitorJob = scope.launch {
            systemAudioMixer?.isSystemAudioPlaying?.collect { isPlaying ->
                // Dynamically mute/unmute remote audio based on whether system audio is actually playing
                muteRemoteAudio(isPlaying)
                Log.d(TAG, "System audio playing state changed: $isPlaying, remote audio ${if (isPlaying) "muted" else "unmuted"}")
            }
        }
    }
    
    /**
     * Mute or unmute the remote audio track.
     * Used to prevent audio leak when sender is playing system audio.
     * Also respects Silent App mode.
     */
    private fun muteRemoteAudio(mute: Boolean) {
        // If Silent App mode is enabled, always keep remote audio muted
        val shouldMute = mute || _isSilentMode.value
        remoteAudioTrack?.setEnabled(!shouldMute)
        Log.d(TAG, "Remote audio ${if (shouldMute) "muted" else "unmuted"}")
    }
    
    /**
     * Toggle Silent App mode - mutes all incoming audio from remote peer.
     */
    fun toggleSilentMode() {
        _isSilentMode.value = !_isSilentMode.value
        // Update remote audio state immediately
        val isSystemPlaying = systemAudioMixer?.isSystemAudioPlaying?.value == true
        muteRemoteAudio(isSystemPlaying)
        Log.d(TAG, "Silent App mode: ${_isSilentMode.value}")
    }
    
    /**
     * Toggle Mute Me mode - mutes microphone so no voice input is transmitted.
     */
    fun toggleMicMute() {
        _isMicMuted.value = !_isMicMuted.value
        localAudioTrack?.setEnabled(!_isMicMuted.value)
        Log.d(TAG, "Mic muted: ${_isMicMuted.value}")
    }
    
    /**
     * Toggle loudspeaker mode - routes audio to loudspeaker or earpiece.
     * When ON: Audio plays through the large phone loudspeaker.
     * When OFF: Audio plays through the default output (earpiece during calls).
     */
    fun toggleLoudspeaker() {
        _isLoudspeakerOn.value = !_isLoudspeakerOn.value
        audioManager?.isSpeakerphoneOn = _isLoudspeakerOn.value
        Log.d(TAG, "Loudspeaker: ${_isLoudspeakerOn.value}")
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
        
        // Only update encoding parameters if screen share is active
        // DO NOT restart capture - it causes black screen
        if (_screenShareActive.value) {
            updateVideoBitrate(enabled)
        }
        
        // Signal HD mode to remote peer via Firebase
        currentRoomId?.let { roomId ->
            scope.launch {
                signaling.sendHdModeRequest(roomId, enabled, signaling.getCurrentUserId())
            }
        }
        
        Log.d(TAG, "HD mode set to: $enabled (bitrate updated dynamically)")
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
            // Only update encoding parameters dynamically - no capture restart
            updateVideoBitrate(enabled)
        } else if (!_screenShareActive.value && requestedBy != myId) {
            // We're the viewer, just update our local state
            _isHdMode.value = enabled
        }
    }
    
    /**
     * Note: Restarting capture when HD mode changes causes black screen.
     * Instead, we update encoding parameters (bitrate, framerate) dynamically
     * without stopping/restarting the capture. Capture always runs at 1080p/30fps,
     * and encoding parameters control the actual quality sent over the network.
     */
    private fun restartCaptureWithNewSettings() {
        // Intentionally empty - do not restart capture to avoid black screen
        Log.d(TAG, "HD mode change handled via dynamic encoding parameters only")
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
            val maxFramerate = if (isHd) HD_MAX_FRAMERATE else SD_MAX_FRAMERATE
            
            for (encoding in parameters.encodings) {
                encoding.maxBitrateBps = maxBitrate
                encoding.minBitrateBps = minBitrate
                encoding.maxFramerate = maxFramerate
                // Don't scale down resolution - maintain full quality
                encoding.scaleResolutionDownBy = 1.0
            }
            
            // Set degradation preference to maintain resolution quality
            // This tells WebRTC to reduce framerate before reducing resolution
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            
            val success = videoSender.setParameters(parameters)
            Log.d(TAG, "Video bitrate updated: maxBitrate=${maxBitrate/1_000_000.0}Mbps, success=$success")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating video bitrate", e)
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
    
    private var previousParticipants = emptyList<FirebaseSignaling.Participant>()
    
    private fun handleParticipantChanges(participants: List<FirebaseSignaling.Participant>) {
        val myUserId = signaling.getCurrentUserId()
        val activeParticipants = participants.filter { it.status == "joined" && it.userId != myUserId }
        val previousActive = previousParticipants.filter { it.status == "joined" && it.userId != myUserId }
        
        // Detect new joins
        val newJoins = activeParticipants.filter { participant ->
            previousActive.none { it.userId == participant.userId }
        }
        
        // Detect leaves
        val newLeaves = previousActive.filter { participant ->
            activeParticipants.none { it.userId == participant.userId }
        }
        
        // Emit events
        newJoins.forEach { participant ->
            _participantEvent.value = ParticipantEvent.Joined(participant.userId)
        }
        
        newLeaves.forEach { participant ->
            _participantEvent.value = ParticipantEvent.Left(participant.userId)
        }
        
        previousParticipants = participants
        _participants.value = activeParticipants
        
        Log.d(TAG, "Participants updated: ${activeParticipants.size} active participants")
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
                
                // Register as participant
                signaling.joinRoomAsParticipant(roomId)
                
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
                        val audioTrack = receiver.track() as? AudioTrack
                        audioTrack?.setEnabled(true)
                        remoteAudioTrack = audioTrack
                        _remoteAudioActive.value = true
                        
                        // Check if system audio is actively playing or Silent App mode is on
                        val isSystemPlaying = systemAudioMixer?.isSystemAudioPlaying?.value == true
                        if (isSystemPlaying || _isSilentMode.value) {
                            muteRemoteAudio(isSystemPlaying)
                        }
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
        
        // Listen for participant changes
        signaling.listenForParticipants(roomId) { participants ->
            handleParticipantChanges(participants)
        }
    }
    
    /**
     * Handle screen share status update from remote peer.
     * Used to auto-stop local sharing when remote peer starts sharing.
     */
    private fun handleRemoteScreenShareStatus(isSharing: Boolean, sharerId: String) {
        val myId = signaling.getCurrentUserId()
        
        // Only process if this is from the remote peer
        if (sharerId != myId) {
            // Emit notification event
            if (isSharing) {
                _participantEvent.value = ParticipantEvent.StartedSharing(sharerId)
            } else {
                _participantEvent.value = ParticipantEvent.StoppedSharing(sharerId)
            }
        }
        
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
        systemAudioMonitorJob?.cancel()
        systemAudioMonitorJob = null
        disableScreenShare()
        disableSystemAudio()
        
        val roomId = currentRoomId
        if (roomId != null) {
            scope.launch {
                try {
                    signaling.leaveRoomAsParticipant(roomId)
                    signaling.leaveRoom(roomId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during room cleanup", e)
                }
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
        isInitiator = false
        _connectionState.value = ConnectionState.Idle
        _remoteAudioActive.value = false
        _systemAudioActive.value = false
        _screenShareActive.value = false
        _isSilentMode.value = false
        _isMicMuted.value = false
        _participants.value = emptyList()
        _participantEvent.value = null
        previousParticipants = emptyList()
        
        // Clean up dimension tracking sink
        dimensionTrackingSink?.let { sink ->
            _remoteVideoTrack.value?.removeSink(sink)
        }
        dimensionTrackingSink = null
        _remoteVideoTrack.value = null
        _remoteVideoAspectRatio.value = 16f / 9f // Reset to default
        remoteAudioTrack = null
    }
    
    fun clearParticipantEvent() {
        _participantEvent.value = null
    }

    fun release() {
        val roomId = currentRoomId
        
        stopConnectionMonitoring()
        disconnect()
        
        // Final cleanup of signaling
        scope.launch {
            try {
                signaling.cleanup(roomId)
            } catch (e: Exception) {
                Log.e(TAG, "Error during signaling cleanup", e)
            }
        }
        
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

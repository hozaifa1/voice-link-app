package com.voicelink.connect.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Manages WebRTC PeerConnection for P2P audio streaming.
 * Handles ICE candidates, offers/answers, and audio track management.
 */
class WebRtcManager(
    private val context: Context,
    private val signaling: FirebaseSignaling
) {
    companion object {
        private const val TAG = "WebRtcManager"
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

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isInitiator = false
    private var currentRoomId: String? = null

    private val eglBase: EglBase by lazy { EglBase.create() }

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

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule())
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized successfully")
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

    private fun createPeerConnection(roomId: String) {
        val iceServers = WebRtcConfig.ICE_SERVERS.map { server ->
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
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        _connectionState.value = ConnectionState.Connected
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        _connectionState.value = ConnectionState.Failed("ICE connection failed")
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
                if (receiver?.track()?.kind() == "audio") {
                    _remoteAudioActive.value = true
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
        
        // If we're the initiator, listen for answer
        if (isInitiator) {
            signaling.listenForAnswer(roomId) { answer ->
                Log.d(TAG, "Answer received")
                peerConnection?.setRemoteDescription(
                    SimpleSdpObserver("setRemoteDescription (answer)"),
                    answer
                )
            }
        }
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
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        
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
        _connectionState.value = ConnectionState.Idle
        _remoteAudioActive.value = false
    }

    fun release() {
        disconnect()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase.release()
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

package com.voicelink.connect.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.voicelink.connect.service.AudioCaptureService
import com.voicelink.connect.service.WebRtcService
import com.voicelink.connect.util.PermissionHelper
import com.voicelink.connect.webrtc.FirebaseSignaling
import com.voicelink.connect.webrtc.WebRtcManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    
    var screenState by remember { mutableStateOf<RoomScreenState>(RoomScreenState.Initial) }
    var roomCode by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(PermissionHelper.hasAllPermissions(context)) }
    
    // Fullscreen state for video
    var isFullscreen by remember { mutableStateOf(false) }
    var videoVolume by remember { mutableStateOf(1f) }
    var isVideoMuted by remember { mutableStateOf(false) }
    
    val signaling = remember { FirebaseSignaling() }
    val webRtcManager = remember { WebRtcManager(context, signaling) }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.all { it.value }
        if (!hasPermissions) {
            errorMessage = "Microphone permission is required for voice chat"
        }
    }
    
    // Request permissions on first launch
    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
        }
    }
    
    val connectionState by webRtcManager.connectionState.collectAsState()
    val remoteAudioActive by webRtcManager.remoteAudioActive.collectAsState()
    val systemAudioActive by webRtcManager.systemAudioActive.collectAsState()
    val screenShareActive by webRtcManager.screenShareActive.collectAsState()
    val remoteVideoTrack by webRtcManager.remoteVideoTrack.collectAsState()
    val isHdMode by webRtcManager.isHdMode.collectAsState()
    val remoteVideoAspectRatio by webRtcManager.remoteVideoAspectRatio.collectAsState()
    val remoteScreenShareActive by webRtcManager.remoteScreenShareActive.collectAsState()
    val participantEvent by webRtcManager.participantEvent.collectAsState()
    val participants by webRtcManager.participants.collectAsState()
    val isSilentMode by webRtcManager.isSilentMode.collectAsState()
    val isMicMuted by webRtcManager.isMicMuted.collectAsState()
    val isLoudspeakerOn by webRtcManager.isLoudspeakerOn.collectAsState()
    
    // State for showing screen share warning dialog
    var showScreenShareWarning by remember { mutableStateOf(false) }
    
    // State for back button confirmation dialog
    var showBackConfirmDialog by remember { mutableStateOf(false) }
    
    // State for app exit confirmation dialog
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    
    // Joining timeout job
    var joiningTimeoutJob by remember { mutableStateOf<Job?>(null) }
    
    // Participant notification state
    var participantNotification by remember { mutableStateOf<ParticipantNotification?>(null) }
    
    // MediaProjection launcher for system audio sharing
    val audioProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start foreground service for media projection
            AudioCaptureService.prepareCapture(context)
            // Enable system audio in WebRTC
            webRtcManager.enableSystemAudio(result.resultCode, result.data!!)
        }
    }
    
    // Coroutine scope for async operations
    val coroutineScope = rememberCoroutineScope()
    
    // MediaProjection launcher for screen sharing (video + audio)
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start foreground service for media projection
            AudioCaptureService.prepareCapture(context)
            
            // Use coroutine to wait for service to be ready before starting screen share
            coroutineScope.launch {
                // Wait for foreground service to be ready (required for MediaProjection on Android 10+)
                var attempts = 0
                while (!AudioCaptureService.isRunning && attempts < 50) {
                    delay(20)
                    attempts++
                }
                
                if (AudioCaptureService.isRunning) {
                    // Use the combined method that handles everything in one go
                    webRtcManager.enableScreenShareWithAudio(result.resultCode, result.data!!)
                } else {
                    Log.e("RoomScreen", "Foreground service not ready for screen share")
                }
            }
        }
    }
    
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            webRtcManager.initialize()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            // Stop WebRTC service when leaving
            WebRtcService.stop(context)
            webRtcManager.release()
            // Cleanup is now handled in release() with proper participant status update
        }
    }
    
    // Update screen state based on connection
    LaunchedEffect(connectionState) {
        when (connectionState) {
            is WebRtcManager.ConnectionState.Connected -> {
                screenState = RoomScreenState.Connected
            }
            is WebRtcManager.ConnectionState.Failed -> {
                errorMessage = (connectionState as WebRtcManager.ConnectionState.Failed).error
            }
            is WebRtcManager.ConnectionState.Disconnected -> {
                if (screenState == RoomScreenState.Connected) {
                    screenState = RoomScreenState.Disconnected
                }
            }
            else -> {}
        }
    }
    
    // Handle participant events
    LaunchedEffect(participantEvent) {
        participantEvent?.let { event ->
            when (event) {
                is WebRtcManager.ParticipantEvent.Joined -> {
                    participantNotification = ParticipantNotification(
                        message = "Participant joined",
                        type = NotificationType.JOIN,
                        timestamp = event.timestamp
                    )
                    delay(3000)
                    participantNotification = null
                }
                is WebRtcManager.ParticipantEvent.Left -> {
                    participantNotification = ParticipantNotification(
                        message = "Participant left",
                        type = NotificationType.LEAVE,
                        timestamp = event.timestamp
                    )
                    delay(3000)
                    participantNotification = null
                }
                is WebRtcManager.ParticipantEvent.StartedSharing -> {
                    participantNotification = ParticipantNotification(
                        message = "Participant started sharing screen",
                        type = NotificationType.SCREEN_SHARE_START,
                        timestamp = event.timestamp
                    )
                    delay(3000)
                    participantNotification = null
                }
                is WebRtcManager.ParticipantEvent.StoppedSharing -> {
                    participantNotification = ParticipantNotification(
                        message = "Participant stopped sharing screen",
                        type = NotificationType.SCREEN_SHARE_STOP,
                        timestamp = event.timestamp
                    )
                    delay(3000)
                    participantNotification = null
                }
            }
            webRtcManager.clearParticipantEvent()
        }
    }
    
    // Get activity for orientation control
    val activity = context as? Activity
    
    // FULLSCREEN MODE - Render outside of Scaffold for true fullscreen
    // This must be at the top level to cover the entire screen including status bar
    val currentVideoTrack = remoteVideoTrack // Capture for smart cast
    if (isFullscreen && currentVideoTrack != null && screenState == RoomScreenState.Connected) {
        var controlsVisible by remember { mutableStateOf(true) }
        var videoRotation by remember { mutableStateOf(0) } // 0, 90, 180, 270
        
        // Hide system UI for true immersive fullscreen
        DisposableEffect(Unit) {
            val window = activity?.window
            val decorView = window?.decorView
            val originalSystemUiVisibility = decorView?.systemUiVisibility ?: 0
            
            // Set immersive fullscreen flags
            decorView?.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
            
            onDispose {
                decorView?.systemUiVisibility = originalSystemUiVisibility
            }
        }
        
        // Handle back press to exit fullscreen
        BackHandler(enabled = true) {
            isFullscreen = false
        }
        
        // Auto-hide controls after 3 seconds
        LaunchedEffect(controlsVisible) {
            if (controlsVisible) {
                delay(3000)
                controlsVisible = false
            }
        }
        
        // True fullscreen Box - covers entire screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) {
                    controlsVisible = !controlsVisible
                }
        ) {
            // Fullscreen video with manual rotation control
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(videoRotation.toFloat())
            ) {
                RemoteVideoView(
                    videoTrack = currentVideoTrack,
                    eglBase = webRtcManager.getEglBaseContext(),
                    modifier = Modifier.fillMaxSize(),
                    aspectRatio = remoteVideoAspectRatio,
                    scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
                )
            }
            
            // Controls overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Top controls
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rotate button - tap to rotate video 90° clockwise
                        IconButton(
                            onClick = { 
                                videoRotation = (videoRotation + 90) % 360
                            },
                            modifier = Modifier
                                .background(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.RotateRight,
                                contentDescription = "Rotate Video 90°",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Exit fullscreen button
                        IconButton(
                            onClick = { isFullscreen = false },
                            modifier = Modifier
                                .background(
                                    color = Color.Black.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "Exit Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    
                    // Bottom controls
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isVideoMuted = !isVideoMuted }) {
                            Icon(
                                imageVector = if (isVideoMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (isVideoMuted) "Unmute" else "Mute",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
        
        // Return early - don't render Scaffold when in fullscreen
        return
    }

    // Handle back button press based on state
    BackHandler(enabled = true) {
        when (screenState) {
            RoomScreenState.Connected -> {
                showBackConfirmDialog = true
            }
            RoomScreenState.Joining, RoomScreenState.Creating -> {
                // Cancel joining/creating and return to initial screen
                joiningTimeoutJob?.cancel()
                WebRtcService.stop(context)
                webRtcManager.disconnect()
                screenState = RoomScreenState.Initial
                errorMessage = "Join cancelled"
            }
            RoomScreenState.Initial, RoomScreenState.WaitingForPeer, RoomScreenState.Disconnected -> {
                // Show exit confirmation
                showExitConfirmDialog = true
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebRTC Room") },
                navigationIcon = {
                    IconButton(onClick = {
                        when (screenState) {
                            RoomScreenState.Connected -> {
                                showBackConfirmDialog = true
                            }
                            RoomScreenState.Joining, RoomScreenState.Creating -> {
                                // Cancel joining/creating and return to initial screen
                                joiningTimeoutJob?.cancel()
                                WebRtcService.stop(context)
                                webRtcManager.disconnect()
                                screenState = RoomScreenState.Initial
                                errorMessage = "Join cancelled"
                            }
                            RoomScreenState.Initial, RoomScreenState.WaitingForPeer, RoomScreenState.Disconnected -> {
                                showExitConfirmDialog = true
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            when (screenState) {
                RoomScreenState.Initial -> {
                    InitialScreen(
                        joinCode = joinCode,
                        onJoinCodeChange = { joinCode = it.uppercase().take(6) },
                        onCreateRoom = {
                            if (!hasPermissions) {
                                permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                                return@InitialScreen
                            }
                            // Start foreground service to keep connection alive
                            WebRtcService.start(context)
                            screenState = RoomScreenState.Creating
                            
                            // Set timeout for creating (15 seconds)
                            joiningTimeoutJob?.cancel()
                            joiningTimeoutJob = coroutineScope.launch {
                                delay(15000) // 15 seconds timeout
                                if (screenState == RoomScreenState.Creating) {
                                    WebRtcService.stop(context)
                                    webRtcManager.disconnect()
                                    errorMessage = "Failed to create room. Please try again."
                                    screenState = RoomScreenState.Initial
                                }
                            }
                            
                            webRtcManager.createRoom { createdRoomCode ->
                                joiningTimeoutJob?.cancel()
                                roomCode = createdRoomCode
                                screenState = RoomScreenState.WaitingForPeer
                            }
                        },
                        onJoinRoom = {
                            if (!hasPermissions) {
                                permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                                return@InitialScreen
                            }
                            if (joinCode.length == 6) {
                                // Start foreground service to keep connection alive
                                WebRtcService.start(context)
                                screenState = RoomScreenState.Joining
                                
                                // Set timeout for joining (30 seconds)
                                joiningTimeoutJob?.cancel()
                                joiningTimeoutJob = coroutineScope.launch {
                                    delay(30000) // 30 seconds timeout
                                    if (screenState == RoomScreenState.Joining) {
                                        WebRtcService.stop(context)
                                        webRtcManager.disconnect()
                                        errorMessage = "Join timed out. The room might be full or unavailable."
                                        screenState = RoomScreenState.Initial
                                    }
                                }
                                
                                webRtcManager.joinRoom(joinCode) { success ->
                                    joiningTimeoutJob?.cancel()
                                    if (!success) {
                                        WebRtcService.stop(context)
                                        errorMessage = "Failed to join room. Check the code and try again."
                                        screenState = RoomScreenState.Initial
                                    }
                                }
                            }
                        },
                        hasPermissions = hasPermissions,
                        onRequestPermissions = {
                            permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                        },
                        errorMessage = errorMessage,
                        onDismissError = { errorMessage = null }
                    )
                }
                
                RoomScreenState.Creating -> {
                    LoadingScreen("Creating room...")
                }
                
                RoomScreenState.Joining -> {
                    LoadingScreen("Joining room...")
                }
                
                RoomScreenState.WaitingForPeer -> {
                    WaitingScreen(
                        roomCode = roomCode,
                        onCopyCode = {
                            clipboardManager.setText(AnnotatedString(roomCode))
                        },
                        onCancel = {
                            WebRtcService.stop(context)
                            webRtcManager.disconnect()
                            screenState = RoomScreenState.Initial
                            roomCode = ""
                        }
                    )
                }
                
                RoomScreenState.Connected -> {
                    ConnectedScreen(
                        roomCode = roomCode.ifEmpty { joinCode },
                        screenShareActive = screenShareActive,
                        remoteVideoTrack = remoteVideoTrack,
                        remoteVideoAspectRatio = remoteVideoAspectRatio,
                        webRtcManager = webRtcManager,
                        isFullscreen = isFullscreen,
                        isVideoMuted = isVideoMuted,
                        isHdMode = isHdMode,
                        isSilentMode = isSilentMode,
                        isMicMuted = isMicMuted,
                        isLoudspeakerOn = isLoudspeakerOn,
                        participantCount = participants.size,
                        onToggleFullscreen = { isFullscreen = !isFullscreen },
                        onToggleVideoMute = { isVideoMuted = !isVideoMuted },
                        onToggleHdMode = { webRtcManager.setHdMode(!isHdMode) },
                        onToggleShareScreen = {
                            if (screenShareActive) {
                                // Stop both screen and audio sharing
                                webRtcManager.disableScreenShare()
                                webRtcManager.disableSystemAudio()
                                AudioCaptureService.stopCapture(context)
                            } else {
                                // Check if remote peer is sharing - show warning
                                if (remoteScreenShareActive) {
                                    showScreenShareWarning = true
                                } else {
                                    // Request MediaProjection permission for screen + audio sharing
                                    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                                }
                            }
                        },
                        onToggleSilentMode = { webRtcManager.toggleSilentMode() },
                        onToggleMicMute = { webRtcManager.toggleMicMute() },
                        onToggleLoudspeaker = { webRtcManager.toggleLoudspeaker() },
                        onDisconnect = {
                            AudioCaptureService.stopCapture(context)
                            WebRtcService.stop(context)
                            webRtcManager.disconnect()
                            screenState = RoomScreenState.Initial
                            roomCode = ""
                            joinCode = ""
                        }
                    )
                }
                
                RoomScreenState.Disconnected -> {
                    DisconnectedScreen(
                        onRetry = {
                            screenState = RoomScreenState.Initial
                            roomCode = ""
                            joinCode = ""
                        }
                    )
                }
            }
        }
            
            // Screen share warning dialog
            if (showScreenShareWarning) {
                AlertDialog(
                    onDismissRequest = { showScreenShareWarning = false },
                    title = { Text("Screen Share Warning") },
                    text = { 
                        Text("The other user is currently sharing their screen. If you start sharing, their screen share will automatically stop.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showScreenShareWarning = false
                                // Proceed with screen share - remote will auto-stop
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        ) {
                            Text("Share Anyway")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showScreenShareWarning = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            
            // Back confirmation dialog
            if (showBackConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showBackConfirmDialog = false },
                    title = { Text("Leave Room?") },
                    text = { Text("Are you sure you want to leave the room? This will disconnect the call.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showBackConfirmDialog = false
                                AudioCaptureService.stopCapture(context)
                                WebRtcService.stop(context)
                                webRtcManager.disconnect()
                                screenState = RoomScreenState.Initial
                                roomCode = ""
                                joinCode = ""
                            }
                        ) {
                            Text("Leave")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBackConfirmDialog = false }) {
                            Text("Stay")
                        }
                    }
                )
            }
            
            // App exit confirmation dialog
            if (showExitConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showExitConfirmDialog = false },
                    title = { Text("Exit App?") },
                    text = { Text("Are you sure you want to exit VoiceLink Connect?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showExitConfirmDialog = false
                                onBackClick()
                            }
                        ) {
                            Text("Exit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitConfirmDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            
            // Participant notification overlay (top center, non-overlapping)
            participantNotification?.let { notification ->
                ParticipantNotificationBanner(
                    notification = notification,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun InitialScreen(
    joinCode: String,
    onJoinCodeChange: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit
) {
    // Permission banner if not granted
    if (!hasPermissions) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Permissions Required",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Microphone access is needed for voice chat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                TextButton(onClick = onRequestPermissions) {
                    Text("Grant")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    Box(
        modifier = Modifier
            .size(80.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF8B5CF6),
                        Color(0xFF6366F1)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }
    }
    
    Spacer(modifier = Modifier.height(20.dp))
    
    Text(
        text = "StreamSync",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
    
    Spacer(modifier = Modifier.height(4.dp))
    
    Text(
        text = "Share Everything, Seamlessly",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium
    )
    
    Spacer(modifier = Modifier.height(40.dp))
    
    // Create Room Button
    Button(
        onClick = onCreateRoom,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Create Room", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "  OR  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    // Join Room Section
    Text(
        text = "Join with Room Code",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    
    Spacer(modifier = Modifier.height(12.dp))
    
    OutlinedTextField(
        value = joinCode,
        onValueChange = onJoinCodeChange,
        label = { Text("Room Code", style = MaterialTheme.typography.labelMedium) },
        placeholder = { Text("ABC123") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Medium
        ),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (joinCode.length == 6) onJoinRoom() }
        )
    )
    
    Spacer(modifier = Modifier.height(12.dp))
    
    Button(
        onClick = onJoinRoom,
        enabled = joinCode.length == 6,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Join Room", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
    
    // Error Message
    errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = onDismissError, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ColumnScope.WaitingScreen(
    roomCode: String,
    onCopyCode: () -> Unit,
    onCancel: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))
    
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.HourglassTop,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
    
    Spacer(modifier = Modifier.height(20.dp))
    
    Text(
        text = "Waiting for peer...",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    
    Spacer(modifier = Modifier.height(4.dp))
    
    Text(
        text = "Share this code with your friend",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    Spacer(modifier = Modifier.height(28.dp))
    
    // Room Code Display
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Room Code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = roomCode,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 6.sp
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onCopyCode,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy Code", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    CircularProgressIndicator(
        modifier = Modifier.size(40.dp),
        strokeWidth = 3.dp
    )
    
    Spacer(modifier = Modifier.weight(1f))
    
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Cancel", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ColumnScope.ConnectedScreen(
    roomCode: String,
    screenShareActive: Boolean,
    remoteVideoTrack: VideoTrack?,
    remoteVideoAspectRatio: Float,
    webRtcManager: WebRtcManager,
    isFullscreen: Boolean,
    isVideoMuted: Boolean,
    isHdMode: Boolean,
    isSilentMode: Boolean,
    isMicMuted: Boolean,
    isLoudspeakerOn: Boolean,
    participantCount: Int,
    onToggleFullscreen: () -> Unit,
    onToggleVideoMute: () -> Unit,
    onToggleHdMode: () -> Unit,
    onToggleShareScreen: () -> Unit,
    onToggleSilentMode: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleLoudspeaker: () -> Unit,
    onDisconnect: () -> Unit
) {
    // Fullscreen is now handled at the RoomScreen level, outside of Scaffold
    // This ensures true fullscreen without app bar or other UI elements
    
    // Use a scrollable column for content to ensure disconnect button stays accessible
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Remote Video Display (if receiving video)
        if (remoteVideoTrack != null) {
            val isPortraitVideo = remoteVideoAspectRatio < 1f
            Card(
                modifier = Modifier
                    .then(
                        if (isPortraitVideo) {
                            // Portrait video: limit width and constrain height
                            Modifier
                                .fillMaxWidth(0.6f)
                                .heightIn(max = 400.dp) // Limit max height for portrait
                        } else {
                            // Landscape video: fill width and use aspect ratio
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(remoteVideoAspectRatio.coerceIn(0.5f, 2.5f))
                        }
                    )
                    .clickable { onToggleFullscreen() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Box {
                    // Preview video with SCALE_ASPECT_FILL for better display
                    RemoteVideoView(
                        videoTrack = remoteVideoTrack,
                        eglBase = webRtcManager.getEglBaseContext(),
                        modifier = Modifier.fillMaxSize(),
                        aspectRatio = remoteVideoAspectRatio,
                        scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    )
                
                // Video controls overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isVideoMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onToggleVideoMute() }
                    )
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onToggleFullscreen() }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    } else {
        // Placeholder when no video
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No video stream",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
    
    // Connection info row
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF8B5CF6).copy(alpha = 0.15f),
                        Color(0xFF6366F1).copy(alpha = 0.15f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF10B981),
                                    Color(0xFF059669)
                                )
                            ),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8B5CF6)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = roomCode,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF6366F1)
                    )
                    Text(
                        text = "${participantCount + 1} users",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF6366F1),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    // Control buttons - Compact grid layout
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // First row: Share Screen and HD toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShareButton(
                icon = if (screenShareActive) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                label = if (screenShareActive) "Stop" else "Share",
                isActive = screenShareActive,
                onClick = onToggleShareScreen,
                modifier = Modifier.weight(1f)
            )
            ShareButton(
                icon = Icons.Default.Hd,
                label = "HD",
                isActive = isHdMode,
                isPremium = true,
                onClick = onToggleHdMode,
                modifier = Modifier.weight(1f)
            )
        }
        
        // Second row: Silent App and Mute Me
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShareButton(
                icon = if (isSilentMode) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                label = "Silent",
                isActive = isSilentMode,
                onClick = onToggleSilentMode,
                modifier = Modifier.weight(1f)
            )
            ShareButton(
                icon = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = "Mute",
                isActive = isMicMuted,
                onClick = onToggleMicMute,
                modifier = Modifier.weight(1f)
            )
        }
        
        // Third row: Loudspeaker
        ShareButton(
            icon = if (isLoudspeakerOn) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk,
            label = "Loudspeaker",
            isActive = isLoudspeakerOn,
            onClick = onToggleLoudspeaker,
            modifier = Modifier.fillMaxWidth()
        )
    }
    
    // Info text
    Spacer(modifier = Modifier.height(14.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    screenShareActive -> Color(0xFF8B5CF6).copy(alpha = 0.1f)
                    isSilentMode || isMicMuted -> Color(0xFFF59E0B).copy(alpha = 0.1f)
                    else -> Color(0xFF6366F1).copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = when {
                screenShareActive && isHdMode -> "✨ HD Sharing Active"
                screenShareActive -> "🎬 Sharing Active"
                isSilentMode && isMicMuted -> "🔇 Silent & Muted"
                isSilentMode -> "🔇 Silent Mode"
                isMicMuted -> "🎤 Mic Muted"
                else -> "⚡ Ready to Share"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            color = when {
                screenShareActive -> Color(0xFF8B5CF6)
                isSilentMode || isMicMuted -> Color(0xFFF59E0B)
                else -> Color(0xFF6366F1)
            }
        )
    }
    
    } // End of scrollable column
    
    Spacer(modifier = Modifier.height(10.dp))
    
    // Disconnect button - always visible at bottom
    Button(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFEF4444)
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp
        )
    ) {
        Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text("End Call", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun ShareButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    isPremium: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = when {
            isPremium && isActive -> ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFBBF24),
                contentColor = Color(0xFF1F2937)
            )
            isPremium -> ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1F2937),
                contentColor = Color(0xFFFBBF24)
            )
            isActive -> ButtonDefaults.buttonColors(
                containerColor = Color(0xFF8B5CF6)
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1F2937),
                contentColor = Color(0xFF9CA3AF)
            )
        },
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isActive) 6.dp else 2.dp
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun RemoteVideoView(
    videoTrack: VideoTrack,
    eglBase: org.webrtc.EglBase,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
) {
    // Key by videoTrack id to force recreation when track changes
    // This fixes the black screen bug when switching screen sharers
    key(videoTrack.id()) {
        var surfaceViewRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
        
        DisposableEffect(Unit) {
            onDispose {
                surfaceViewRenderer?.let { renderer ->
                    try {
                        videoTrack.removeSink(renderer)
                        renderer.release()
                        Log.d("RemoteVideoView", "Renderer released for track: ${videoTrack.id()}")
                    } catch (e: Exception) {
                        Log.e("RemoteVideoView", "Error releasing renderer", e)
                    }
                }
            }
        }
        
        AndroidView(
            modifier = modifier,
            factory = { context ->
                Log.d("RemoteVideoView", "Creating new renderer for track: ${videoTrack.id()}")
                SurfaceViewRenderer(context).apply {
                    init(eglBase.eglBaseContext, null)
                    setScalingType(scalingType)
                    setEnableHardwareScaler(true)
                    setMirror(false)
                    surfaceViewRenderer = this
                    
                    // Add sink to the video track
                    videoTrack.addSink(this)
                    Log.d("RemoteVideoView", "Video sink added for track: ${videoTrack.id()}")
                }
            },
            update = { renderer ->
                // Ensure scaling type is updated
                renderer.setScalingType(scalingType)
            }
        )
    }
}

@Composable
private fun DisconnectedScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Disconnected",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) {
                Text("Start Over")
            }
        }
    }
}

sealed class RoomScreenState {
    data object Initial : RoomScreenState()
    data object Creating : RoomScreenState()
    data object Joining : RoomScreenState()
    data object WaitingForPeer : RoomScreenState()
    data object Connected : RoomScreenState()
    data object Disconnected : RoomScreenState()
}

data class ParticipantNotification(
    val message: String,
    val type: NotificationType,
    val timestamp: Long
)

enum class NotificationType {
    JOIN, LEAVE, SCREEN_SHARE_START, SCREEN_SHARE_STOP
}

@Composable
fun ParticipantNotificationBanner(
    notification: ParticipantNotification,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 320.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (notification.type) {
                NotificationType.JOIN -> Color(0xFF10B981).copy(alpha = 0.96f)
                NotificationType.LEAVE -> Color(0xFFF59E0B).copy(alpha = 0.96f)
                NotificationType.SCREEN_SHARE_START -> Color(0xFF3B82F6).copy(alpha = 0.96f)
                NotificationType.SCREEN_SHARE_STOP -> Color(0xFF6366F1).copy(alpha = 0.96f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (notification.type) {
                    NotificationType.JOIN -> Icons.Default.PersonAdd
                    NotificationType.LEAVE -> Icons.Default.PersonRemove
                    NotificationType.SCREEN_SHARE_START -> Icons.Default.ScreenShare
                    NotificationType.SCREEN_SHARE_STOP -> Icons.Default.StopScreenShare
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = notification.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

package com.voicelink.connect.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    
    // State for showing screen share warning dialog
    var showScreenShareWarning by remember { mutableStateOf(false) }
    
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
            signaling.cleanup()
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
    
    // Get activity for orientation control
    val activity = context as? Activity
    
    // FULLSCREEN MODE - Render outside of Scaffold for true fullscreen
    // This must be at the top level to cover the entire screen including status bar
    if (isFullscreen && remoteVideoTrack != null && screenState == RoomScreenState.Connected) {
        var controlsVisible by remember { mutableStateOf(true) }
        val isLandscapeVideo = remoteVideoAspectRatio > 1f
        
        // Lock orientation based on video content
        DisposableEffect(isLandscapeVideo) {
            val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            
            activity?.requestedOrientation = if (isLandscapeVideo) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            
            onDispose {
                activity?.requestedOrientation = originalOrientation
            }
        }
        
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
            // Fullscreen video
            RemoteVideoView(
                videoTrack = remoteVideoTrack,
                eglBase = webRtcManager.getEglBaseContext(),
                modifier = Modifier.fillMaxSize(),
                aspectRatio = remoteVideoAspectRatio,
                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
            )
            
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
                    // Exit fullscreen button
                    IconButton(
                        onClick = { isFullscreen = false },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebRTC Room") },
                navigationIcon = {
                    IconButton(onClick = {
                        webRtcManager.disconnect()
                        onBackClick()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
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
                            webRtcManager.createRoom { createdRoomCode ->
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
                                webRtcManager.joinRoom(joinCode) { success ->
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
                        systemAudioActive = systemAudioActive,
                        screenShareActive = screenShareActive,
                        remoteVideoTrack = remoteVideoTrack,
                        remoteVideoAspectRatio = remoteVideoAspectRatio,
                        webRtcManager = webRtcManager,
                        isFullscreen = isFullscreen,
                        isVideoMuted = isVideoMuted,
                        isHdMode = isHdMode,
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
                        onToggleShareAudio = {
                            if (systemAudioActive) {
                                webRtcManager.disableSystemAudio()
                                if (!screenShareActive) {
                                    AudioCaptureService.stopCapture(context)
                                }
                            } else {
                                // Request MediaProjection permission for audio only
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                audioProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        },
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
    
    Spacer(modifier = Modifier.height(32.dp))
    
    Icon(
        imageVector = Icons.Default.Wifi,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    
    Spacer(modifier = Modifier.height(24.dp))
    
    Text(
        text = "P2P Audio Connection",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
    
    Text(
        text = "Create a room or join with a code",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    Spacer(modifier = Modifier.height(48.dp))
    
    // Create Room Button
    Button(
        onClick = onCreateRoom,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Create Room", fontSize = 16.sp)
    }
    
    Spacer(modifier = Modifier.height(32.dp))
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "  OR  ",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
    
    Spacer(modifier = Modifier.height(32.dp))
    
    // Join Room Section
    Text(
        text = "Join with Room Code",
        style = MaterialTheme.typography.titleMedium
    )
    
    Spacer(modifier = Modifier.height(16.dp))
    
    OutlinedTextField(
        value = joinCode,
        onValueChange = onJoinCodeChange,
        label = { Text("Room Code") },
        placeholder = { Text("ABC123") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 4.sp
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (joinCode.length == 6) onJoinRoom() }
        )
    )
    
    Spacer(modifier = Modifier.height(16.dp))
    
    Button(
        onClick = onJoinRoom,
        enabled = joinCode.length == 6,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Login, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Join Room", fontSize = 16.sp)
    }
    
    // Error Message
    errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissError) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
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
            CircularProgressIndicator(modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = message, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ColumnScope.WaitingScreen(
    roomCode: String,
    onCopyCode: () -> Unit,
    onCancel: () -> Unit
) {
    Spacer(modifier = Modifier.height(48.dp))
    
    Icon(
        imageVector = Icons.Default.HourglassTop,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    
    Spacer(modifier = Modifier.height(24.dp))
    
    Text(
        text = "Waiting for peer...",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    
    Text(
        text = "Share this code with your friend",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    
    Spacer(modifier = Modifier.height(32.dp))
    
    // Room Code Display
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Room Code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = roomCode,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onCopyCode) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Code")
            }
        }
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    CircularProgressIndicator()
    
    Spacer(modifier = Modifier.weight(1f))
    
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Cancel")
    }
}

@Composable
private fun ColumnScope.ConnectedScreen(
    roomCode: String,
    systemAudioActive: Boolean,
    screenShareActive: Boolean,
    remoteVideoTrack: VideoTrack?,
    remoteVideoAspectRatio: Float,
    webRtcManager: WebRtcManager,
    isFullscreen: Boolean,
    isVideoMuted: Boolean,
    isHdMode: Boolean,
    onToggleFullscreen: () -> Unit,
    onToggleVideoMute: () -> Unit,
    onToggleHdMode: () -> Unit,
    onToggleShareScreen: () -> Unit,
    onToggleShareAudio: () -> Unit,
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
        // Dynamically adapt to portrait or landscape based on incoming video stream
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
                    // Preview video - pass aspect ratio for proper handling
                    RemoteVideoView(
                        videoTrack = remoteVideoTrack,
                        eglBase = webRtcManager.getEglBaseContext(),
                        modifier = Modifier.fillMaxSize(),
                        aspectRatio = remoteVideoAspectRatio,
                        scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.WifiTethering,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "Room: $roomCode",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    // Control buttons - Share Screen, Share Audio, HD toggle
    // Improved layout with proper spacing to prevent overlap
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // First row: Share Screen and Share Audio
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
        ) {
            // Share Screen button (shares screen + audio)
            ShareButton(
                icon = if (screenShareActive) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                label = if (screenShareActive) "Stop Screen" else "Share Screen",
                isActive = screenShareActive,
                onClick = onToggleShareScreen
            )
            
            // Share Audio button (shares audio only)
            ShareButton(
                icon = if (systemAudioActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                label = if (systemAudioActive) "Stop Audio" else "Share Audio",
                isActive = systemAudioActive,
                onClick = onToggleShareAudio
            )
        }
        
        // Second row: HD toggle
        ShareButton(
            icon = Icons.Default.Hd,
            label = if (isHdMode) "HD On" else "HD Off",
            isActive = isHdMode,
            isPremium = true,
            onClick = onToggleHdMode
        )
    }
    
    // Info text
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = when {
                screenShareActive && isHdMode -> "Sharing screen in HD (1080p). Higher bandwidth usage."
                screenShareActive -> "Sharing screen in SD (720p)."
                systemAudioActive -> "Sharing audio only."
                else -> "Tap 'Share Screen' to share screen + audio."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            textAlign = TextAlign.Center
        )
    }
    
    } // End of scrollable column
    
    Spacer(modifier = Modifier.height(12.dp))
    
    // Disconnect button - always visible at bottom
    Button(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
    ) {
        Icon(Icons.Default.CallEnd, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Disconnect")
    }
}

@Composable
private fun ShareButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    isPremium: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .height(56.dp)
                .widthIn(min = 100.dp),
            shape = RoundedCornerShape(12.dp),
            colors = when {
                isPremium && isActive -> ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD700) // Gold for premium active
                )
                isPremium -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = Color(0xFFFFD700) // Gold text for premium
                )
                isActive -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
                else -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun RemoteVideoView(
    videoTrack: VideoTrack,
    eglBase: org.webrtc.EglBase,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f, // Add aspect ratio parameter for dynamic updates
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
) {
    // Determine if video is landscape or portrait for keying
    val isLandscape = aspectRatio > 1f
    
    // Key the view by orientation to force recreation when it changes significantly
    // This ensures proper layout when switching between portrait and landscape
    key(isLandscape) {
        var surfaceViewRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
        
        DisposableEffect(videoTrack) {
            onDispose {
                surfaceViewRenderer?.let { renderer ->
                    try {
                        videoTrack.removeSink(renderer)
                        renderer.release()
                    } catch (e: Exception) {
                        Log.e("RemoteVideoView", "Error releasing renderer", e)
                    }
                }
            }
        }
        
        AndroidView(
            modifier = modifier,
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(eglBase.eglBaseContext, null)
                    setScalingType(scalingType)
                    setEnableHardwareScaler(true)
                    setMirror(false)
                    surfaceViewRenderer = this
                    videoTrack.addSink(this)
                }
            },
            update = { renderer ->
                // Update scaling type if needed
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

private sealed class RoomScreenState {
    data object Initial : RoomScreenState()
    data object Creating : RoomScreenState()
    data object Joining : RoomScreenState()
    data object WaitingForPeer : RoomScreenState()
    data object Connected : RoomScreenState()
    data object Disconnected : RoomScreenState()
}

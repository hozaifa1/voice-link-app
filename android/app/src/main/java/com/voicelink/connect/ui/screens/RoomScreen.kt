package com.voicelink.connect.ui.screens

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    
    // MediaProjection launcher for screen sharing (video)
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start foreground service for media projection
            AudioCaptureService.prepareCapture(context)
            // Store permission and enable screen share
            webRtcManager.storeScreenSharePermission(result.resultCode, result.data!!)
            webRtcManager.enableScreenShare()
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
                        isMuted = isMuted,
                        remoteAudioActive = remoteAudioActive,
                        systemAudioActive = systemAudioActive,
                        screenShareActive = screenShareActive,
                        remoteVideoTrack = remoteVideoTrack,
                        webRtcManager = webRtcManager,
                        onToggleMute = {
                            isMuted = !isMuted
                            webRtcManager.setAudioEnabled(!isMuted)
                        },
                        onToggleSystemAudio = {
                            if (systemAudioActive) {
                                webRtcManager.disableSystemAudio()
                                AudioCaptureService.stopCapture(context)
                            } else {
                                // Request MediaProjection permission for audio
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                audioProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        },
                        onToggleScreenShare = {
                            if (screenShareActive) {
                                webRtcManager.disableScreenShare()
                                AudioCaptureService.stopCapture(context)
                            } else {
                                // Request MediaProjection permission for screen sharing
                                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
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
    isMuted: Boolean,
    remoteAudioActive: Boolean,
    systemAudioActive: Boolean,
    screenShareActive: Boolean,
    remoteVideoTrack: VideoTrack?,
    webRtcManager: WebRtcManager,
    onToggleMute: () -> Unit,
    onToggleSystemAudio: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onDisconnect: () -> Unit
) {
    // Remote Video Display (if receiving video)
    if (remoteVideoTrack != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp)
        ) {
            RemoteVideoView(
                videoTrack = remoteVideoTrack,
                eglBase = webRtcManager.getEglBaseContext(),
                modifier = Modifier.fillMaxSize()
            )
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
    
    Spacer(modifier = Modifier.height(16.dp))
    
    // Status indicators
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatusIndicator(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = if (isMuted) "Muted" else "Mic On",
            isActive = !isMuted
        )
        StatusIndicator(
            icon = Icons.Default.Headphones,
            label = if (remoteAudioActive) "Receiving" else "Waiting",
            isActive = remoteAudioActive
        )
        StatusIndicator(
            icon = Icons.Default.Videocam,
            label = if (screenShareActive) "Sharing" else "Off",
            isActive = screenShareActive
        )
        StatusIndicator(
            icon = Icons.Default.VolumeUp,
            label = if (systemAudioActive) "Audio On" else "Audio Off",
            isActive = systemAudioActive
        )
    }
    
    Spacer(modifier = Modifier.height(24.dp))
    
    // Control buttons row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Mute button
        ControlButton(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = if (isMuted) "Unmute" else "Mute",
            isActive = !isMuted,
            isError = isMuted,
            onClick = onToggleMute
        )
        
        // Screen Share button
        ControlButton(
            icon = if (screenShareActive) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
            label = if (screenShareActive) "Stop Video" else "Share Screen",
            isActive = screenShareActive,
            onClick = onToggleScreenShare
        )
        
        // Share Audio button
        ControlButton(
            icon = if (systemAudioActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
            label = if (systemAudioActive) "Stop Audio" else "Share Audio",
            isActive = systemAudioActive,
            onClick = onToggleSystemAudio
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
                screenShareActive && systemAudioActive -> "Sharing your screen and audio with your friend."
                screenShareActive -> "Sharing your screen. Tap 'Share Audio' to also share app sounds."
                systemAudioActive -> "Sharing audio only. Tap 'Share Screen' to share video."
                else -> "Tap 'Share Screen' to share your screen, or 'Share Audio' to share app sounds."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
            textAlign = TextAlign.Center
        )
    }
    
    Spacer(modifier = Modifier.weight(1f))
    
    // Disconnect button
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
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    isError: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = when {
                isError -> ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
                isActive -> ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
                else -> ButtonDefaults.filledTonalButtonColors()
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RemoteVideoView(
    videoTrack: VideoTrack,
    eglBase: org.webrtc.EglBase,
    modifier: Modifier = Modifier
) {
    var surfaceViewRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    
    DisposableEffect(videoTrack) {
        onDispose {
            surfaceViewRenderer?.let { renderer ->
                videoTrack.removeSink(renderer)
                renderer.release()
            }
        }
    }
    
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                setMirror(false)
                surfaceViewRenderer = this
                videoTrack.addSink(this)
            }
        }
    )
}

@Composable
private fun StatusIndicator(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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

package com.voicelink.connect.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.voicelink.connect.audio.AudioCaptureManager
import com.voicelink.connect.service.AudioCaptureService
import com.voicelink.connect.util.PermissionHelper

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AudioCaptureTestScreen(
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val captureState by AudioCaptureManager.captureState.collectAsState()
    val audioLevel by AudioCaptureManager.audioLevel.collectAsState()
    val captureStats by AudioCaptureManager.captureStats.collectAsState()

    // Audio permission state
    val audioPermissionState = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
    
    // MediaProjection launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start the foreground service with MediaProjection result
            AudioCaptureService.startCapture(context, result.resultCode, result.data!!)
        } else {
            AudioCaptureManager.reset()
        }
    }

    // Permission launcher for notifications (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun requestMediaProjection() {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        AudioCaptureManager.requestMediaProjection()
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun startCapture() {
        // Check audio permission first
        if (!audioPermissionState.status.isGranted) {
            audioPermissionState.launchPermissionRequest()
            return
        }

        // Check notification permission for Android 13+
        if (!PermissionHelper.hasNotificationPermission(context)) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Request MediaProjection
        requestMediaProjection()
    }

    fun stopCapture() {
        AudioCaptureService.stopCapture(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Title
            Text(
                text = "Audio Capture Test",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Test mic + system audio capture",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Audio Level Visualizer
            AudioLevelVisualizer(
                level = audioLevel,
                isCapturing = captureState is AudioCaptureManager.CaptureState.Capturing
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status Card
            StatusCard(captureState = captureState, stats = captureStats)

            Spacer(modifier = Modifier.height(32.dp))

            // Control Button
            when (captureState) {
                is AudioCaptureManager.CaptureState.Idle,
                is AudioCaptureManager.CaptureState.Stopped,
                is AudioCaptureManager.CaptureState.Error -> {
                    StartButton(onClick = { startCapture() })
                }
                is AudioCaptureManager.CaptureState.Capturing -> {
                    StopButton(onClick = { stopCapture() })
                }
                is AudioCaptureManager.CaptureState.Starting,
                is AudioCaptureManager.CaptureState.WaitingForPermission -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Permission Status
            PermissionStatus(
                hasAudioPermission = audioPermissionState.status.isGranted,
                hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Info text
            Text(
                text = "This test captures mic audio (with echo cancellation)\nand system audio, mixes them in real-time.\nNo audio is stored to disk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AudioLevelVisualizer(
    level: Float,
    isCapturing: Boolean
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isCapturing) 1f + (level * 0.3f) else 1f,
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(animatedScale)
            .clip(CircleShape)
            .background(
                if (isCapturing) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inner circle that pulses with audio level
        if (isCapturing) {
            Box(
                modifier = Modifier
                    .size(120.dp + (level * 20).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            )
        }

        Icon(
            imageVector = if (isCapturing) Icons.Default.VolumeUp else Icons.Default.MicOff,
            contentDescription = null,
            modifier = Modifier.size(60.dp),
            tint = if (isCapturing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun StatusCard(
    captureState: AudioCaptureManager.CaptureState,
    stats: AudioCaptureManager.CaptureStats
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val (statusText, statusColor) = when (captureState) {
                    is AudioCaptureManager.CaptureState.Idle -> "Ready" to MaterialTheme.colorScheme.outline
                    is AudioCaptureManager.CaptureState.WaitingForPermission -> "Waiting for permission..." to MaterialTheme.colorScheme.tertiary
                    is AudioCaptureManager.CaptureState.Starting -> "Starting..." to MaterialTheme.colorScheme.tertiary
                    is AudioCaptureManager.CaptureState.Capturing -> "Capturing" to Color(0xFF4CAF50)
                    is AudioCaptureManager.CaptureState.Stopped -> "Stopped" to MaterialTheme.colorScheme.outline
                    is AudioCaptureManager.CaptureState.Error -> "Error" to MaterialTheme.colorScheme.error
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Error message if any
            if (captureState is AudioCaptureManager.CaptureState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = captureState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Stats when capturing
            if (stats.isCapturing) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        label = "Duration",
                        value = formatDuration(stats.durationMs)
                    )
                    StatItem(
                        label = "Data Processed",
                        value = formatBytes(stats.bytesProcessed)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun StartButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4CAF50)
        )
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Start Capture",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
    ) {
        Icon(
            imageVector = Icons.Default.Stop,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Stop Capture",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun PermissionStatus(
    hasAudioPermission: Boolean,
    hasNotificationPermission: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PermissionChip(
            label = "Mic",
            granted = hasAudioPermission
        )
        Spacer(modifier = Modifier.width(12.dp))
        PermissionChip(
            label = "Notifications",
            granted = hasNotificationPermission
        )
    }
}

@Composable
private fun PermissionChip(label: String, granted: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (granted) {
            Color(0xFF4CAF50).copy(alpha = 0.15f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 60000) % 60
    val hours = ms / 3600000
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

package com.voicelink.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.voicelink.connect.audio.AudioCaptureManager
import com.voicelink.connect.service.AudioCaptureService
import com.voicelink.connect.ui.theme.VoiceLinkTheme
import com.voicelink.connect.ui.screens.AudioCaptureTestScreen
import com.voicelink.connect.ui.screens.HomeScreen

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_PENDING_PROJECTION = "pending_projection"
    }

    private var pendingMediaProjection = false
    
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Restore state
        pendingMediaProjection = savedInstanceState?.getBoolean(KEY_PENDING_PROJECTION, false) ?: false
        Log.d(TAG, "onCreate: pendingMediaProjection=$pendingMediaProjection")
        
        // Register launcher BEFORE setContent
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d(TAG, "MediaProjection result callback fired: resultCode=${result.resultCode}, data=${result.data}")
            pendingMediaProjection = false
            
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection granted, initializing capture directly")
                
                // Get MediaProjection directly here instead of passing through service
                val success = AudioCaptureManager.onMediaProjectionResult(this, result.resultCode, result.data!!)
                Log.d(TAG, "onMediaProjectionResult returned: $success")
                
                if (success) {
                    val captureStarted = AudioCaptureManager.startCapture()
                    Log.d(TAG, "startCapture returned: $captureStarted")
                    if (!captureStarted) {
                        Log.e(TAG, "Failed to start capture")
                        AudioCaptureService.stopCapture(this)
                    }
                } else {
                    Log.e(TAG, "Failed to get MediaProjection")
                    AudioCaptureService.stopCapture(this)
                    AudioCaptureManager.reset()
                }
            } else {
                Log.d(TAG, "MediaProjection denied or cancelled")
                AudioCaptureService.stopCapture(this)
                AudioCaptureManager.reset()
            }
        }
        
        enableEdgeToEdge()
        setContent {
            VoiceLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // For Step 4 testing: Show AudioCaptureTestScreen
                    // TODO: Replace with proper navigation after Step 4 is verified
                    var showAudioTest by remember { mutableStateOf(true) }
                    
                    if (showAudioTest) {
                        AudioCaptureTestScreen(
                            onBackClick = { showAudioTest = false },
                            onRequestMediaProjection = { requestMediaProjection() }
                        )
                    } else {
                        HomeScreen()
                    }
                }
            }
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_PROJECTION, pendingMediaProjection)
        Log.d(TAG, "onSaveInstanceState: pendingMediaProjection=$pendingMediaProjection")
    }
    
    fun requestMediaProjection() {
        Log.d(TAG, "requestMediaProjection called")
        
        // Start foreground service FIRST
        AudioCaptureService.prepareCapture(this)
        AudioCaptureManager.requestMediaProjection()
        
        // Then request permission
        pendingMediaProjection = true
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}

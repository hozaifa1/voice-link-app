package com.voicelink.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.voicelink.connect.ui.theme.VoiceLinkTheme
import com.voicelink.connect.ui.screens.AudioCaptureTestScreen
import com.voicelink.connect.ui.screens.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            onBackClick = { showAudioTest = false }
                        )
                    } else {
                        HomeScreen()
                    }
                }
            }
        }
    }
}

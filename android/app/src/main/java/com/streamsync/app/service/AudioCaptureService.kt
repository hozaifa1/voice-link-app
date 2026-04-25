package com.streamsync.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamsync.app.MainActivity
import com.streamsync.app.R
import com.streamsync.app.StreamSyncApp
import com.streamsync.app.audio.AudioCaptureManager

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_START_FOREGROUND = "com.streamsync.app.action.START_FOREGROUND"
        const val ACTION_START_CAPTURE = "com.streamsync.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.streamsync.app.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set

        fun prepareCapture(context: Context) {
            Log.d(TAG, "prepareCapture called")
            val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START_FOREGROUND
            }
            context.startForegroundService(serviceIntent)
        }

        fun startCapture(context: Context, resultCode: Int, data: Intent) {
            Log.d(TAG, "startCapture called with resultCode: $resultCode")
            val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startService(serviceIntent)
        }

        fun stopCapture(context: Context) {
            Log.d(TAG, "stopCapture called")
            val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(serviceIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                Log.d(TAG, "Starting foreground service (prepare phase)")
                startForegroundService()
                isRunning = true
            }
            ACTION_START_CAPTURE -> {
                Log.d(TAG, "Received MediaProjection result, starting capture")
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != -1 && resultData != null) {
                    initializeCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid MediaProjection result: resultCode=$resultCode, data=$resultData")
                    AudioCaptureManager.reset()
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received")
                stopCapture()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        Log.d(TAG, "Starting foreground service")
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeCapture(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "Initializing capture with resultCode: $resultCode")
        
        // Pass MediaProjection result to manager
        val success = AudioCaptureManager.onMediaProjectionResult(this, resultCode, resultData)
        if (success) {
            // Start the actual audio capture
            if (!AudioCaptureManager.startCapture()) {
                Log.e(TAG, "Failed to start capture")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Failed to initialize MediaProjection")
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AudioCaptureService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, StreamSyncApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_sharing),
                stopIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        AudioCaptureManager.stopCapture()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        isRunning = false
        stopCapture()
        super.onDestroy()
    }
}

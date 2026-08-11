package com.example.alibi.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.example.alibi.MainActivity
import com.example.alibi.receiver.CallActionReceiver
import android.graphics.drawable.Icon as AndroidIcon

class CallNotificationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Alibi:CallWakeLock").apply {
            acquire(10 * 60 * 1000L) // 10 minutes max safety
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Unknown"
        val isIncoming = intent?.getBooleanExtra(EXTRA_IS_INCOMING, false) ?: false
        val isMissed = intent?.getBooleanExtra(EXTRA_IS_MISSED, false) ?: false
        val isDialing = intent?.getBooleanExtra(EXTRA_IS_DIALING, false) ?: false
        val isSimulated = intent?.getBooleanExtra(EXTRA_IS_SIMULATED, true) ?: true
        val startTime = intent?.getLongExtra(EXTRA_START_TIME, 0L) ?: 0L

        createNotificationChannels()
        showNotification(phoneNumber, isIncoming, isMissed, isDialing, isSimulated, startTime)

        return START_STICKY
    }

    @SuppressLint("InsecureFullscreenIntent", "FullScreenIntentPolicy")
    private fun showNotification(phoneNumber: String, isIncoming: Boolean, isMissed: Boolean, isDialing: Boolean, isSimulated: Boolean, startTime: Long = 0L) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // All simulated calls use the silent channel to stay in the shade without pushing
        val channelId = if (isSimulated) CHANNEL_ID_SILENT else CHANNEL_ID

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder()
                .setName(phoneNumber)
                .setImportant(true)
                .build()

            val hangupIntent = PendingIntent.getBroadcast(
                this, 1, 
                Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_HANGUP }, 
                PendingIntent.FLAG_IMMUTABLE
            )
            val answerIntent = PendingIntent.getBroadcast(
                this, 2, 
                Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_ANSWER }, 
                PendingIntent.FLAG_IMMUTABLE
            )

            val builder = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(when {
                    isMissed -> "Missed call attempt..."
                    isDialing -> "Connecting..."
                    else -> "Active Call"
                })
                .setContentText(phoneNumber)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setCategory(Notification.CATEGORY_CALL)

            // Tiered Notification Strategy:
            // 1. Ringing (Incoming): CallStyle.forIncomingCall -> Colorful buttons, NO timer.
            // 2. Active/Connecting (Ongoing/Outgoing): CallStyle.forOngoingCall -> Colorful button, WITH timer.
            // 3. Missed: Standard Notification -> Standard buttons.
            
            val isRinging = isIncoming && !isMissed && !isDialing
            val isActive = !isIncoming && !isMissed && !isDialing
            val isConnecting = isDialing && !isMissed

            val canUseFullScreen = canUseFullScreenIntent()

            if (isRinging && !isSimulated) {
                // Real incoming call gets full screen intent priority if permitted
                if (canUseFullScreen) {
                    builder.setFullScreenIntent(pendingIntent, true)
                }
            }

            if (isRinging) {
                builder.style = Notification.CallStyle.forIncomingCall(person, hangupIntent, answerIntent)
            } else if (isActive) {
                // ONLY use CallStyle.forOngoingCall when call is truly ACTIVE
                // This prevents Android 16 from showing the status bar timer/hangup chip too early.
                val finalStartTime = if (startTime > 0L) startTime else System.currentTimeMillis()
                builder.setWhen(finalStartTime)
                builder.setUsesChronometer(true)
                builder.setShowWhen(true)
                builder.style = Notification.CallStyle.forOngoingCall(person, hangupIntent)
            } else if (isConnecting) {
                // Connecting/Dialing phase uses Standard Notification to remain sticky 
                // but avoids triggering the system "Active Call" chip.
                builder.setShowWhen(false)
                builder.setUsesChronometer(false)
                
                val action = Notification.Action.Builder(
                    AndroidIcon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Hangup", hangupIntent).build()
                builder.addAction(action)
            } else {
                // Missed - Use standard notification
                builder.setShowWhen(false)
                builder.setUsesChronometer(false)
                builder.setOngoing(false) // Missed call logs should be removable
                
                val action = Notification.Action.Builder(
                    AndroidIcon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dismiss", hangupIntent).build()
                builder.addAction(action)
            }
            
            builder.build()
        } else {
            val hangupIntent = PendingIntent.getBroadcast(
                this, 1, 
                Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_HANGUP }, 
                PendingIntent.FLAG_IMMUTABLE
            )
            val answerIntent = PendingIntent.getBroadcast(
                this, 2, 
                Intent(this, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_ANSWER }, 
                PendingIntent.FLAG_IMMUTABLE
            )

            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(when {
                    isMissed -> "Incoming call..."
                    isDialing -> "Calling..."
                    else -> "Active Call"
                })
                .setContentText(phoneNumber)
                .setContentIntent(pendingIntent)
                // Use PRIORITY_HIGH even for simulated to avoid MIUI hiding it
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(!isMissed)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, if (isMissed) "Dismiss" else "Hangup", hangupIntent)
                .apply {
                    if (isIncoming && !isMissed) addAction(android.R.drawable.ic_menu_call, "Answer", answerIntent)
                    
                    if (!isSimulated && canUseFullScreenIntent()) {
                        setFullScreenIntent(pendingIntent, true)
                    }
                }
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("NewApi")
    private fun canUseFullScreenIntent(): Boolean {
        // Use a more resilient check to satisfy the toolchain analyzer
        val version = Build.VERSION.SDK_INT
        return if (version >= 34) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.canUseFullScreenIntent()
        } else {
            true
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val activeChannel = NotificationChannel(CHANNEL_ID, "Simulated Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for simulated calls"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(activeChannel)

            val silentChannel = NotificationChannel(CHANNEL_ID_SILENT, "Simulated Missed Calls (Silent)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shade-only notifications for missed calls"
                setShowBadge(false)
            }
            manager.createNotificationChannel(silentChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        // Explicitly cancel the notification to ensure it disappears instantly
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "call_channel"
        private const val CHANNEL_ID_SILENT = "call_channel_silent"
        private const val NOTIFICATION_ID = 101
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_IS_MISSED = "extra_is_missed"
        const val EXTRA_IS_DIALING = "extra_is_dialing"
        const val EXTRA_IS_SIMULATED = "extra_is_simulated"
        const val EXTRA_START_TIME = "extra_start_time"
    }
}

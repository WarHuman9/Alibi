package com.example.alibi.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.example.alibi.MainActivity
import com.example.alibi.receiver.CallActionReceiver
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.drawable.Icon as AndroidIcon

class CallNotificationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioHeartbeatManager: AudioHeartbeatManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var delayedStopJob: kotlinx.coroutines.Job? = null

    private data class NotificationState(
        val id: String,
        val phoneNumber: String,
        val name: String,
        val isIncoming: Boolean,
        val isMissed: Boolean,
        val isDialing: Boolean,
        val isSimulated: Boolean,
        val startTime: Long
    )
    private var lastNotificationState: NotificationState? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onCreate: Service created")
        audioHeartbeatManager = AudioHeartbeatManager.getInstance(this)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TelecomConstants.WAKE_LOCK_TAG).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max safety
        }
        createNotificationChannels()
        observeCallState()
    }

    private fun observeCallState() {
        serviceScope.launch {
            CallStateManager.state.collect { state ->
                val calls = state.activeCalls
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: Active calls update received. Count=${calls.size}")
                if (calls.isEmpty()) {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: No active calls. Scheduling delayed stop.")
                    startDelayedStopCheck()
                    return@collect
                }
                
                cancelDelayedStop()
                
                val anySimulated = calls.values.any { it.isSimulated }
                if (!anySimulated) {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: No simulated calls. Stopping heartbeat.")
                    audioHeartbeatManager.stop()
                }

                val callInfo = calls[state.currentCallId]
                    ?: calls.values.find { it.state == android.telecom.Call.STATE_ACTIVE }
                    ?: calls.values.find { it.state == android.telecom.Call.STATE_RINGING }
                    ?: calls.values.find { it.state == android.telecom.Call.STATE_DIALING || it.state == android.telecom.Call.STATE_CONNECTING }
                    ?: calls.values.lastOrNull()
                
                callInfo?.let {
                    val newState = NotificationState(
                        id = it.id,
                        phoneNumber = it.number,
                        name = it.name,
                        isIncoming = it.phase == com.example.alibi.telecom.SimulationPhase.RINGING,
                        isMissed = it.type == android.provider.CallLog.Calls.MISSED_TYPE,
                        isDialing = it.phase == com.example.alibi.telecom.SimulationPhase.DIALING,
                        isSimulated = it.isSimulated,
                        startTime = it.answerTime
                    )

                    if (newState == lastNotificationState) {
                        return@let
                    }
                    lastNotificationState = newState

                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: Updating notification for call ${it.id} (name=${it.name})")
                    showNotification(
                        phoneNumber = it.number,
                        name = it.name,
                        isIncoming = newState.isIncoming,
                        isMissed = newState.isMissed,
                        isDialing = newState.isDialing,
                        isSimulated = newState.isSimulated,
                        startTime = newState.startTime,
                        callId = it.id
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: Intent received. Action=${intent?.action}")
        
        // Log all extras for debugging ID and state issues
        intent?.extras?.let { extras ->
            Log.d(TelecomConstants.NOTIFICATION_TAG, "--- onStartCommand Extras Start ---")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                Log.d(TelecomConstants.NOTIFICATION_TAG, "  $key = $value (${value?.javaClass?.simpleName})")
            }
            Log.d(TelecomConstants.NOTIFICATION_TAG, "--- onStartCommand Extras End ---")
        }

        if (intent?.action == TelecomConstants.ACTION_STOP_SERVICE) {
            Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: STOP_SERVICE action received. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Task 16 Hardening: Cancel any pending stop immediately when a start command arrives
        cancelDelayedStop()

        // Task 16: Imperative Notification - Show directly from intent extras
        intent?.let {
            // Check both standard and Alibi-specific ID keys
            val callId = it.getStringExtra(TelecomConstants.EXTRA_CALL_ID) 
                ?: it.getStringExtra(TelecomConstants.EXTRA_ALIBI_CALL_ID)
            
            val phoneNumber = it.getStringExtra(TelecomConstants.EXTRA_PHONE_NUMBER) ?: "Unknown"
            val name = it.getStringExtra(TelecomConstants.EXTRA_NAME) ?: phoneNumber
            val isIncoming = it.getBooleanExtra(TelecomConstants.EXTRA_IS_INCOMING, false)
            val isDialing = it.getBooleanExtra(TelecomConstants.EXTRA_IS_DIALING, false)
            val isSimulated = it.getBooleanExtra(TelecomConstants.EXTRA_IS_SIMULATED, false)
            val startTime = it.getLongExtra(TelecomConstants.EXTRA_START_TIME, 0L)
            
            // Task 18: Deduplication in onStartCommand to prevent redundant builds from multiple intents
            val newState = NotificationState(
                id = callId ?: "unknown",
                phoneNumber = phoneNumber,
                name = name,
                isIncoming = isIncoming,
                isMissed = false,
                isDialing = isDialing,
                isSimulated = isSimulated,
                startTime = startTime
            )
            if (newState == lastNotificationState) {
                return START_STICKY
            }
            lastNotificationState = newState

            // Active Verification: Check if call still exists in manager
            val info = CallStateManager.activeCalls.value[callId]
            if (callId != null && (info == null || info.state == android.telecom.Call.STATE_DISCONNECTED)) {
                Log.w(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: Call $callId no longer active or DISCONNECTED. Ignoring.")
                if (CallStateManager.activeCalls.value.isEmpty()) {
                    Log.i(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: No other active calls. Stopping service.")
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: Imperative notification for $name (simulated=$isSimulated, callId=$callId)")
            showNotification(
                phoneNumber = phoneNumber,
                name = name,
                isIncoming = isIncoming,
                isMissed = false,
                isDialing = isDialing,
                isSimulated = isSimulated,
                startTime = startTime,
                callId = callId
            )
        }
        return START_STICKY
    }


    @SuppressLint("InsecureFullscreenIntent", "FullScreenIntentPolicy")
    private fun showNotification(phoneNumber: String, name: String, isIncoming: Boolean, isMissed: Boolean, isDialing: Boolean, isSimulated: Boolean, startTime: Long = 0L, callId: String? = null) {
        val requestTime = System.currentTimeMillis()
        
        // Build notification on background thread to avoid main thread jank ("Davey!")
        serviceScope.launch(Dispatchers.Default) {
            val startTimeBuild = System.currentTimeMillis()
            
            // State Check: Ensure call isn't DISCONNECTED before startForeground
            if (callId != null) {
                val state = CallStateManager.activeCalls.value[callId]?.state ?: android.telecom.Call.STATE_DISCONNECTED
                if (state == android.telecom.Call.STATE_DISCONNECTED || state == android.telecom.Call.STATE_DISCONNECTING) {
                    Log.w("Alibi_Notification", "[$startTimeBuild] showNotification: Call $callId is DISCONNECTED. Aborting foreground start.")
                    return@launch
                }
            }

            // Task 16: If we are showing a notification, we definitely shouldn't be stopping
            cancelDelayedStop()
            
            val intent = Intent(this@CallNotificationService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this@CallNotificationService, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // All simulated calls use the silent channel to stay in the shade without pushing
            val channelId = if (isSimulated) CHANNEL_ID_SILENT else CHANNEL_ID

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val person = Person.Builder()
                    .setName(name)
                    .setImportant(true)
                    .build()

                val hangupIntent = PendingIntent.getBroadcast(
                    this@CallNotificationService, 1, 
                    Intent(this@CallNotificationService, CallActionReceiver::class.java).apply { action = TelecomConstants.ACTION_HANGUP }, 
                    PendingIntent.FLAG_IMMUTABLE
                )
                val answerIntent = PendingIntent.getBroadcast(
                    this@CallNotificationService, 2, 
                    Intent(this@CallNotificationService, CallActionReceiver::class.java).apply { action = TelecomConstants.ACTION_ANSWER }, 
                    PendingIntent.FLAG_IMMUTABLE
                )

                val builder = Notification.Builder(this@CallNotificationService, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle(when {
                        isMissed -> "Missed call"
                        isDialing -> "Calling..."
                        isIncoming -> "Incoming call..."
                        else -> "Active call"
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
                        AndroidIcon.createWithResource(this@CallNotificationService, android.R.drawable.ic_menu_close_clear_cancel),
                        "Hangup", hangupIntent).build()
                    builder.addAction(action)
                } else {
                    // Missed - Use standard notification
                    builder.setShowWhen(false)
                    builder.setUsesChronometer(false)
                    builder.setOngoing(false) // Missed call logs should be removable
                    
                    val action = Notification.Action.Builder(
                        AndroidIcon.createWithResource(this@CallNotificationService, android.R.drawable.ic_menu_close_clear_cancel),
                        "Dismiss", hangupIntent).build()
                    builder.addAction(action)
                }
                
                builder.build()
            } else {
                val hangupIntent = PendingIntent.getBroadcast(
                    this@CallNotificationService, 1, 
                    Intent(this@CallNotificationService, CallActionReceiver::class.java).apply { action = TelecomConstants.ACTION_HANGUP }, 
                    PendingIntent.FLAG_IMMUTABLE
                )
                val answerIntent = PendingIntent.getBroadcast(
                    this@CallNotificationService, 2, 
                    Intent(this@CallNotificationService, CallActionReceiver::class.java).apply { action = TelecomConstants.ACTION_ANSWER }, 
                    PendingIntent.FLAG_IMMUTABLE
                )

                NotificationCompat.Builder(this@CallNotificationService, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle(when {
                        isMissed -> "Missed call"
                        isDialing -> "Calling..."
                        isIncoming -> "Incoming call..."
                        else -> "Active call"
                    })
                    .setContentText(phoneNumber)
                    .setContentIntent(pendingIntent)
                    .setWhen(if (startTime > 0L) startTime else System.currentTimeMillis())
                    .setUsesChronometer(!isMissed && !isDialing)
                    .setShowWhen(!isMissed && !isDialing)
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

            withContext(Dispatchers.Main) {
                val startTimeForeground = System.currentTimeMillis()
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$startTimeForeground] showNotification: Build took ${startTimeForeground - startTimeBuild}ms. Starting foreground.")
                
                // Task 17 Abort Guard: Check if call still exists before calling startForeground
                if (callId != null && !CallStateManager.activeCalls.value.containsKey(callId)) {
                    Log.w(TelecomConstants.NOTIFICATION_TAG, "Call $callId disappeared during build. Aborting foreground start.")
                    return@withContext
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] showNotification: Foreground started in ${System.currentTimeMillis() - startTimeForeground}ms")
            }
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
            
            val activeChannel = NotificationChannel(CHANNEL_ID, "Active Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for active calls"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(activeChannel)

            val silentChannel = NotificationChannel(CHANNEL_ID_SILENT, "Simulated Calls (Standard)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Standard notifications for simulated calls"
                setSound(null, null)
                setShowBadge(false)
            }
            manager.createNotificationChannel(silentChannel)
        }
    }

    private fun startDelayedStopCheck() {
        cancelDelayedStop()
        delayedStopJob = serviceScope.launch {
            kotlinx.coroutines.delay(5000) // 5 seconds grace period
            if (CallStateManager.activeCalls.value.isEmpty()) {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "Delayed stop check: Still no calls. Stopping service.")
                stopSelf()
            }
        }
    }

    private fun cancelDelayedStop() {
        delayedStopJob?.cancel()
        delayedStopJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        audioHeartbeatManager.stop()
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
    }
}

package com.example.alibi.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.content.pm.ServiceInfo
import com.example.alibi.MainActivity
import com.example.alibi.receiver.CallActionReceiver
import com.example.alibi.service.factory.CallNotificationFactory
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CallNotificationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioHeartbeatManager: AudioHeartbeatManager
    private lateinit var notificationFactory: CallNotificationFactory
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var delayedStopJob: Job? = null
    private val notificationMutex = Mutex()

    private val activeNotificationIds = mutableMapOf<String, Int>()
    private val activeNotifications = mutableMapOf<String, Notification>()
    private val lastNotificationStates = mutableMapOf<String, NotificationState>()
    private var foregroundCallId: String? = null
    private val debounceJobs = mutableMapOf<String, Job>()
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onCreate: Service created")
        audioHeartbeatManager = AudioHeartbeatManager.getInstance(this)
        notificationFactory = CallNotificationFactory(this)
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
                    // Task 17: Cancel all lingering notifications if any
                    activeNotificationIds.keys.toList().forEach { cancelNotification(it) }
                    return@collect
                }
                
                cancelDelayedStop()
                
                val anySimulated = calls.values.any { it.isSimulated }
                if (!anySimulated) {
                    audioHeartbeatManager.stop()
                }

                // Task 17: Support Multi-Call updates near-instantly
                calls.forEach { (id, it) ->
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

                    if (newState == lastNotificationStates[id]) {
                        return@forEach
                    }
                    
                    val oldState = lastNotificationStates[id]
                    val isSignificantChange = oldState != null && (
                        oldState.isIncoming != newState.isIncoming ||
                        oldState.isDialing != newState.isDialing
                    )

                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: State changed for $id.")
                    lastNotificationStates[id] = newState

                    showNotification(
                        phoneNumber = it.number,
                        name = it.name,
                        isIncoming = newState.isIncoming,
                        isMissed = newState.isMissed,
                        isDialing = newState.isDialing,
                        isSimulated = newState.isSimulated,
                        startTime = newState.startTime,
                        callId = it.id,
                        instant = isSignificantChange
                    )
                }

                // Task 17: Clear stale notifications for calls that are gone
                val staleIds = activeNotificationIds.keys.filter { !calls.containsKey(it) }
                staleIds.forEach { cancelNotification(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: Intent received. Action=${intent?.action}")
        
        if (intent?.action == TelecomConstants.ACTION_STOP_SERVICE) {
            Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: STOP_SERVICE action received. Stopping.")
            // Clear everything before stopping
            activeNotificationIds.keys.toList().forEach { cancelNotification(it) }
            stopSelf()
            return START_NOT_STICKY
        }

        cancelDelayedStop()

        intent?.let {
            val callId = it.getStringExtra(TelecomConstants.EXTRA_CALL_ID) 
                ?: it.getStringExtra(TelecomConstants.EXTRA_ALIBI_CALL_ID)
            
            val phoneNumber = it.getStringExtra(TelecomConstants.EXTRA_PHONE_NUMBER) ?: "Unknown"
            val name = it.getStringExtra(TelecomConstants.EXTRA_NAME) ?: phoneNumber
            val isIncoming = it.getBooleanExtra(TelecomConstants.EXTRA_IS_INCOMING, false)
            val isDialing = it.getBooleanExtra(TelecomConstants.EXTRA_IS_DIALING, false)
            val isMissed = it.getBooleanExtra(TelecomConstants.EXTRA_IS_MISSED, false)
            val isSimulated = it.getBooleanExtra(TelecomConstants.EXTRA_IS_SIMULATED, false)
            val startTime = it.getLongExtra(TelecomConstants.EXTRA_START_TIME, 0L)
            
            if (callId != null) {
                val newState = NotificationState(
                    id = callId,
                    phoneNumber = phoneNumber,
                    name = name,
                    isIncoming = isIncoming,
                    isMissed = isMissed,
                    isDialing = isDialing,
                    isSimulated = isSimulated,
                    startTime = startTime
                )
                if (newState == lastNotificationStates[callId]) {
                    return START_STICKY
                }
                lastNotificationStates[callId] = newState

                val info = CallStateManager.activeCalls.value[callId]
                if (info == null || info.state == android.telecom.Call.STATE_DISCONNECTED) {
                    Log.w(TelecomConstants.NOTIFICATION_TAG, "onStartCommand: Call $callId no longer active. Ignoring.")
                    return START_NOT_STICKY
                }

                showNotification(
                    phoneNumber = phoneNumber,
                    name = name,
                    isIncoming = isIncoming,
                    isMissed = isMissed,
                    isDialing = isDialing,
                    isSimulated = isSimulated,
                    startTime = startTime,
                    callId = callId
                )
            }
        }
        return START_STICKY
    }

    private fun showNotification(
        phoneNumber: String, 
        name: String, 
        isIncoming: Boolean, 
        isMissed: Boolean, 
        isDialing: Boolean, 
        isSimulated: Boolean, 
        startTime: Long = 0L, 
        callId: String? = null,
        instant: Boolean = false
    ) {
        if (callId == null) return

        // Task 17: 150ms debounce for the FIRST notification of a simulated call
        val isFirst = !activeNotificationIds.containsKey(callId)
        if (isSimulated && isFirst && !instant) {
            debounceJobs[callId]?.cancel()
            debounceJobs[callId] = serviceScope.launch {
                delay(150L)
                performShowNotification(phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, callId)
            }
        } else {
            debounceJobs[callId]?.cancel()
            serviceScope.launch(Dispatchers.Default) {
                performShowNotification(phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, callId)
            }
        }
    }

    @SuppressLint("InsecureFullscreenIntent", "FullScreenIntentPolicy")
    private suspend fun performShowNotification(
        phoneNumber: String, 
        name: String, 
        isIncoming: Boolean, 
        isMissed: Boolean, 
        isDialing: Boolean, 
        isSimulated: Boolean, 
        startTime: Long = 0L, 
        callId: String
    ) {
        notificationMutex.withLock {
            val startTimeBuild = System.currentTimeMillis()
            
            // State Check: Ensure call isn't DISCONNECTED before build
            val state = CallStateManager.activeCalls.value[callId]?.state ?: android.telecom.Call.STATE_DISCONNECTED
            if (state == android.telecom.Call.STATE_DISCONNECTED || state == android.telecom.Call.STATE_DISCONNECTING) {
                return@withLock
            }

            withContext(Dispatchers.Main) {
                cancelDelayedStop()
            }
            
            val channelId = if (isSimulated) CHANNEL_ID_SILENT else CHANNEL_ID
            val notification = notificationFactory.createNotification(
                phoneNumber = phoneNumber,
                name = name,
                isIncoming = isIncoming,
                isMissed = isMissed,
                isDialing = isDialing,
                isSimulated = isSimulated,
                startTime = startTime,
                channelId = channelId,
                callId = callId
            )

            withContext(Dispatchers.Main) {
                // Ensure call still exists before posting
                if (!CallStateManager.activeCalls.value.containsKey(callId)) return@withContext

                val id = activeNotificationIds.getOrPut(callId) { getNotificationId(callId) }
                activeNotifications[callId] = notification
                
                Log.d(TelecomConstants.NOTIFICATION_TAG, "Posting notification for $callId (id=$id). ForegroundOwnedBy=$foregroundCallId")
                
                // Task 17: Maintain foreground status as long as at least one call is active
                if (foregroundCallId == null || foregroundCallId == callId) {
                    updateForeground(callId, notification)
                } else {
                    notificationManager.notify(id, notification)
                }
            }
        }
    }

    private fun updateForeground(callId: String, notification: Notification) {
        val id = activeNotificationIds.getOrPut(callId) { getNotificationId(callId) }
        foregroundCallId = callId
        Log.d(TelecomConstants.NOTIFICATION_TAG, "startForeground for call $callId with id $id")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(id, notification)
        }
    }

    private fun cancelNotification(callId: String) {
        debounceJobs.remove(callId)?.cancel()
        val id = activeNotificationIds.remove(callId)
        activeNotifications.remove(callId)
        lastNotificationStates.remove(callId)
        
        if (id != null) {
            notificationManager.cancel(id)
            Log.d(TelecomConstants.NOTIFICATION_TAG, "Cancelled notification for $callId (id=$id)")
        }

        if (foregroundCallId == callId) {
            foregroundCallId = null
            // Promote next active call to foreground
            val nextCallId = activeNotificationIds.keys.firstOrNull()
            if (nextCallId != null) {
                val nextNotification = activeNotifications[nextCallId]
                if (nextNotification != null) {
                    updateForeground(nextCallId, nextNotification)
                }
            } else {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "No more calls. Stopping foreground.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                startDelayedStopCheck()
            }
        }
    }

    private fun getNotificationId(callId: String): Int {
        val hash = callId.hashCode() and 0x7FFFFFFF
        return if (hash == 0) NOTIFICATION_ID else hash
    }

    @SuppressLint("NewApi")
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
            delay(5000L) // 5 seconds grace period
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
        // Ensure ALL notifications are cancelled on destroy
        activeNotificationIds.values.forEach { notificationManager.cancel(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "call_channel"
        private const val CHANNEL_ID_SILENT = "call_channel_silent"
        private const val NOTIFICATION_ID = 101
    }
}

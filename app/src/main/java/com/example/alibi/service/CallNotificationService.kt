package com.example.alibi.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.content.pm.ServiceInfo
import com.example.alibi.receiver.CallActionReceiver
import com.example.alibi.service.factory.CallNotificationFactory
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
            CallStateManager.state
                .map { it.activeCalls }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { calls ->
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: Active calls update received. Count=${calls.size}")
                    
                    if (calls.isEmpty()) {
                        val now = System.currentTimeMillis()
                        Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] observeCallState: No active calls. Starting total cleanup.")
                        withContext(Dispatchers.Main) {
                            startDelayedStopCheck()
                        }
                        
                        // Task 17: Cancel all lingering notifications if any
                        val idsToCancel = notificationMutex.withLock { activeNotificationIds.keys.toList() }
                        Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] observeCallState: Cancelling ${idsToCancel.size} notifications")
                        idsToCancel.forEach { cancelNotification(it) }
                        
                        // Master Clear: Ensure no stale state prevents future notifications
                        lastNotificationStates.clear()
                        debounceJobs.values.forEach { it.cancel() }
                        debounceJobs.clear()
                        
                        return@collect
                    }
                    
                    withContext(Dispatchers.Main) {
                        cancelDelayedStop()
                    }
                    
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
                            isIncoming = if (it.isSimulated) it.phase == com.example.alibi.telecom.SimulationPhase.RINGING else it.state == android.telecom.Call.STATE_RINGING,
                            isMissed = it.type == android.provider.CallLog.Calls.MISSED_TYPE,
                            isDialing = if (it.isSimulated) it.phase == com.example.alibi.telecom.SimulationPhase.DIALING else (it.state == android.telecom.Call.STATE_DIALING || it.state == android.telecom.Call.STATE_CONNECTING),
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
                    val staleIds = notificationMutex.withLock { 
                        activeNotificationIds.keys.filter { !calls.containsKey(it) }
                    }
                    staleIds.forEach { 
                        Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: Call $it is stale. Cancelling.")
                        cancelNotification(it) 
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: Intent received. Action=${intent?.action}")
        
        if (intent?.action == TelecomConstants.ACTION_STOP_SERVICE) {
            Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] onStartCommand: STOP_SERVICE action received. Stopping.")
            // Clear everything before stopping
            serviceScope.launch {
                val idsToCancel = notificationMutex.withLock { activeNotificationIds.keys.toList() }
                idsToCancel.forEach { cancelNotification(it) }
                stopSelf()
            }
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
                val isExplicitlyDisconnected = info != null && (
                    info.state == android.telecom.Call.STATE_DISCONNECTED || 
                    info.state == android.telecom.Call.STATE_DISCONNECTING
                )

                if (isExplicitlyDisconnected) {
                    Log.w(TelecomConstants.NOTIFICATION_TAG, "onStartCommand: Call $callId explicitly disconnected in state map. Aborting.")
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
            
            // State Check: Ensure call isn't DISCONNECTED before build.
            // Fast Path: Trust the Intent data unless the State Map explicitly says the call is finished.
            val info = CallStateManager.activeCalls.value[callId]
            if (info != null && (info.state == android.telecom.Call.STATE_DISCONNECTED || info.state == android.telecom.Call.STATE_DISCONNECTING)) {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "performShowNotification: Call $callId explicitly disconnected. Aborting post.")
                return@withLock
            }

            withContext(Dispatchers.Main) {
                cancelDelayedStop()
            }
            
            // Task 17 & Android 14 Stability: 
            // Determine if this call should be the foreground primary.
            // New incoming/dialing calls always take priority.
            val shouldBePrimary = when {
                foregroundCallId == null -> true
                foregroundCallId == callId -> true
                isIncoming -> true
                isDialing -> true
                else -> false
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
                callId = callId,
                isPrimary = shouldBePrimary
            )

            withContext(Dispatchers.Main) {
                // Ensure call still exists before posting
                if (!CallStateManager.activeCalls.value.containsKey(callId)) return@withContext

                val id = activeNotificationIds.getOrPut(callId) { getNotificationId(callId) }
                activeNotifications[callId] = notification
                
                Log.d(TelecomConstants.NOTIFICATION_TAG, "Posting notification for $callId (id=$id). isPrimary=$shouldBePrimary")
                
                if (shouldBePrimary) {
                    updateForegroundInternal(callId, notification)
                } else {
                    val now = System.currentTimeMillis()
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] Posting secondary notification for $callId")
                    notificationManager.notify(id, notification)
                }
            }
        }
    }

    private fun cancelNotification(callId: String) {
        serviceScope.launch {
            performCancelNotification(callId)
        }
    }

    private suspend fun performCancelNotification(callId: String) {
        notificationMutex.withLock {
            val now = System.currentTimeMillis()
            debounceJobs.remove(callId)?.cancel()
            val id = activeNotificationIds.remove(callId)
            activeNotifications.remove(callId)
            lastNotificationStates.remove(callId)
            
            if (id != null) {
                notificationManager.cancel(id)
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Notification for $callId removed (id=$id)")
            }

            if (foregroundCallId == callId) {
                foregroundCallId = null
                // Promote next active call to foreground
                val nextEntry = activeNotificationIds.entries.firstOrNull()
                if (nextEntry != null) {
                    val nextCallId = nextEntry.key
                    val state = lastNotificationStates[nextCallId]
                    if (state != null) {
                        Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Promoting $nextCallId to primary")
                        // Re-trigger performShowNotification directly to rebuild as primary
                        performShowNotification(
                            phoneNumber = state.phoneNumber,
                            name = state.name,
                            isIncoming = state.isIncoming,
                            isMissed = state.isMissed,
                            isDialing = state.isDialing,
                            isSimulated = state.isSimulated,
                            startTime = state.startTime,
                            callId = nextCallId
                        )
                    }
                } else {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: No more calls. stopForeground(REMOVE).")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    startDelayedStopCheck()
                }
            }
        }
    }


    private fun updateForegroundInternal(callId: String, notification: Notification) {
        val id = activeNotificationIds.getOrPut(callId) { getNotificationId(callId) }
        foregroundCallId = callId
        Log.d(TelecomConstants.NOTIFICATION_TAG, "startForeground for call $callId with id $id")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(id, notification)
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
        val now = System.currentTimeMillis()
        Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] startDelayedStopCheck: Scheduling 5s grace period.")
        delayedStopJob = serviceScope.launch {
            delay(5000L)
            val finishTime = System.currentTimeMillis()
            if (CallStateManager.activeCalls.value.isEmpty()) {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$finishTime] startDelayedStopCheck: Grace period finished. stopSelf(). Duration=${finishTime - now}ms")
                stopSelf()
            } else {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$finishTime] startDelayedStopCheck: New call detected. Aborting stop.")
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
        serviceScope.launch {
            notificationMutex.withLock {
                activeNotificationIds.values.forEach { notificationManager.cancel(it) }
                activeNotificationIds.clear()
                activeNotifications.clear()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "call_channel"
        private const val CHANNEL_ID_SILENT = "call_channel_silent"
        private const val NOTIFICATION_ID = 101
    }
}

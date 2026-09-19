package com.example.alibi.service

import android.app.*
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.provider.CallLog
import android.telecom.Call
import com.example.alibi.receiver.CallActionReceiver
import com.example.alibi.service.factory.CallNotificationFactory
import com.example.alibi.telecom.CallRepository
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulationPhase
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
    private val surgicalCallJobs = mutableMapOf<String, Job>()
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
        
        createNotificationChannels()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TelecomConstants.WAKE_LOCK_TAG).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max safety
        }

        CallStateManager.onSilenceRingtoneRequested = {
            handleRingtoneAndWakeLock(isIncomingRinging = false)
        }

        observeCallState()
    }

    private fun observeCallState() {
        serviceScope.launch {
            CallStateManager.activeCallIds
                .collect { ids ->
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: Active call IDs update received. Count=${ids.size}")
                    
                    if (ids.isEmpty()) {
                        val now = System.currentTimeMillis()
                        Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] observeCallState: No active calls. Preparing delayed stop.")
                        withContext(Dispatchers.Main) {
                            startDelayedStopCheck()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            cancelDelayedStop()
                        }
                    }

                    // 1. Cleanup jobs for calls that are gone
                    val removedIds = surgicalCallJobs.keys.filter { it !in ids }
                    removedIds.forEach { id ->
                        surgicalCallJobs.remove(id)?.cancel()
                        cancelNotification(id)
                    }

                    // 2. Start surgical collectors for new IDs
                    ids.forEach { id ->
                        if (id !in surgicalCallJobs) {
                            surgicalCallJobs[id] = serviceScope.launch {
                                CallStateManager.getCallMetadata(id).collect { meta ->
                                    if (meta == null || meta.state == Call.STATE_DISCONNECTED || meta.state == Call.STATE_DISCONNECTING) {
                                        Log.d(TelecomConstants.NOTIFICATION_TAG, "observeCallState: Call $id disconnected/disconnecting. Immediate notification cleanup.")
                                        cancelNotification(id)
                                        return@collect
                                    }
                                    
                                    val newState = NotificationState(
                                        id = meta.id,
                                        phoneNumber = meta.number,
                                        name = meta.name,
                                        isIncoming = if (meta.isSimulated) meta.phase == com.example.alibi.telecom.SimulationPhase.RINGING else meta.state == android.telecom.Call.STATE_RINGING,
                                        isMissed = meta.type == android.provider.CallLog.Calls.MISSED_TYPE,
                                        isDialing = if (meta.isSimulated) meta.phase == com.example.alibi.telecom.SimulationPhase.DIALING else (meta.state == android.telecom.Call.STATE_DIALING || meta.state == android.telecom.Call.STATE_CONNECTING),
                                        isSimulated = meta.isSimulated,
                                        startTime = meta.answerTime
                                    )

                                    if (newState == lastNotificationStates[id]) return@collect
                                    
                                    val oldState = lastNotificationStates[id]
                                    // Significant change or the FIRST notification for a real call
                                    val isSignificantChange = (oldState == null && !meta.isSimulated) || (oldState != null && (
                                        oldState.isIncoming != newState.isIncoming ||
                                        oldState.isDialing != newState.isDialing
                                    ))

                                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[${System.currentTimeMillis()}] observeCallState: Surgical state changed for $id. Significant=$isSignificantChange")
                                    // Don't update lastNotificationStates here; showNotification will do it.

                                    showNotification(
                                        phoneNumber = meta.number,
                                        name = meta.name,
                                        isIncoming = newState.isIncoming,
                                        isMissed = newState.isMissed,
                                        isDialing = newState.isDialing,
                                        isSimulated = newState.isSimulated,
                                        startTime = newState.startTime,
                                        callId = meta.id,
                                        instant = isSignificantChange
                                    )
                                }
                            }
                        }
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
            
            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            Log.d("[Alibi_FSI]", "CallNotificationService.onStartCommand: callId=$callId, isIncoming=$isIncoming, screenInteractive=${powerManager?.isInteractive}, keyguardLocked=${keyguardManager?.isKeyguardLocked}")

            if (callId != null) {
                val info = CallStateManager.activeCalls.value[callId]
                val isExplicitlyDisconnected = info != null && (
                    info.state == android.telecom.Call.STATE_DISCONNECTED || 
                    info.state == android.telecom.Call.STATE_DISCONNECTING
                )

                if (isExplicitlyDisconnected) {
                    Log.w(TelecomConstants.NOTIFICATION_TAG, "onStartCommand: Call $callId explicitly disconnected in state map. Aborting.")
                    return START_NOT_STICKY
                }

                // Synchronous FGS Deadline Compliance:
                // Instantly post high-importance notification to startForeground on line 1 of service execution.
                if (foregroundCallId == null) {
                    val isIncomingRinging = isIncoming && !isMissed && !isDialing
                    val channelId = if (isIncomingRinging) CHANNEL_ID_INCOMING else CHANNEL_ID
                    val initialNotification = notificationFactory.createNotification(
                        phoneNumber = phoneNumber,
                        name = name,
                        isIncoming = isIncoming,
                        isMissed = isMissed,
                        isDialing = isDialing,
                        isSimulated = isSimulated,
                        startTime = startTime,
                        channelId = channelId,
                        callId = callId,
                        isPrimary = true
                    )
                    Log.d("[Alibi_FSI]", "onStartCommand: Executing synchronous startForeground for $callId on channel $channelId")
                    updateForegroundInternal(callId, initialNotification)
                }

                // Force instant update in onStartCommand to synchronize notification state
                showNotification(
                    phoneNumber = phoneNumber,
                    name = name,
                    isIncoming = isIncoming,
                    isMissed = isMissed,
                    isDialing = isDialing,
                    isSimulated = isSimulated,
                    startTime = startTime,
                    callId = callId,
                    instant = true
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

        // Single point of truth for deduplication: Check if state is truly new
        val isNewState = lastNotificationStates[callId] != newState
        if (!isNewState && !instant) {
            Log.d(TelecomConstants.NOTIFICATION_TAG, "showNotification: Skipping duplicate state for $callId")
            return
        }
        Log.d(TelecomConstants.NOTIFICATION_TAG, "showNotification: Processing update for $callId. Instant=$instant")

        debounceJobs[callId]?.cancel()
        // Use Main thread immediately for responsive foreground updates
        serviceScope.launch(Dispatchers.Main) {
            performShowNotification(newState, phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, callId)
        }
    }

    @SuppressLint("InsecureFullscreenIntent", "FullScreenIntentPolicy")
    private suspend fun performShowNotification(
        newState: NotificationState,
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
            
            withContext(Dispatchers.Main) {
                cancelDelayedStop()
            }
            
            // Task 17 & Android 14 Stability: 
            // Determine if this call should be the foreground primary.
            // New incoming/dialing calls always take priority.
            val shouldBePrimary = when {
                foregroundCallId == null -> true // Take over from bootstrap or if idle
                foregroundCallId == callId -> true
                isIncoming -> true
                isDialing -> true
                else -> false
            }

            val isIncomingRinging = isIncoming && !isMissed && !isDialing
            val channelId = if (isIncomingRinging) CHANNEL_ID_INCOMING else CHANNEL_ID
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

            // Ensure call still exists in CallRepository or CallStateManager before posting
            val existsInRepo = CallRepository.sessions.value.containsKey(callId)
            val existsInManager = CallStateManager.activeCalls.value.containsKey(callId)
            if (!existsInRepo && !existsInManager) {
                Log.w(TelecomConstants.NOTIFICATION_TAG, "performShowNotification: Call $callId no longer exists in repository or state manager. Aborting.")
                return@withLock
            }

            val id = if (shouldBePrimary) NOTIFICATION_ID else getNotificationId(callId)
            activeNotificationIds[callId] = id
            activeNotifications[callId] = notification
            lastNotificationStates[callId] = newState
            
            Log.d("[Alibi_FSI]", "CallNotificationService: performShowNotification posting notification for $callId (id=$id, isPrimary=$shouldBePrimary, channel=$channelId)")
            
            if (shouldBePrimary) {
                updateForegroundInternal(callId, notification)
            } else {
                notificationManager.notify(id, notification)
            }

            // Trigger Ringtone Audio & Screen Wake Lock on Main Thread
            handleRingtoneAndWakeLock(isIncomingRinging)
        }
    }

    private fun cancelNotification(callId: String) {
        serviceScope.launch {
            performCancelNotification(callId)
        }
    }

    private fun getBestCallToPromote(excludingId: String): NotificationState? {
        val states = lastNotificationStates.values.filter { it.id != excludingId }
        
        // Priority 1: Ringing / Incoming
        states.find { it.isIncoming }?.let { return it }
        
        // Priority 2: Active ongoing call
        states.find { !it.isIncoming && !it.isDialing && !it.isMissed }?.let { return it }
        
        // Priority 3: Dialing / Connecting call
        states.find { it.isDialing }?.let { return it }
        
        // Priority 4: Any remaining notification state
        states.firstOrNull()?.let { return it }

        // Fallback: Inspect CallRepository sessions directly
        val sessions = CallRepository.sessions.value.values.filter { it.id != excludingId }
        val sessionMeta = sessions.firstOrNull()?.metadata?.value
        if (sessionMeta != null) {
            return NotificationState(
                id = sessionMeta.id,
                phoneNumber = sessionMeta.number,
                name = sessionMeta.name,
                isIncoming = if (sessionMeta.isSimulated) sessionMeta.phase == SimulationPhase.RINGING else sessionMeta.state == Call.STATE_RINGING,
                isMissed = sessionMeta.type == CallLog.Calls.MISSED_TYPE,
                isDialing = if (sessionMeta.isSimulated) sessionMeta.phase == SimulationPhase.DIALING else (sessionMeta.state == Call.STATE_DIALING || sessionMeta.state == Call.STATE_CONNECTING),
                isSimulated = sessionMeta.isSimulated,
                startTime = sessionMeta.answerTime
            )
        }

        return null
    }

    private suspend fun performCancelNotification(callId: String) {
        var stateToPromote: NotificationState? = null
        
        notificationMutex.withLock {
            val now = System.currentTimeMillis()
            val otherSessions = CallRepository.sessions.value.keys.filter { it != callId }
            val otherActiveCalls = CallStateManager.activeCalls.value.keys.filter { it != callId }

            // Safeguard #1: Idempotency check to safely handle duplicate/late cancellations
            val isAlreadyCleared = !activeNotificationIds.containsKey(callId)
                && !activeNotifications.containsKey(callId)
                && !lastNotificationStates.containsKey(callId)
                && foregroundCallId != callId

            if (isAlreadyCleared) {
                val hasOtherCalls = activeNotificationIds.isNotEmpty() 
                    || otherSessions.isNotEmpty()
                    || otherActiveCalls.isNotEmpty()

                if (!hasOtherCalls) {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Call $callId already cleared and no calls remain. Idempotent teardown check.")
                    notificationManager.cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    startDelayedStopCheck()
                } else {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Call $callId already cleared. Idempotent no-op.")
                }
                return@withLock
            }

            debounceJobs.remove(callId)?.cancel()
            val id = activeNotificationIds.remove(callId)
            activeNotifications.remove(callId)
            val lastState = lastNotificationStates.remove(callId)

            // Stop ringtone audio and screen wake lock if this was the incoming call
            if (lastState != null && lastState.isIncoming) {
                handleRingtoneAndWakeLock(false)
            }

            // Post Missed Call Notification for unanswered incoming calls
            if (lastState != null && lastState.isIncoming && !lastState.isMissed && lastState.startTime == 0L) {
                val missedNotification = notificationFactory.createMissedCallNotification(
                    phoneNumber = lastState.phoneNumber,
                    name = lastState.name,
                    callId = callId,
                    channelId = CHANNEL_ID_MISSED
                )
                val missedId = getNotificationId("MISSED_$callId")
                notificationManager.notify(missedId, missedNotification)
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Posted missed call notification for $callId (id=$missedId)")
            }
            
            // If it was a secondary notification, cancel its specific ID
            if (id != null && id != NOTIFICATION_ID) {
                notificationManager.cancel(id)
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Secondary notification for $callId removed (id=$id)")
            }

            // Orphan Guard: Check if current foregroundCallId corresponds to a live session or active notification
            val isForegroundIdLive = foregroundCallId == null || foregroundCallId == callId || (
                CallRepository.sessions.value.containsKey(foregroundCallId) ||
                activeNotificationIds.containsKey(foregroundCallId)
            )
            if (!isForegroundIdLive && foregroundCallId != null) {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: foregroundCallId ($foregroundCallId) is stale/orphaned. Resetting to null.")
                foregroundCallId = null
            }

            val hasOtherCalls = activeNotificationIds.isNotEmpty() 
                || otherSessions.isNotEmpty()
                || otherActiveCalls.isNotEmpty()

            if (foregroundCallId == callId || foregroundCallId == null) {
                foregroundCallId = null
                
                // Promote highest-priority active call to Primary slot (ID 101)
                val bestCandidate = getBestCallToPromote(callId)
                if (bestCandidate != null) {
                    stateToPromote = bestCandidate
                } else if (!hasOtherCalls) {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: No active calls remaining. Cancelling ID $NOTIFICATION_ID and stopForeground(REMOVE).")
                    notificationManager.cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    startDelayedStopCheck()
                } else {
                    Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Primary removed but other calls exist. Keeping foreground active.")
                }
            } else if (!hasOtherCalls) {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "[$now] performCancelNotification: Secondary call removed and no calls remain. Cancelling ID $NOTIFICATION_ID and stopForeground(REMOVE).")
                notificationManager.cancel(NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                startDelayedStopCheck()
            }
        }

        // Deadlock Fix & Fail-Safe Fallback: Call performShowNotification OUTSIDE the mutex with try-catch
        stateToPromote?.let { state ->
            Log.d(TelecomConstants.NOTIFICATION_TAG, "performCancelNotification: Executing promotion for ${state.id}")
            try {
                performShowNotification(
                    newState = state,
                    phoneNumber = state.phoneNumber,
                    name = state.name,
                    isIncoming = state.isIncoming,
                    isMissed = state.isMissed,
                    isDialing = state.isDialing,
                    isSimulated = state.isSimulated,
                    startTime = state.startTime,
                    callId = state.id
                )
            } catch (e: Exception) {
                Log.e(TelecomConstants.NOTIFICATION_TAG, "performCancelNotification: Promotion failed for ${state.id}. Fallback teardown.", e)
                notificationMutex.withLock {
                    notificationManager.cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    startDelayedStopCheck()
                }
            }
        }
    }


    private fun updateForegroundInternal(callId: String, notification: Notification) {
        foregroundCallId = callId
        Log.d(TelecomConstants.NOTIFICATION_TAG, "Updating Primary Foreground (ID $NOTIFICATION_ID) for call $callId")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TelecomConstants.NOTIFICATION_TAG, "Failed to update foreground service, falling back to NotificationManager.notify", e)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private var activeRingtone: Ringtone? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var isVolumeReceiverRegistered = false

    private val volumeKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == "android.media.VOLUME_CHANGED_ACTION" || action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TelecomConstants.NOTIFICATION_TAG, "System volume/power key press detected. Silencing ringtone.")
                handleRingtoneAndWakeLock(isIncomingRinging = false)
            }
        }
    }

    private fun registerVolumeReceiver() {
        if (!isVolumeReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction("android.media.VOLUME_CHANGED_ACTION")
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(volumeKeyReceiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(volumeKeyReceiver, filter)
                }
                isVolumeReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TelecomConstants.NOTIFICATION_TAG, "Failed to register volume key receiver", e)
            }
        }
    }

    private fun unregisterVolumeReceiver() {
        if (isVolumeReceiverRegistered) {
            try {
                unregisterReceiver(volumeKeyReceiver)
            } catch (_: Exception) {}
            isVolumeReceiverRegistered = false
        }
    }

    private fun handleRingtoneAndWakeLock(isIncomingRinging: Boolean) {
        if (isIncomingRinging) {
            registerVolumeReceiver()
        } else {
            unregisterVolumeReceiver()
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
            
            // Clean up legacy channels so Android system settings adopt the new channel rules
            try {
                manager.deleteNotificationChannel("call_channel")
                manager.deleteNotificationChannel("call_channel_silent")
                manager.deleteNotificationChannel("call_channel_incoming_v2")
            } catch (_: Exception) {}

            // Ongoing & Outgoing Calls Channel (IMPORTANCE_LOW -> Shade only, NO heads-up banner)
            val ongoingChannel = NotificationChannel(CHANNEL_ID, "Ongoing & Outgoing Calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifications for ongoing and outgoing calls"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(ongoingChannel)

            // Incoming Calls Channel (IMPORTANCE_HIGH -> Heads-up alert banner + Ringtone Sound & Vibration)
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val incomingChannel = NotificationChannel(CHANNEL_ID_INCOMING, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for incoming calls"
                setSound(ringtoneUri, audioAttributes)
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(incomingChannel)

            // Missed Calls Channel (IMPORTANCE_DEFAULT -> Notification Shade)
            val missedChannel = NotificationChannel(CHANNEL_ID_MISSED, "Missed Calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications for missed calls"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(missedChannel)
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
        unregisterVolumeReceiver()
        handleRingtoneAndWakeLock(false)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        // Synchronous cleanup for active ongoing call notifications on exit
        activeNotificationIds.values.forEach { id ->
            try { notificationManager.cancel(id) } catch (_: Exception) {}
        }
        try { notificationManager.cancel(NOTIFICATION_ID) } catch (_: Exception) {}

        activeNotificationIds.clear()
        activeNotifications.clear()
        lastNotificationStates.clear()
        
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "call_channel_ongoing_v2"
        private const val CHANNEL_ID_INCOMING = "call_channel_incoming_v3"
        private const val CHANNEL_ID_MISSED = "call_channel_missed_v1"
        private const val CHANNEL_ID_SILENT = "call_channel_ongoing_v2"
        private const val NOTIFICATION_ID = 101
    }
}

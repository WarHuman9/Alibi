package com.example.alibi.service.factory

import android.R
import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.alibi.MainActivity
import com.example.alibi.receiver.CallActionReceiver
import com.example.alibi.telecom.TelecomConstants
import com.example.alibi.ui.IncomingCallActivity

class CallNotificationFactory(private val context: Context) {

    fun createNotification(
        phoneNumber: String,
        name: String,
        isIncoming: Boolean,
        isMissed: Boolean,
        isDialing: Boolean,
        isSimulated: Boolean,
        startTime: Long,
        channelId: String,
        callId: String,
        isPrimary: Boolean = true,
    ): Notification {
        val pendingIntent = createContentIntent(callId)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildModernNotification(
                phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, channelId, pendingIntent, callId, isPrimary
            )
        } else {
            buildLegacyNotification(
                phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, channelId, pendingIntent, callId
            )
        }
    }

    fun createBootstrapNotification(channelId: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Call Service")
            .setContentText("Initializing...")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun createMissedCallNotification(phoneNumber: String, name: String, callId: String, channelId: String): Notification {
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneNumber, null)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val callBackPendingIntent = PendingIntent.getActivity(
            context,
            callId.hashCode() + TelecomConstants.REQUEST_CODE_CONTENT,
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayName = if (name.isNotBlank()) name else phoneNumber

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_menu_call)
            .setContentTitle("Missed call")
            .setContentText(displayName)
            .setContentIntent(callBackPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                R.drawable.ic_menu_call,
                "Call back",
                callBackPendingIntent
            )
            .build()
    }

    private fun createContentIntent(callId: String): PendingIntent {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TelecomConstants.EXTRA_CALL_ID, callId)
            putExtra(TelecomConstants.EXTRA_REAL_CALL, true)
        }
        val requestCode = callId.hashCode() + TelecomConstants.REQUEST_CODE_CONTENT

        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                if (Build.VERSION.SDK_INT >= 35) {
                    setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
            }.toBundle()
        } else null

        return PendingIntent.getActivity(
            context, 
            requestCode, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options
        )
    }

    private fun createActionIntent(action: String, requestCodeOffset: Int, callId: String): PendingIntent {
        val baseRequestCode = callId.hashCode()
        val finalRequestCode = baseRequestCode + requestCodeOffset
        
        return PendingIntent.getBroadcast(
            context, 
            finalRequestCode, 
            Intent(context, CallActionReceiver::class.java).apply { 
                this.action = action 
                putExtra(TelecomConstants.EXTRA_CALL_ID, callId)
            }, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun buildModernNotification(
        phoneNumber: String,
        name: String,
        isIncoming: Boolean,
        isMissed: Boolean,
        isDialing: Boolean,
        @Suppress("UNUSED_PARAMETER") isSimulated: Boolean,
        startTime: Long,
        channelId: String,
        pendingIntent: PendingIntent,
        callId: String,
        isPrimary: Boolean
    ): Notification {
        val person = Person.Builder()
            .setName(name)
            .setImportant(true)
            .build()

        val hangupIntent = createActionIntent(TelecomConstants.ACTION_HANGUP, TelecomConstants.REQUEST_CODE_HANGUP, callId)
        val answerIntent = createActionIntent(TelecomConstants.ACTION_ANSWER, TelecomConstants.REQUEST_CODE_ANSWER, callId)

        val isRinging = isIncoming && !isMissed && !isDialing
        val isActive = !isIncoming && !isMissed && !isDialing
        val isConnecting = isDialing && !isMissed

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(when {
                isMissed -> "Missed call"
                isRinging -> "Incoming call..."
                isConnecting -> "Calling..."
                else -> "Active call"
            })
            .setContentText(phoneNumber)
            .setContentIntent(pendingIntent)
            .setOngoing(!isMissed)
            .setLocalOnly(true)
            .setOnlyAlertOnce(!isRinging)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_CALL)

        // CallStyle is only allowed for the primary foreground notification on Android 14+.
        // For non-primary calls, we use a standard notification with action buttons.
        val useCallStyle = isPrimary && (isRinging || isActive)

        // Only push FullScreenIntent / Heads-up alert for Incoming Ringing Calls
        if (isPrimary && isRinging && canUseFullScreenIntent()) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        when {
            useCallStyle && isRinging -> {
                builder.style = Notification.CallStyle.forIncomingCall(person, hangupIntent, answerIntent)
            }
            useCallStyle -> { // Primary Active
                val finalStartTime = if (startTime > 0L) startTime else System.currentTimeMillis()
                builder.setWhen(finalStartTime)
                builder.setUsesChronometer(true)
                builder.setShowWhen(true)
                builder.style = Notification.CallStyle.forOngoingCall(person, hangupIntent)
            }
            isRinging -> { // Secondary ringing
                builder.addAction(Notification.Action.Builder(
                    AndroidIcon.createWithResource(context, android.R.drawable.ic_menu_call),
                    "Answer", answerIntent).build())
                builder.addAction(Notification.Action.Builder(
                    AndroidIcon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Hangup", hangupIntent).build())
            }
            isActive -> { // Secondary active/hold
                val finalStartTime = if (startTime > 0L) startTime else System.currentTimeMillis()
                builder.setWhen(finalStartTime)
                builder.setUsesChronometer(true)
                builder.setShowWhen(true)
                builder.addAction(Notification.Action.Builder(
                    AndroidIcon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Hangup", hangupIntent).build())
            }
            isConnecting -> {
                builder.setShowWhen(false)
                builder.setUsesChronometer(false)
                val action = Notification.Action.Builder(
                    AndroidIcon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Hangup", hangupIntent).build()
                builder.addAction(action)
            }
            else -> { // Missed
                builder.setShowWhen(false)
                builder.setUsesChronometer(false)
                builder.setOngoing(false)
                val action = Notification.Action.Builder(
                    AndroidIcon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dismiss", hangupIntent).build()
                builder.addAction(action)
            }
        }
        
        return builder.build()
    }

    private fun buildLegacyNotification(
        phoneNumber: String,
        @Suppress("UNUSED_PARAMETER") name: String,
        isIncoming: Boolean,
        isMissed: Boolean,
        isDialing: Boolean,
        isSimulated: Boolean,
        startTime: Long,
        channelId: String,
        pendingIntent: PendingIntent,
        callId: String
    ): Notification {
        val hangupIntent = createActionIntent(TelecomConstants.ACTION_HANGUP, TelecomConstants.REQUEST_CODE_HANGUP, callId)
        val answerIntent = createActionIntent(TelecomConstants.ACTION_ANSWER, TelecomConstants.REQUEST_CODE_ANSWER, callId)

        return NotificationCompat.Builder(context, channelId)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(!isMissed)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, if (isMissed) "Dismiss" else "Hangup", hangupIntent)
            .apply {
                if (isIncoming && !isMissed) addAction(android.R.drawable.ic_menu_call, "Answer", answerIntent)
                if (!isSimulated && isIncoming && !isMissed && canUseFullScreenIntent()) {
                    setFullScreenIntent(pendingIntent, true)
                }
            }
            .build()
    }

    private fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val allowed = manager.canUseFullScreenIntent()
            if (!allowed) {
                Log.w("CallNotificationFactory", "canUseFullScreenIntent() is FALSE on Android 14+! FullScreenIntent suppressed by OS.")
            }
            allowed
        } else {
            true
        }
    }
}

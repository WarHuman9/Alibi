package com.example.alibi.service.factory

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.alibi.MainActivity
import com.example.alibi.receiver.CallActionReceiver
import com.example.alibi.telecom.TelecomConstants

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
        callId: String
    ): Notification {
        val pendingIntent = createContentIntent()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildModernNotification(
                phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, channelId, pendingIntent, callId
            )
        } else {
            buildLegacyNotification(
                phoneNumber, name, isIncoming, isMissed, isDialing, isSimulated, startTime, channelId, pendingIntent, callId
            )
        }
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createActionIntent(action: String, requestCode: Int, callId: String): PendingIntent {
        return PendingIntent.getBroadcast(
            context, 
            requestCode, 
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
        isSimulated: Boolean,
        startTime: Long,
        channelId: String,
        pendingIntent: PendingIntent,
        callId: String
    ): Notification {
        val person = Person.Builder()
            .setName(name)
            .setImportant(true)
            .build()

        val hangupIntent = createActionIntent(TelecomConstants.ACTION_HANGUP, 1, callId)
        val answerIntent = createActionIntent(TelecomConstants.ACTION_ANSWER, 2, callId)

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(when {
                isMissed -> "Missed call"
                isDialing -> "Calling..."
                isIncoming -> "Incoming call..."
                else -> "Active call"
            })
            .setContentText(phoneNumber)
            .setContentIntent(pendingIntent)
            .setOngoing(!isMissed)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_CALL)

        val isRinging = isIncoming && !isMissed && !isDialing
        val isActive = !isIncoming && !isMissed && !isDialing
        val isConnecting = isDialing && !isMissed

        if (isRinging && !isSimulated && canUseFullScreenIntent()) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        when {
            isRinging -> {
                builder.style = Notification.CallStyle.forIncomingCall(person, hangupIntent, answerIntent)
            }
            isActive -> {
                val finalStartTime = if (startTime > 0L) startTime else System.currentTimeMillis()
                builder.setWhen(finalStartTime)
                builder.setUsesChronometer(true)
                builder.setShowWhen(true)
                builder.style = Notification.CallStyle.forOngoingCall(person, hangupIntent)
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
        val hangupIntent = createActionIntent(TelecomConstants.ACTION_HANGUP, 1, callId)
        val answerIntent = createActionIntent(TelecomConstants.ACTION_ANSWER, 2, callId)

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
                if (!isSimulated && canUseFullScreenIntent()) {
                    setFullScreenIntent(pendingIntent, true)
                }
            }
            .build()
    }

    private fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.canUseFullScreenIntent()
        } else {
            true
        }
    }
}

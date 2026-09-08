package com.example.alibi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(TelecomConstants.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            TelecomConstants.ACTION_HANGUP -> {
                CallStateManager.disconnect(callId)
            }
            TelecomConstants.ACTION_ANSWER -> {
                CallStateManager.answer(callId)
            }
        }
    }
}

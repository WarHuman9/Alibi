package com.example.alibi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.alibi.telecom.CallStateManager

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HANGUP -> {
                CallStateManager.disconnect()
            }
            ACTION_ANSWER -> {
                CallStateManager.answer()
            }
        }
    }

    companion object {
        const val ACTION_HANGUP = "com.example.alibi.ACTION_HANGUP"
        const val ACTION_ANSWER = "com.example.alibi.ACTION_ANSWER"
    }
}

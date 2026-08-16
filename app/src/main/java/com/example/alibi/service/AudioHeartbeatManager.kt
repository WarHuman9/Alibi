package com.example.alibi.service

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import com.example.alibi.telecom.CallStateManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Audio Focus and Silent Audio Playback to maintain high process priority
 * and satisfy Android 14+ background behavioral checks for phone call services.
 */
class AudioHeartbeatManager private constructor(context: Context) : AudioManager.OnAudioFocusChangeListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var heartbeatJob: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private var focusRequest: AudioFocusRequest? = null
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Reference to the active connection for status synchronization
    var connection: SimulatedConnection? = null

    init {
        managerScope.launch {
            CallStateManager.isRealCall.collect { isReal ->
                if (isReal) {
                    Log.d(TAG, "Real call detected. Forcing heartbeat stop.")
                    stop()
                }
            }
        }
    }

    fun start() {
        if (!CallStateManager.isCurrentCallSimulated()) {
            Log.d(TAG, "Start requested but NO simulated call active. Bypassing heartbeat for system safety.")
            return
        }
        
        if (isPlaying.compareAndSet(false, true)) {
            Log.d(TAG, "Starting Audio Heartbeat...")
            try {
                requestAudioFocus()
                startSilentAudio()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start heartbeat", e)
                stop()
            }
        }
    }


    fun stop() {
        isPlaying.set(false)
        Log.d(TAG, "Aggressive stop: Releasing AudioTrack and abandoning focus.")
        stopSilentAudio()
        abandonAudioFocus()
        resetHardware()
    }

    /**
     * Centralized hardware reset to ensure simulation doesn't leak into real calls.
     */
    private fun resetHardware() {
        try {
            Log.d(TAG, "Resetting hardware audio mode to NORMAL")
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset hardware audio mode", e)
        }
    }

    /**
     * Task 18: Hardware-level reset to ensure simulation doesn't leak into real calls.
     */
    fun forceReset() {
        Log.w(TAG, "Force reset requested. Cleaning up audio state.")
        stop()
        resetHardware()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "onAudioFocusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost ($focusChange). Requesting Hold.")
                // Task 11: Ensure ducking triggers hold immediately
                connection?.setOnHold()
                connection?.onHold()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained. Resuming.")
                connection?.setUnhold()
            }
        }
    }

    private fun requestAudioFocus() {
        if (!CallStateManager.isCurrentCallSimulated()) {
            Log.d(TAG, "requestAudioFocus: No simulated call. Bypassing to protect real call audio path.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            
            val result = audioManager.requestAudioFocus(focusRequest!!)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio focus request not granted: $result")
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio focus request not granted (legacy): $result")
            }
        }
    }

    private fun abandonAudioFocus() {
        try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(this)
            }
            Log.d(TAG, "abandonAudioFocus result: $result")
            focusRequest = null
            // Task 18: Reset mode immediately after abandoning focus to prevent simulation leak
            resetHardware()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abandon audio focus", e)
        }
    }

    private fun startSilentAudio() {
        heartbeatJob = audioScope.launch {
            var track: AudioTrack? = null
            try {
                val sampleRate = 8000
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack failed to initialize")
                    track.release()
                    return@launch
                }

                audioTrack = track
                track.play()
                Log.d(TAG, "Silent audio heartbeat coroutine started")

                val silentBuffer = ShortArray(bufferSize)
                while (isActive && isPlaying.get()) {
                    track.write(silentBuffer, 0, silentBuffer.size)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "AudioHeartbeat coroutine error", e)
                }
            } finally {
                Log.d(TAG, "Cleaning up AudioTrack in coroutine finally block")
                track?.apply {
                    try {
                        if (state == AudioTrack.STATE_INITIALIZED) {
                            stop()
                        }
                    } catch (e: Exception) { /* ignore */ }
                    release()
                }
                if (audioTrack == track) {
                    audioTrack = null
                }
                // If we stopped unexpectedly but isPlaying is still true, trigger a full stop
                if (isPlaying.get() && isActive) {
                    managerScope.launch { stop() }
                }
            }
        }
    }

    private fun stopSilentAudio() {
        Log.d(TAG, "Stopping silent audio: cancelling job")
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        // Safety: ensure it's cleared if the coroutine hasn't handled it yet or won't
        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    try { stop() } catch (e: Exception) { /* ignore */ }
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing silent audio", e)
        }
        audioTrack = null
    }

    companion object {
        private const val TAG = "AudioHeartbeat"

        @Volatile
        private var instance: AudioHeartbeatManager? = null

        fun getInstance(context: Context): AudioHeartbeatManager {
            return instance ?: synchronized(this) {
                instance ?: AudioHeartbeatManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

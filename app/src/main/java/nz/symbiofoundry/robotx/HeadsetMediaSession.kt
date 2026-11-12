package nz.symbiofoundry.robotx

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import java.io.Closeable

/**
 * Production integration of the accepted Robot X headset H1 mechanism.
 *
 * Authority boundary:
 * - owns Android media-button reception only;
 * - may request one bounded voice-listen toggle through [onListenToggle];
 * - has no RobotController, RobotCommand, robot BLE, or motor authority.
 *
 * H2/H3 headset audio routing and microphone capture are intentionally absent.
 */
class HeadsetMediaSession(
    context: Context,
    private val onListenToggle: (source: String, keyName: String) -> Unit,
) : Closeable {

    companion object {
        private const val TAG = "RobotXHeadset"
    }

    private val triggerPolicy = HeadsetTriggerPolicy()
    private val session = MediaSession(context, "RobotX-H1")
    private val audioTrack: AudioTrack

    init {
        val sampleRate = 8000
        val silenceFrames = sampleRate
        val silence = ShortArray(silenceFrames)

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            silence.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )

        val written = audioTrack.write(silence, 0, silence.size)
        check(written == silence.size) {
            "AudioTrack static write failed: wrote $written of ${silence.size} frames"
        }
        audioTrack.setLoopPoints(0, silenceFrames, -1)
        audioTrack.play()
        Log.i(TAG, "RXH_PROD_AUDIO_ACTIVITY active=true mode=silent_static_loop")

        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        session.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = extractKeyEvent(mediaButtonIntent)
                    if (event == null) {
                        Log.w(TAG, "RXH_PROD_MEDIA_EVENT")
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }

                    val keyName = KeyEvent.keyCodeToString(event.keyCode)
                    val actionName = when (event.action) {
                        KeyEvent.ACTION_DOWN -> "DOWN"
                        KeyEvent.ACTION_UP -> "UP"
                        KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
                        else -> event.action.toString()
                    }

                    Log.i(TAG, "RXH_PROD_MEDIA_EVENT")

                    if (
                        triggerPolicy.shouldTrigger(
                            keyCode = event.keyCode,
                            action = event.action,
                            repeatCount = event.repeatCount,
                            downTime = event.downTime,
                        )
                    ) {
                        Log.i(TAG, "RXH_PROD_TRIGGER")
                        onListenToggle("MEDIA_SESSION", keyName)
                        return true
                    }

                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() { Log.i(TAG, "RXH_PROD_TRANSPORT_CALLBACK callback=onPlay") }
                override fun onPause() { Log.i(TAG, "RXH_PROD_TRANSPORT_CALLBACK callback=onPause") }
                override fun onStop() { Log.i(TAG, "RXH_PROD_TRANSPORT_CALLBACK callback=onStop") }
            },
            Handler(Looper.getMainLooper()),
        )

        val component = ComponentName(context, MediaButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { this.component = component }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(context, 0, mediaButtonIntent, pendingFlags)

        session.setMediaButtonReceiver(mediaButtonPendingIntent)
        Log.i(TAG, "RXH_PROD_MEDIA_BUTTON_RECEIVER_REGISTERED component=$component")

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )

        session.isActive = true
        Log.i(TAG, "RXH_PROD_MEDIA_SESSION_ACTIVE active=true")
    }

    @Suppress("DEPRECATION")
    private fun extractKeyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    override fun close() {
        try { audioTrack.stop() } catch (_: IllegalStateException) {}
        audioTrack.release()
        Log.i(TAG, "RXH_PROD_AUDIO_ACTIVITY active=false")

        session.isActive = false
        session.setCallback(null)
        session.release()
        Log.i(TAG, "RXH_PROD_MEDIA_SESSION_ACTIVE active=false")
    }
}

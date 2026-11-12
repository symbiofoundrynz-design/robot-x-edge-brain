package nz.symbiofoundry.robotx

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class VoiceInputState {
READY,
LISTENING,
PROCESSING,
DENIED,
UNAVAILABLE
}

/**

E2.4B bounded offline voice-capture transport.

Authority boundary:

owns microphone capture and offline speech-to-text only;
emits one final transcript after recording stops;
does not submit commands, control BLE, or touch RobotController;
never emits partial recognition results.
*/
class VoiceInputController(
context: Context,
private val onStateChanged: (VoiceInputState) -> Unit,
private val onFinalTranscript: (String) -> Unit,
private val onError: (String) -> Unit
) : AutoCloseable {

companion object {
private const val TAG = "RobotXVoice"
private const val SAMPLE_RATE = 16000
private const val MODEL_DIR =
"sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"
}

private val appContext = context.applicationContext
private val worker = Executors.newSingleThreadExecutor()
private val recording = AtomicBoolean(false)
private val sampleChunks = ArrayList<ShortArray>()
private val sampleLock = Any()

@Volatile
private var state = VoiceInputState.PROCESSING

@Volatile
private var recognizer: OfflineRecognizer? = null

@Volatile
private var audioRecord: AudioRecord? = null

fun initialize() {
publishState(VoiceInputState.PROCESSING)
worker.execute {
runCatching {
val moonshine = OfflineMoonshineModelConfig().apply {
encoder = "$MODEL_DIR/encoder_model.ort"
mergedDecoder = "$MODEL_DIR/decoder_model_merged.ort"
}

         val model = OfflineModelConfig().apply {
             this.moonshine = moonshine
             tokens = "$MODEL_DIR/tokens.txt"
             numThreads = 2
             debug = false
             provider = "cpu"
         }

         val feature = FeatureConfig().apply {
             sampleRate = SAMPLE_RATE
             featureDim = 80
             dither = 0.0f
         }

         val config = OfflineRecognizerConfig().apply {
             featConfig = feature
             modelConfig = model
             decodingMethod = "greedy_search"
         }

         recognizer = OfflineRecognizer(appContext.assets, config)
     }.onSuccess {
         Log.i(TAG, "E24B_OFFLINE_STT_READY model=moonshine-tiny-en-quantized")
         publishState(VoiceInputState.READY)
     }.onFailure { error ->
         Log.e(TAG, "E24B_OFFLINE_STT_INIT_ERROR")
         publishState(VoiceInputState.UNAVAILABLE)
         publishError("Offline speech recognizer unavailable: ${error.message ?: error.javaClass.simpleName}")
     }
 }

}

fun currentState(): VoiceInputState = state

fun toggleCapture() {
when (state) {
VoiceInputState.READY -> startCapture()
VoiceInputState.LISTENING -> stopCapture()
VoiceInputState.DENIED -> publishError("Microphone permission denied.")
VoiceInputState.PROCESSING -> publishError("Voice input is still processing.")
VoiceInputState.UNAVAILABLE -> publishError("Offline voice input is unavailable.")
}
}

@SuppressLint("MissingPermission")
private fun startCapture() {
if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
PackageManager.PERMISSION_GRANTED
) {
publishState(VoiceInputState.DENIED)
publishError("Microphone permission denied.")
return
}

 val minimum = AudioRecord.getMinBufferSize(
     SAMPLE_RATE,
     AudioFormat.CHANNEL_IN_MONO,
     AudioFormat.ENCODING_PCM_16BIT
 )
 if (minimum <= 0) {
     publishState(VoiceInputState.UNAVAILABLE)
     publishError("Unable to allocate microphone buffer.")
     return
 }

 val audioManager =
     appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as
         android.media.AudioManager
 val bluetoothInput =
     audioManager
         .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
         .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
 val bluetoothOutput =
     audioManager
         .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
         .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
 val hasBluetoothDuplexRoute = bluetoothInput != null && bluetoothOutput != null
 val audioSource =
     if (hasBluetoothDuplexRoute) {
         MediaRecorder.AudioSource.VOICE_COMMUNICATION
     } else {
         MediaRecorder.AudioSource.VOICE_RECOGNITION
     }

 val recorder = AudioRecord.Builder()
     .setAudioSource(audioSource)
     .setAudioFormat(
         AudioFormat.Builder()
             .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
             .setSampleRate(SAMPLE_RATE)
             .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
             .build()
     )
     .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE / 5 * 2))
     .build()

 if (recorder.state != AudioRecord.STATE_INITIALIZED) {
     recorder.release()
     publishState(VoiceInputState.UNAVAILABLE)
     publishError("Microphone failed to initialize.")
     return
 }

 synchronized(sampleLock) {
     sampleChunks.clear()
 }

 var usingBluetoothRoute = false

 if (hasBluetoothDuplexRoute &&
     android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
 ) {
     val input = bluetoothInput!!
     val output = bluetoothOutput!!
     val communicationAccepted = audioManager.setCommunicationDevice(output)
     val preferredAccepted = recorder.setPreferredDevice(input)
     usingBluetoothRoute = communicationAccepted && preferredAccepted
     Log.i(TAG, "E24B_COMMUNICATION_PAIR")
 } else if (hasBluetoothDuplexRoute) {
     Log.i(TAG, "E24B_COMMUNICATION_PAIR")
 } else {
     Log.i(TAG, "E24B_COMMUNICATION_PAIR")
 }

 audioRecord = recorder
 recording.set(true)
 recorder.startRecording()

 val routedDevice = recorder.routedDevice
 if (routedDevice != null) {
     Log.i(TAG, "E24B_INPUT_ROUTE")
 } else {
     Log.w(TAG, "E24B_INPUT_ROUTE")
 }

 publishState(VoiceInputState.LISTENING)
 Log.i(TAG, "E24B_CAPTURE_START")

 worker.execute {
     captureLoop(recorder, usingBluetoothRoute)
 }

}

private fun captureLoop(recorder: AudioRecord, usingBluetoothRoute: Boolean) {
 val captureStartedAt = android.os.SystemClock.elapsedRealtime()
 var noiseFloorRms = 80.0
 var speechCandidateSince: Long? = null
 var speechStarted = false
 var lastSpeechAt: Long? = null
 var shouldDecode = true
 var noSpeechReasonLogged = false

 val speechStartWindowMs = 6_000L
 val speechConfirmMs = 180L
 val trailingSilenceMs = 1_000L
 val maximumCaptureMs = 15_000L
 val minimumSpeechRms = 250.0
 val speechNoiseMultiplier = 3.0

 Log.i(
     TAG,
     "E24B_SPEECH_WAIT startWindowMs=$speechStartWindowMs " +
         "confirmMs=$speechConfirmMs trailingSilenceMs=$trailingSilenceMs " +
         "maxCaptureMs=$maximumCaptureMs"
 )
val buffer = ShortArray(2048)

 try {
     while (recording.get()) {
         val count =
             if (usingBluetoothRoute) {
                 recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
             } else {
                 recorder.read(buffer, 0, buffer.size)
             }
         if (count > 0) {
             synchronized(sampleLock) {
                 sampleChunks.add(buffer.copyOf(count))
             }

             val now = android.os.SystemClock.elapsedRealtime()
             var sumSquares = 0.0
             for (index in 0 until count) {
                 val sample = buffer[index].toDouble()
                 sumSquares += sample * sample
             }
             val rms = kotlin.math.sqrt(sumSquares / count.toDouble())
             val speechThreshold =
                 kotlin.math.max(minimumSpeechRms, noiseFloorRms * speechNoiseMultiplier)

             if (!speechStarted) {
                 if (rms >= speechThreshold) {
                     val candidateStart = speechCandidateSince
                     if (candidateStart == null) {
                         speechCandidateSince = now
                     } else if (now - candidateStart >= speechConfirmMs) {
                         speechStarted = true
                         lastSpeechAt = now
                         Log.i(
                             TAG,
                             "E24B_SPEECH_START rms=$rms threshold=$speechThreshold"
                         )
                     }
                 } else {
                     speechCandidateSince = null
                     noiseFloorRms = (noiseFloorRms * 0.95) + (rms * 0.05)
                 }

                 if (!speechStarted && now - captureStartedAt >= speechStartWindowMs) {
                     shouldDecode = false
                     noSpeechReasonLogged = true
                     recording.set(false)
                     Log.i(
                         TAG,
                         "E24B_NO_SPEECH_CANCEL reason=START_WINDOW_TIMEOUT " +
                             "elapsedMs=${now - captureStartedAt}"
                     )
                     break
                 }
             } else {
                 if (rms >= speechThreshold) {
                     lastSpeechAt = now
                 } else {
                     val lastActive = lastSpeechAt
                     if (lastActive != null && now - lastActive >= trailingSilenceMs) {
                         recording.set(false)
                         publishState(VoiceInputState.PROCESSING)
                         Log.i(
                             TAG,
                             "E24B_AUTO_STOP reason=TRAILING_SILENCE " +
                                 "elapsedMs=${now - captureStartedAt}"
                         )
                         break
                     }
                 }
             }

             if (now - captureStartedAt >= maximumCaptureMs) {
                 recording.set(false)
                 if (speechStarted) {
                     publishState(VoiceInputState.PROCESSING)
                     Log.i(
                         TAG,
                         "E24B_AUTO_STOP reason=MAX_DURATION " +
                             "elapsedMs=${now - captureStartedAt}"
                     )
                 } else {
                     shouldDecode = false
                     noSpeechReasonLogged = true
                     Log.i(
                         TAG,
                         "E24B_NO_SPEECH_CANCEL reason=MAX_DURATION_NO_SPEECH " +
                             "elapsedMs=${now - captureStartedAt}"
                     )
                 }
                 break
             }
         } else if (count == 0 && usingBluetoothRoute) {
             val now = android.os.SystemClock.elapsedRealtime()

             if (!speechStarted && now - captureStartedAt >= speechStartWindowMs) {
                 shouldDecode = false
                 noSpeechReasonLogged = true
                 recording.set(false)
                 Log.i(
                     TAG,
                     "E24B_NO_SPEECH_CANCEL reason=START_WINDOW_TIMEOUT_NO_FRAMES " +
                         "elapsedMs=${now - captureStartedAt}"
                 )
                 break
             }

             val lastActive = lastSpeechAt
             if (speechStarted &&
                 lastActive != null &&
                 now - lastActive >= trailingSilenceMs
             ) {
                 recording.set(false)
                 publishState(VoiceInputState.PROCESSING)
                 Log.i(
                     TAG,
                     "E24B_AUTO_STOP reason=TRAILING_SILENCE_NO_FRAMES " +
                         "elapsedMs=${now - captureStartedAt}"
                 )
                 break
             }

             if (now - captureStartedAt >= maximumCaptureMs) {
                 recording.set(false)
                 if (speechStarted) {
                     publishState(VoiceInputState.PROCESSING)
                     Log.i(
                         TAG,
                         "E24B_AUTO_STOP reason=MAX_DURATION_NO_FRAMES " +
                             "elapsedMs=${now - captureStartedAt}"
                     )
                 } else {
                     shouldDecode = false
                     noSpeechReasonLogged = true
                     Log.i(
                         TAG,
                         "E24B_NO_SPEECH_CANCEL reason=MAX_DURATION_NO_FRAMES " +
                             "elapsedMs=${now - captureStartedAt}"
                     )
                 }
                 break
             }
             try {
                 Thread.sleep(10)
             } catch (_: InterruptedException) {
                 Thread.currentThread().interrupt()
                 break
             }
         } else if (count < 0 && recording.get()) {
             throw IllegalStateException("AudioRecord read failed: $count")
         }
     }
 } catch (error: Throwable) {
     if (recording.get()) {
         Log.e(TAG, "E24B_CAPTURE_ERROR")
         publishState(VoiceInputState.UNAVAILABLE)
         publishError("Voice capture failed: ${error.message ?: error.javaClass.simpleName}")
     }
 } finally {
     recording.set(false)
     runCatching { recorder.stop() }
     recorder.release()
     if (audioRecord === recorder) {
         audioRecord = null
     }
 }

 if (state == VoiceInputState.PROCESSING) {
     if (usingBluetoothRoute) {
         clearBluetoothCommunicationDevice("capture_complete")
     }
     if (shouldDecode && speechStarted) {
         decodeFinalTranscript()
     } else {
         synchronized(sampleLock) {
             sampleChunks.clear()
         }
         if (!noSpeechReasonLogged) {
             Log.i(TAG, "E24B_NO_SPEECH_CANCEL reason=NO_CONFIRMED_SPEECH")
         }
         publishState(VoiceInputState.READY)
     }
 }

}

private fun stopCapture() {
if (!recording.compareAndSet(true, false)) {
return
}

 publishState(VoiceInputState.PROCESSING)
 Log.i(TAG, "E24B_CAPTURE_STOP")
 runCatching { audioRecord?.stop() }

}

private fun decodeFinalTranscript() {
val localRecognizer = recognizer
if (localRecognizer == null) {
publishState(VoiceInputState.UNAVAILABLE)
publishError("Offline speech recognizer is not ready.")
return
}

 val chunks = synchronized(sampleLock) {
     sampleChunks.toList().also { sampleChunks.clear() }
 }
 val total = chunks.sumOf { it.size }
 if (total == 0) {
     publishState(VoiceInputState.READY)
     publishError("No microphone audio was captured.")
     return
 }

 runCatching {
     val samples = FloatArray(total)
     var index = 0
     for (chunk in chunks) {
         for (sample in chunk) {
             samples[index++] = sample.toFloat() / 32768.0f
         }
     }

     val stream = localRecognizer.createStream()
     try {
         stream.acceptWaveform(samples, SAMPLE_RATE)
         localRecognizer.decode(stream)
         localRecognizer.getResult(stream).text.trim()
     } finally {
         stream.release()
     }
 }.onSuccess { transcript ->
     if (transcript.isNotBlank()) {
         Log.i(TAG, "E24B_FINAL_TRANSCRIPT_RECEIVED")
         appContext.mainExecutor.execute {
             onFinalTranscript(transcript)
         }
     } else {
         publishError("No speech was recognized.")
     }
     publishState(VoiceInputState.READY)
 }.onFailure { error ->
     Log.e(TAG, "E24B_DECODE_ERROR")
     publishState(VoiceInputState.READY)
     publishError("Speech recognition failed: ${error.message ?: error.javaClass.simpleName}")
 }

}

fun markPermissionDenied() {
publishState(VoiceInputState.DENIED)
}

fun markPermissionAvailable() {
if (recognizer != null && state == VoiceInputState.DENIED) {
publishState(VoiceInputState.READY)
}
}

private fun clearBluetoothCommunicationDevice(reason: String) {
 if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return

 val audioManager =
     appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as
         android.media.AudioManager

 runCatching {
     audioManager.clearCommunicationDevice()
 }.onSuccess {
     Log.i(TAG, "E24B_COMMUNICATION_DEVICE_CLEARED reason=$reason")
 }.onFailure { error ->
     Log.w(TAG, "E24B_COMMUNICATION_DEVICE_CLEAR_FAILED")
 }
}

private fun publishState(newState: VoiceInputState) {
state = newState
appContext.mainExecutor.execute {
onStateChanged(newState)
}
}

private fun publishError(message: String) {
appContext.mainExecutor.execute {
onError(message)
}
}

override fun close() {
recording.set(false)
runCatching { audioRecord?.stop() }
clearBluetoothCommunicationDevice("controller_close")
audioRecord = null
worker.shutdown()
runCatching { recognizer?.release() }
recognizer = null
Log.i(TAG, "E24B_VOICE_CONTROLLER_CLOSED")
}

}

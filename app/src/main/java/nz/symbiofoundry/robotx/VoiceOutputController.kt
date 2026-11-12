package nz.symbiofoundry.robotx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

enum class VoiceOutputState {
    INITIALIZING,
    READY,
    SPEAKING,
    UNAVAILABLE,
    CLOSED,
}

/**
 * E2.5 bounded offline voice-output transport.
 *
 * This class owns local text-to-speech synthesis and phone-speaker playback only.
 * It has no RobotController, BLE, camera, routing, or reasoning authority.
 */
class VoiceOutputController(
    context: Context,
    private val onState: (VoiceOutputState) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AutoCloseable {

    companion object {
        private const val TAG = "RobotXVoiceOutput"
        private const val MODEL_DIR = "sherpa-onnx-vits-piper-en_US-lessac-medium"
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var closed = false

    fun initialize() {
        if (closed) return
        publishState(VoiceOutputState.INITIALIZING)

        executor.execute {
            runCatching {
                val espeakDataDir = prepareEspeakDataDir()

                val vits = OfflineTtsVitsModelConfig().apply {
                    model = "$MODEL_DIR/en_US-lessac-medium.onnx"
                    tokens = "$MODEL_DIR/tokens.txt"
                    dataDir = espeakDataDir
                    noiseScale = 0.667f
                    noiseScaleW = 0.8f
                    lengthScale = 1.0f
                }

                val model = OfflineTtsModelConfig().apply {
                    this.vits = vits
                    numThreads = 2
                    debug = false
                }

                val config = OfflineTtsConfig().apply {
                    this.model = model
                    maxNumSentences = 1
                }

                OfflineTts(appContext.assets, config)
            }.onSuccess { engine ->
                if (closed) {
                    engine.release()
                    return@onSuccess
                }
                tts = engine
                Log.i(
                    TAG,
                    "E25_OFFLINE_TTS_READY model=piper-en_US-lessac-medium " +
                        "sampleRate=${engine.sampleRate()} speakers=${engine.numSpeakers()}"
                )
                publishState(VoiceOutputState.READY)
            }.onFailure { error ->
                Log.e(TAG, "E25_OFFLINE_TTS_INIT_ERROR")
                publishState(VoiceOutputState.UNAVAILABLE)
                publishError(
                    "Offline voice output unavailable: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    fun speak(text: String) {
        val utterance = text.trim()
        if (utterance.isEmpty() || closed) return

        executor.execute {
            val engine = tts
            if (engine == null) {
                publishError("Offline voice output is not ready.")
                return@execute
            }

            runCatching {
                publishState(VoiceOutputState.SPEAKING)
                val generated = engine.generate(utterance, 0, 1.0f)
                val samples = generated.samples
                val sampleRate = generated.sampleRate

                check(samples.isNotEmpty()) { "TTS produced no audio samples." }
                check(sampleRate > 0) { "TTS returned invalid sample rate: $sampleRate" }

                playSamples(samples, sampleRate)

                Log.i(
                    TAG,
                    "E25_OFFLINE_TTS_COMPLETE chars=${utterance.length} " +
                        "samples=${samples.size} sampleRate=$sampleRate"
                )
            }.onSuccess {
                if (!closed) publishState(VoiceOutputState.READY)
            }.onFailure { error ->
                Log.e(TAG, "E25_OFFLINE_TTS_ERROR")
                if (!closed) publishState(VoiceOutputState.READY)
                publishError(
                    "Voice output failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minBuffer > 0) { "AudioTrack buffer query failed: $minBuffer" }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeTrack = track
        try {
            track.play()
            var offset = 0
            while (offset < samples.size && !closed) {
                val written = track.write(
                    samples,
                    offset,
                    samples.size - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                check(written > 0) { "AudioTrack write failed: $written" }
                offset += written
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            if (activeTrack === track) activeTrack = null
        }
    }

    private fun prepareEspeakDataDir(): String {
        val assetPath = "$MODEL_DIR/espeak-ng-data"
        val root = appContext.getExternalFilesDir(null)
            ?: error("External app files directory unavailable.")
        val target = File(root, assetPath)
        val marker = File(target, ".robotx-copy-complete")

        if (!marker.isFile) {
            if (target.exists()) {
                target.deleteRecursively()
            }
            copyAssetTree(assetPath, target)
            marker.parentFile?.mkdirs()
            marker.writeText("Robot X E2.5 espeak-ng-data copy complete\n")
            Log.i(TAG, "E25_ESPEAK_DATA_READY")
        }

        return target.absolutePath
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath)
            ?: error("Unable to list asset path: $assetPath")

        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()
        check(target.isDirectory) { "Unable to create directory: ${target.absolutePath}" }
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    private fun publishState(state: VoiceOutputState) {
        onState(state)
    }

    private fun publishError(message: String) {
        onError(message)
    }

    override fun close() {
        closed = true
        runCatching { activeTrack?.pause() }
        runCatching { activeTrack?.flush() }
        runCatching { activeTrack?.release() }
        activeTrack = null

        executor.shutdownNow()
        runCatching { tts?.release() }
        tts = null

        publishState(VoiceOutputState.CLOSED)
    }
}

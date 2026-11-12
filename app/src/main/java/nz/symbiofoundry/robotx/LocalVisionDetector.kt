package nz.symbiofoundry.robotx

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.util.Locale

class LocalVisionDetector(
    context: Context,
    private val config: Config = Config()
) : AutoCloseable {

    data class Config(
        val modelAssetName: String = "efficientdet_lite0_int8.tflite",
        val scoreThreshold: Float = 0.50f,
        val maxResults: Int = 10
    )

    companion object {
        private const val TAG = "RobotXVision"
        private const val DELEGATE_NAME = "CPU"
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var closed = false

    private var detector: ObjectDetector? = null
    private var modelInfo: VisionModelInfo? = null

    init {
        require(config.modelAssetName.isNotBlank()) { "modelAssetName must not be blank" }
        require(config.scoreThreshold in 0.0f..1.0f) { "scoreThreshold must be between 0 and 1" }
        require(config.maxResults in 1..100) { "maxResults must be between 1 and 100" }
    }

    fun ensureLoaded(): VisionModelInfo = synchronized(lock) {
        checkOpen()
        modelInfo?.let { return@synchronized it }

        Log.i(
            TAG,
            "E32_MODEL_LOAD_START model=${config.modelAssetName} threshold=${config.scoreThreshold} max_results=${config.maxResults}"
        )

        try {
            val modelBytes = appContext.assets.openFd(config.modelAssetName).use { it.length }
            val startMs = SystemClock.elapsedRealtime()

            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(config.modelAssetName)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(config.scoreThreshold)
                .setMaxResults(config.maxResults)
                .build()

            val created = ObjectDetector.createFromOptions(appContext, options)
            val loadMs = SystemClock.elapsedRealtime() - startMs
            val info = VisionModelInfo(
                modelAssetName = config.modelAssetName,
                modelBytes = modelBytes,
                loadMs = loadMs,
                delegate = DELEGATE_NAME
            )

            detector = created
            modelInfo = info

            Log.i(
                TAG,
                "E32_MODEL_LOAD_SUCCESS model=${info.modelAssetName} bytes=${info.modelBytes} load_ms=${info.loadMs} delegate=${info.delegate}"
            )
            info
        } catch (e: Exception) {
            Log.e(TAG, "E32_MODEL_LOAD_ERROR")
            throw VisionDetectorException(
                VisionErrorCode.MODEL_LOAD_FAILED,
                "Unable to load local vision model ${config.modelAssetName}",
                e
            )
        }
    }

    fun analyze(imageFile: File): VisionInferenceResult = synchronized(lock) {
        checkOpen()

        if (!imageFile.isFile) {
            throw VisionDetectorException(
                VisionErrorCode.IMAGE_NOT_FOUND,
                "Image file not found: ${imageFile.absolutePath}"
            )
        }
        if (imageFile.length() <= 0L) {
            throw VisionDetectorException(
                VisionErrorCode.IMAGE_EMPTY,
                "Image file is empty: ${imageFile.absolutePath}"
            )
        }

        val activeDetector = ensureLoaded()
        val detectorInstance = detector
            ?: throw VisionDetectorException(
                VisionErrorCode.MODEL_LOAD_FAILED,
                "Vision model was reported loaded but detector instance is unavailable"
            )

        val bitmap = try {
            BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: throw VisionDetectorException(
                    VisionErrorCode.IMAGE_DECODE_FAILED,
                    "Unable to decode image: ${imageFile.absolutePath}"
                )
        } catch (e: VisionDetectorException) {
            throw e
        } catch (e: Exception) {
            throw VisionDetectorException(
                VisionErrorCode.IMAGE_DECODE_FAILED,
                "Unable to decode image: ${imageFile.absolutePath}",
                e
            )
        }

        try {
            val width = bitmap.width
            val height = bitmap.height
            val sourceBytes = imageFile.length()

            Log.i(TAG, "E32_ANALYZE_REQUEST")

            val mpImage = BitmapImageBuilder(bitmap).build()
            val startMs = SystemClock.elapsedRealtime()
            val rawResult = detectorInstance.detect(mpImage)
            val inferenceMs = SystemClock.elapsedRealtime() - startMs

            val accepted = rawResult.detections()
                .mapNotNull { detection ->
                    val category = detection.categories().firstOrNull() ?: return@mapNotNull null
                    val label = category.categoryName().trim()
                    val confidence = category.score()
                    val box = detection.boundingBox()

                    if (label.isBlank()) return@mapNotNull null
                    if (!confidence.isFinite() || confidence < config.scoreThreshold) return@mapNotNull null
                    if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) {
                        return@mapNotNull null
                    }

                    val tolerance = 1.0f
                    if (box.left < -tolerance || box.top < -tolerance || box.right > width + tolerance || box.bottom > height + tolerance) {
                        return@mapNotNull null
                    }

                    val left = box.left.coerceIn(0.0f, width.toFloat())
                    val top = box.top.coerceIn(0.0f, height.toFloat())
                    val right = box.right.coerceIn(0.0f, width.toFloat())
                    val bottom = box.bottom.coerceIn(0.0f, height.toFloat())
                    if (right <= left || bottom <= top) return@mapNotNull null

                    VisionDetection(
                        label = label,
                        confidence = confidence,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom
                    )
                }
                .sortedByDescending { it.confidence }
                .take(config.maxResults)

            accepted.forEach { detection ->
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "E32_DETECTION label=%s confidence=%.3f box=[%.1f,%.1f,%.1f,%.1f]",
                        detection.label,
                        detection.confidence,
                        detection.left,
                        detection.top,
                        detection.right,
                        detection.bottom
                    )
                )
            }

            if (accepted.isEmpty()) {
                Log.i(TAG, "E32_ANALYZE_EMPTY inference_ms=$inferenceMs")
            } else {
                Log.i(TAG, "E32_ANALYZE_SUCCESS detections=${accepted.size} inference_ms=$inferenceMs")
            }

            VisionInferenceResult(
                sourceImagePath = imageFile.absolutePath,
                sourceWidth = width,
                sourceHeight = height,
                sourceBytes = sourceBytes,
                inferenceMs = inferenceMs,
                detections = accepted
            )
        } catch (e: VisionDetectorException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "E32_ANALYZE_ERROR")
            throw VisionDetectorException(
                VisionErrorCode.INFERENCE_FAILED,
                "Local vision inference failed for ${imageFile.absolutePath}",
                e
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun checkOpen() {
        if (closed) {
            throw VisionDetectorException(
                VisionErrorCode.CLOSED,
                "LocalVisionDetector is closed"
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            detector?.close()
            detector = null
            modelInfo = null
            closed = true
            Log.i(TAG, "E32_MODEL_CLOSE")
        }
    }
}

package nz.symbiofoundry.robotx

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), RobotTransport {

    companion object {
        private const val TAG = "RobotXEdgeBrain"
        private const val PERMISSION_REQUEST = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
 private const val VOICE_PERMISSION_REQUEST = 1003
        private const val SCAN_MS = 10_000L

        private const val DEFAULT_SPEED = 3
        private const val DEFAULT_DURATION_MS = 500L

        private val SERVICE =
            UUID.fromString("0000f0ff-0000-1000-8000-00805f9b34fb")
        private val WRITE =
            UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        private val NOTIFY =
            UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        private val CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var console: EdgeBrainConsoleView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var connectButton: Button
    private lateinit var forwardButton: Button
    private lateinit var reverseButton: Button
    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private lateinit var stopButton: Button
    private lateinit var controller: RobotController
    private lateinit var aiInput: EditText
 private lateinit var micButton: Button
    private lateinit var aiButton: Button
    private lateinit var aiResult: TextView
    private lateinit var aiProbe: LocalAiProbe

    private lateinit var chatBrain: ChatBrain
 private lateinit var voiceInputController: VoiceInputController
 private var headsetMediaSession: HeadsetMediaSession? = null
    private lateinit var voiceOutputController: VoiceOutputController
    private lateinit var conversationCoordinator: ConversationCoordinator
    private lateinit var knowledgeStore: KnowledgeStore
    private val aiExecutor = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())
    private val txQueue = ArrayDeque<ByteArray>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var writing = false
    private var ready = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: androidx.camera.core.ImageCapture? = null
    private var cameraOn = false
    private var cameraLensFacing = CameraSelector.LENS_FACING_FRONT
    private var latestVisionCaptureFile: java.io.File? = null
    private val visionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var localVisionDetector: LocalVisionDetector? = null


    private val bluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }

    private val scanner
        get() = bluetoothManager.adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: "(unnamed)"
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

            val isRobot =
                name.startsWith("ApitorTX", ignoreCase = true) ||
                uuids.contains(SERVICE)

            if (!isRobot) return

            Log.i(TAG, "APITOR_FOUND")

            stopScan()
            status.text = "Robot X found — connecting..."
            console.setBleState("CONNECTING")
            details.text = "$name\n${result.device.address}"
            connectGatt(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status.text = "BLE scan failed: $errorCode"
            console.setBleState("ERROR")
            connectButton.isEnabled = true
            Log.e(TAG, "BLE_SCAN_FAILED code=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            statusCode: Int,
            newState: Int
        ) {
            Log.i(TAG, "GATT_STATE status=$statusCode newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    status.text = "Connected — discovering services..."
                    console.setBleState("CONNECTING")
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                writeCharacteristic = null
                writing = false
                synchronized(txQueue) { txQueue.clear() }
                controller.onDisconnected()

                runOnUiThread {
                    status.text = "Disconnected"
                    console.setBleState("OFF")
                    console.setControllerState("IDLE")
                    setDriveEnabled(false)
                    connectButton.isEnabled = true
                    connectButton.text = "CONNECT ROBOT X"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    status.text = "Service discovery failed: $statusCode"
                    console.setBleState("ERROR")
                }
                return
            }

            val service = gatt.getService(SERVICE)
            val writeChar = service?.getCharacteristic(WRITE)
            val notifyChar = service?.getCharacteristic(NOTIFY)

            Log.i(
                TAG,
                "APITOR_GATT service=${service != null} write=${writeChar != null} notify=${notifyChar != null}"
            )

            if (writeChar == null || notifyChar == null) {
                runOnUiThread {
                    status.text = "Apitor GATT characteristics missing"
                    console.setBleState("ERROR")
                }
                return
            }

            writeCharacteristic = writeChar

            val localNotify = gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(CCCD)

            if (!localNotify || descriptor == null) {
                runOnUiThread {
                    status.text = "Notification setup failed"
                    console.setBleState("ERROR")
                }
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                runOnUiThread {
                    status.text = "Notification descriptor write failed"
                    console.setBleState("ERROR")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int
        ) {
            if (descriptor.uuid == CCCD && statusCode == BluetoothGatt.GATT_SUCCESS) {
                send(RobotXProtocol.AUTH)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int
        ) {
            val sent = characteristic.value ?: byteArrayOf()
            Log.i(TAG, "TX_COMPLETE")

            writing = false

            if (
                sent.contentEquals(RobotXProtocol.AUTH) &&
                statusCode == BluetoothGatt.GATT_SUCCESS
            ) {
                ready = true
                Log.i(TAG, "APITOR_AUTH_WRITE=PASS")

                runOnUiThread {
                    status.text = "Robot X ready — B4 controller armed"
                    console.setBleState("READY")
                    console.setControllerState("IDLE")
                    connectButton.text = "CONNECTED"
                    connectButton.isEnabled = false
                    setDriveEnabled(true)
                }
            }

            sendNext()
        }

        @Deprecated("Compatibility callback")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            logNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            logNotification(value)
        }
    }

    private val visionObservationCoordinator = VisionObservationCoordinator()
    private var visionCameraTransactionRequestId: String? = null
    private val visionCameraSettleDelayMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        knowledgeStore = KnowledgeStore.open(this)
        val knowledgeCountBeforeBootstrap = knowledgeStore.count()
        knowledgeStore.bootstrap(KnowledgeBootstrap.records())
        val knowledgePhoneRecord = knowledgeStore.getExact("hardware.phone")
        Log.i(TAG, "E26_KNOWLEDGE_READY")

        aiProbe = LocalAiProbe(this)

        chatBrain = ChatBrain(this)
        conversationCoordinator = ConversationCoordinator(
            chatBrain = chatBrain,
            knowledgeRetriever = KnowledgeRetriever(knowledgeStore),
            persistentUserMemory = PersistentUserMemory(knowledgeStore)
        )
        controller = RobotController(handler, this) { event ->
            Log.i(TAG, "CONTROLLER $event")
            runOnUiThread {
                when {
                    event.startsWith("AUTO STOP") ->
                        console.setControllerState("IDLE")
                    event.startsWith("STOP priority") ->
                        console.setControllerState("STOP")
                }
            }
        }

        console = EdgeBrainConsoleView(this)

        status = console.status
        details = console.details
        connectButton = console.connectButton

        forwardButton = console.forwardButton
        reverseButton = console.reverseButton
        leftButton = console.leftButton
        rightButton = console.rightButton
        stopButton = console.stopButton

        aiInput = console.aiInput
 micButton = console.micButton
        aiButton = console.aiButton
        aiResult = console.aiResult
 configureVoiceInput()

        status.text = "Robot X Edge Brain E2.3D"
        details.text =
            "speed ${RobotCommandPolicy.MIN_SPEED}-${RobotCommandPolicy.MAX_SPEED}/12 · " +
            "${RobotCommandPolicy.MIN_DURATION_MS}-${RobotCommandPolicy.MAX_DURATION_MS}ms"

        connectButton.setOnClickListener {
            ensurePermissionsAndConnect()
        }

        forwardButton.setOnClickListener {
            execute(RobotAction.FORWARD)
        }
        reverseButton.setOnClickListener {
            execute(RobotAction.REVERSE)
        }
        leftButton.setOnClickListener {
            execute(RobotAction.TURN_LEFT)
        }
        rightButton.setOnClickListener {
            execute(RobotAction.TURN_RIGHT)
        }
        stopButton.setOnClickListener {
            val result = controller.execute(RobotCommand(RobotAction.STOP))
            console.setControllerState(if (result.accepted) "STOP" else "BLOCKED")
        }

        aiInput.hint = "Tell Robot X what to do…"
        aiInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        aiInput.setOnEditorActionListener { _, actionId, _ ->
            if (
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) {
                submitUnifiedInput(InputSource.KEYBOARD)
                true
            } else {
                false
            }
        }
        aiInput.setOnKeyListener { _, keyCode, event ->
            if (
                keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                submitUnifiedInput(InputSource.KEYBOARD)
                true
            } else {
                false
            }
        }
        aiButton.setOnClickListener { submitUnifiedInput(InputSource.TEXT_BUTTON) }

        if (aiProbe.modelPresent()) {
            console.appendRobotMessage("Local AI ready — bounded controller enabled.")
        } else {
            console.appendRobotMessage("Local AI model missing.")
        }

        console.setCameraState("OFF")
        console.setCameraControls(false)
        console.cameraToggleButton.setOnClickListener {
            if (cameraOn) stopCamera() else startCamera()
        }
        console.cameraFlipButton.setOnClickListener {
            flipCamera()
        }
        console.cameraCaptureButton.setOnClickListener {
            captureStillImage()
        }
        console.cameraAnalyzeButton.setOnClickListener {
            analyzeLatestVisionCapture()
        }
        console.cameraObserveButton.setOnClickListener {
            runObservationDiagnostic()
        }
        console.setAiState(if (aiProbe.modelPresent()) "READY" else "MISSING")
        console.setBleState("OFF")
        console.setControllerState("IDLE")

        setContentView(console)
        Log.i(TAG, "APP_READY ROBOT_X_EDGE_BRAIN_E23D")
val speechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this)
val onDeviceSpeechRecognitionAvailable =
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
} else {
false
}
val recordAudioGranted =
checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
PackageManager.PERMISSION_GRANTED
Log.i(
TAG,
"E24A_SPEECH_CAPABILITY recognitionAvailable=$speechRecognitionAvailable onDeviceAvailable=$onDeviceSpeechRecognitionAvailable recordAudioGranted=$recordAudioGranted sdk=${Build.VERSION.SDK_INT}"
)
        Log.i(TAG, "EDGE_MODEL_INITIALIZED")
        Log.i(TAG, "EDGE_CONTROL_PATH=AI_POLICY_CONTROLLER_BLE")
    }
    /**
     * Unified natural-language entry point.
     *
     * All normal text submission paths converge here before selecting STOP,
     * capability guard, clarification, canonical action, FunctionGemma action
     * interpretation, or ChatBrain conversation.
     *
     * Physical execution authority remains with RobotController.
     */
   private fun configureVoiceInput() {
voiceInputController = VoiceInputController(
context = this,
onStateChanged = { voiceState ->
when (voiceState) {
VoiceInputState.READY -> {
micButton.text = "MIC"
micButton.isEnabled = true
}
VoiceInputState.LISTENING -> {
micButton.text = "MIC STOP"
micButton.isEnabled = true
}
VoiceInputState.PROCESSING -> {
micButton.text = "..."
micButton.isEnabled = false
}
VoiceInputState.DENIED -> {
micButton.text = "MIC"
micButton.isEnabled = true
}
VoiceInputState.UNAVAILABLE -> {
micButton.text = "MIC N/A"
micButton.isEnabled = false
}
}
Log.i(TAG, "E24B_VOICE_STATE state=$voiceState")
},
onFinalTranscript = { transcript ->
aiInput.setText(transcript)
aiInput.setSelection(transcript.length)
Log.i(TAG, "E24C_FINAL_TRANSCRIPT_ROUTING source=VOICE")
submitUnifiedInput(InputSource.VOICE)
},
onError = { message ->
Log.w(TAG, "E24B_VOICE_ERROR")
}
)

    micButton.setOnClickListener {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            voiceInputController.markPermissionAvailable()
            voiceInputController.toggleCapture()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                VOICE_PERMISSION_REQUEST
            )
        }
    }

    voiceOutputController = VoiceOutputController(
        this,
        onState = { state -> Log.i(TAG, "E25_VOICE_OUTPUT_STATE state=$state") },
        onError = { message -> Log.e(TAG, "E25_VOICE_OUTPUT_ERROR") },
    )
    voiceOutputController.initialize()
    voiceInputController.initialize()
    headsetMediaSession = HeadsetMediaSession(this) { source, keyName ->
        requestHeadsetVoiceToggle(source, keyName)
    }
    Log.i(TAG, "RXH_PROD_H1_READY")
}

 private enum class InputSource {
     TEXT_BUTTON,
     KEYBOARD,
     VOICE
 }

 private fun submitUnifiedInput(source: InputSource) {
        val text = aiInput.text.toString().trim()
        if (text.isEmpty()) return

        if (
            text.equals("/vision", ignoreCase = true) ||
            text.startsWith("/vision ", ignoreCase = true)
        ) {
            val query = text.substring(7).trim()
            if (query.isEmpty()) {
                console.appendRobotMessage("VISION TEST needs a question.")
                return
            }

            runVisionNeedDiagnostic(text, query)
            return
        }

        Log.i(TAG, "INPUT_SOURCE_RECEIVED")

        val decision = conversationCoordinator.decide(text)
        Log.i(TAG, "INPUT_ROUTE")

        when (decision.route) {
            ConversationCoordinator.Route.STOP -> {
                console.appendUserMessage(text)
                aiInput.setText("")

                val result = controller.execute(RobotCommand(RobotAction.STOP))
                console.setControllerState(if (result.accepted) "STOP" else "BLOCKED")
                console.appendRobotMessage(
                    if (result.accepted) {
                        "Stopped."
                    } else {
                        "I couldn't send STOP because Robot X is not connected."
                    }
                )
                Log.i(TAG, "UNIFIED_STOP_RESULT")
            }

            ConversationCoordinator.Route.COORDINATOR_GUARD,
            ConversationCoordinator.Route.ACTION_INCOMPLETE -> {
                console.appendUserMessage(text)
                aiInput.setText("")
                console.appendRobotMessage(
                    decision.fixedReply ?: "I need a little more detail."
                )
            }

            ConversationCoordinator.Route.VISION_REQUIRED -> {
                runVisionRequiredDeferred(text)
            }

            ConversationCoordinator.Route.ACTION -> {
                val canonicalAction = decision.canonicalAction
                if (canonicalAction != null) {
                    console.appendUserMessage(text)
                    aiInput.setText("")

                    val action = when (canonicalAction) {
                        ConversationCoordinator.CanonicalAction.FORWARD -> RobotAction.FORWARD
                        ConversationCoordinator.CanonicalAction.REVERSE -> RobotAction.REVERSE
                        ConversationCoordinator.CanonicalAction.TURN_LEFT -> RobotAction.TURN_LEFT
                        ConversationCoordinator.CanonicalAction.TURN_RIGHT -> RobotAction.TURN_RIGHT
                    }

                    Log.i(TAG, "CANONICAL_ACTION")
                    execute(action)
                } else {
                    runAiProbe()
                }
            }

            ConversationCoordinator.Route.CHAT_MODEL -> {
                runTextChat()
            }
        }
    }

    private fun runVisionRequiredDeferred(text: String) {
        aiInput.setText("")
        console.appendUserMessage(text)

        if (!chatBrain.modelPresent()) {
            console.setAiState("MISSING")
            val message = "Chat model missing — I can't answer from visual evidence yet."
            console.appendRobotMessage(message)
            voiceOutputController.speak(message)
            Log.w(TAG, "E34B3_VISION_ABORT")
            return
        }

        aiButton.isEnabled = false
        console.setAiState("BUSY")

        val requestedAt = System.currentTimeMillis()
        val request = VisionRequest(
            requestId = "conversation-$requestedAt",
            camera = VisionCamera.DEFAULT,
            requestedAtEpochMs = requestedAt
        )

        Log.i(TAG, "E34B3_VISION_REQUEST")

        val start = requestVisionObservation(request) { observation ->
            val grounded = GroundedVisionContextRenderer.render(observation)
            val worldContext = GroundedWorldContextBuilder.build(
                userText = text,
                observation = observation,
                grounded = grounded
            )
            val b4cFactualCore =
                worldContext.factualCore.replace("\n", " ").replace("\r", " ").take(500)
            val b4cVerified =
                worldContext.verifiedFacts.joinToString(separator = ";").take(1000)
            val b4cUnknown =
                worldContext.unknownFacts.joinToString(separator = ";").take(1000)
            Log.i(TAG, "E34B4C_WORLD_CONTEXT")
 val b4aVisualFacts =
 observation.detections
 .joinToString(separator = ";") { detection ->
 "${detection.label}:${detection.confidence}"
 }
 .ifBlank { "none" }
 .take(1000)
 val b4aQuestion = text.replace("\n", " ").replace("\r", " ").take(300)
 val b4aGroundedContext = grounded.text.replace("\n", " ").replace("\r", " ").take(1200)
 Log.i(TAG, "E34B4A_VISUAL_FACTS")
 Log.i(TAG, "E34B4A_GROUNDED_INPUT")

            Log.i(TAG, "E34B3_GROUNDED_CONTEXT")

            aiExecutor.execute {
                val result = runCatching {
                    conversationCoordinator.replyGrounded(worldContext)
                }

                runOnUiThread {
                    aiButton.isEnabled = true

                    result.onSuccess { reply ->
                        console.setAiState("READY")
                        console.appendRobotMessage(reply.text)
                        voiceOutputController.speak(reply.text)
                        Log.i(
                            TAG,
                            "E34B3_GROUNDED_REPLY request_id=${observation.requestId} " +
                                "status=${grounded.status.name} route=${reply.route} " +
                                "chat_ms=${reply.inferenceMs}"
                        )
                    }.onFailure { error ->
                        console.setAiState("ERROR")
                        val message = "I couldn't answer from the visual evidence."
                        console.appendRobotMessage(message)
                        voiceOutputController.speak(message)
                        Log.e(TAG, "E34B3_GROUNDED_REPLY_ERROR")
                    }
                }
            }
        }

        if (start is VisionRequestStart.Busy) {
            aiButton.isEnabled = true
            console.setAiState("READY")
            val message = "I can't look right now because another visual observation is active."
            console.appendRobotMessage(message)
            voiceOutputController.speak(message)
            Log.w(
                TAG,
                "E34B3_VISION_BUSY request_id=${request.requestId} " +
                    "active_request_id=${start.activeRequestId} camera_requested=false"
            )
        }
    }

    private fun runVisionNeedDiagnostic(displayText: String, query: String) {
        aiInput.setText("")
        console.appendUserMessage(displayText)

        if (!aiProbe.modelPresent()) {
            console.setAiState("MISSING")
            console.appendRobotMessage("Local AI model missing — vision test not processed.")
            Log.w(TAG, "E34B1B_VISION_MODEL_MISSING")
            return
        }

        console.setAiBusy(true)
        Log.i(TAG, "E34B1B_VISION_DIAGNOSTIC_REQUEST")

        Thread {
            val result = runCatching { aiProbe.inferVisionNeed(query) }

            runOnUiThread {
                result.onSuccess { probe ->
                    console.setAiBusy(false)

                    val decision = probe.decision
                    if (probe.accepted && decision != null) {
                        console.appendRobotMessage(
                            "VISION TEST → ${decision.name} (${probe.inferenceMs} ms)"
                        )
                    } else {
                        console.appendRobotMessage(
                            "VISION TEST → REJECTED: ${probe.message} (${probe.inferenceMs} ms)"
                        )
                    }

                    Log.i(TAG, "E34B1B_VISION_DIAGNOSTIC_RESULT")
                }.onFailure { error ->
                    console.setAiBusy(false)
                    console.setAiState("ERROR")
                    console.appendRobotMessage(
                        "VISION TEST error: ${error.message ?: error.javaClass.simpleName}"
                    )
                    Log.e(TAG, "E34B1B_VISION_DIAGNOSTIC_ERROR")
                }
            }
        }.start()
    }

    private fun runTextChat() {
        val text = aiInput.text.toString().trim()
        if (text.isEmpty()) {
            console.appendRobotMessage("Enter a message first.")
            return
        }

        if (!chatBrain.modelPresent()) {
            console.appendRobotMessage(
                "Chat model missing — SmolLM2 135M is not installed."
            )
            Log.w(TAG, "CHAT_MODEL_MISSING")
            return
        }

        console.appendUserMessage(text)
        aiInput.text.clear()
        aiButton.isEnabled = false
        console.setAiState("BUSY")

        aiExecutor.execute {
            val result = runCatching {
                conversationCoordinator.reply(text)
            }

            runOnUiThread {
                aiButton.isEnabled = true

                result.onSuccess { reply ->
                    console.setAiState("READY")
                    console.appendRobotMessage(reply.text)
                    voiceOutputController.speak(reply.text)
                    Log.i(
                        TAG,
                        "TEXT_CHAT_RESULT route=${reply.route} ms=${reply.inferenceMs}"
                    )
                }.onFailure { error ->
                    console.setAiState("ERROR")
                    console.appendRobotMessage(
                        "Chat error: ${error.message ?: error.javaClass.simpleName}"
                    )
                    Log.e(TAG, "TEXT_CHAT_ERROR")
                }
            }
        }
    }


    private fun runAiProbe() {
        val text = aiInput.text.toString().trim()
        if (text.isBlank()) return

        aiInput.setText("")
        console.appendUserMessage(text)

        if (!aiProbe.modelPresent()) {
            console.setAiState("MISSING")
            console.appendRobotMessage("Local AI model missing — command not processed.")
            Log.w(TAG, "EDGE_MODEL_MISSING")
            return
        }

        console.setAiBusy(true)
        Log.i(TAG, "EDGE_AI_REQUEST")

        Thread {
            val result = runCatching { aiProbe.infer(text) }

            runOnUiThread {
                result.onSuccess { probe ->
                    console.setAiBusy(false)

                    val command = probe.command
                    val structured = command?.toString() ?: "NO_COMMAND"

                    Log.i(
                        TAG,
                        "EDGE_AI_RESULT accepted=${probe.accepted} command=$command elapsedMs=${probe.inferenceMs}"
                    )

                    if (!probe.accepted || command == null) {
                        console.appendRobotMessage(
                            "${probe.message} (${probe.inferenceMs} ms)"
                        )
                    } else if (!ready) {
                        console.appendRobotMessage(
                            "$structured — not executed: Robot X is not BLE-ready. (${probe.inferenceMs} ms)"
                        )
                        Log.i(
                            TAG,
                            "EDGE_EXECUTION_BLOCKED reason=BLE_NOT_READY command=$command"
                        )
                    } else {
                        Log.i(TAG, "EDGE_EXECUTION_BEGIN command=$command")
                        val execution = controller.execute(command)
                        Log.i(TAG, "COMMAND_RESULT")
                        console.setControllerState(
                            when {
                                !execution.accepted -> "BLOCKED"
                                command.action == RobotAction.STOP -> "STOP"
                                else -> "MOVING"
                            }
                        )
                        Log.i(TAG, "EDGE_EXECUTION_RESULT")
                        console.appendRobotMessage(
                            "$structured — ${execution.message} (${probe.inferenceMs} ms)"
                        )
                    }
                }.onFailure { error ->
                    console.setAiState("ERROR")
                    aiButton.isEnabled = true
                    console.appendRobotMessage(
                        "AI error: ${error.message ?: error.javaClass.simpleName}"
                    )
                    Log.e(TAG, "EDGE_AI_ERROR")
                }
            }
        }.start()
    }

    private fun execute(action: RobotAction) {
        val result = controller.execute(
            RobotCommand(
                action = action,
                speed = DEFAULT_SPEED,
                durationMs = DEFAULT_DURATION_MS
            )
        )
        console.setControllerState(
            if (result.accepted) "MOVING" else "BLOCKED"
        )
        Log.i(TAG, "COMMAND_RESULT")
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun setDriveEnabled(enabled: Boolean) {
        forwardButton.isEnabled = enabled
        reverseButton.isEnabled = enabled
        leftButton.isEnabled = enabled
        rightButton.isEnabled = enabled
        stopButton.isEnabled = enabled
    }

    override fun send(packet: ByteArray, priority: Boolean): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (writeCharacteristic == null) return false

        synchronized(txQueue) {
            if (priority) {
                txQueue.clear()
                txQueue.addFirst(packet)
                Log.i(TAG, "TX_PRIORITY")
            } else {
                txQueue.addLast(packet)
            }
        }

        sendNext(gatt)
        return true
    }

    private fun sendNext(gattOverride: BluetoothGatt? = null) {
        if (writing) return

        val gatt = gattOverride ?: bluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return

        val packet = synchronized(txQueue) {
            if (txQueue.isEmpty()) null else txQueue.removeFirst()
        } ?: return

        characteristic.writeType =
            if (
                (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        characteristic.value = packet
        writing = true

        val queued = gatt.writeCharacteristic(characteristic)
        Log.i(TAG, "TX_QUEUE")

        if (!queued) {
            writing = false
            Log.e(TAG, "TX_QUEUE_FAILED")
        }
    }

    private fun startCamera() {
        cameraOn = true
        console.setCameraState("STARTING")
        console.cameraToggleButton.text = "CAM OFF"
        console.cameraFlipButton.isEnabled = false

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
            return
        }

        bindCamera()
    }

    private fun bindCamera(onBound: ((Boolean) -> Unit)? = null) {
        if (!cameraOn) {
            onBound?.invoke(false)
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val result = runCatching {
                val provider = future.get()
                cameraProvider = provider

                val selector = CameraSelector.Builder()
                    .requireLensFacing(cameraLensFacing)
                    .build()

                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                    .also {
                        it.setSurfaceProvider(console.cameraPreview.surfaceProvider)
                    }

                val capture = androidx.camera.core.ImageCapture.Builder()
                    .setCaptureMode(androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                imageCapture = null
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture

                val facing =
                    if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT"
                    else "REAR"

                console.setCameraState("LIVE $facing")
                console.setCameraControls(true)
                Log.i(TAG, "EDGE_CAMERA_LIVE facing=$facing resolutionTarget=640x480")
            }

            result.onSuccess {
                onBound?.invoke(true)
            }.onFailure { error ->
                Log.e(TAG, "EDGE_CAMERA_ERROR")
                cameraProvider?.unbindAll()
                cameraProvider = null
                imageCapture = null
                cameraOn = false
                console.setCameraState("ERROR")
                console.setCameraControls(false)
                onBound?.invoke(false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageCapture = null
        cameraProvider = null
        cameraOn = false
        console.setCameraState("OFF")
        console.setCameraControls(false)
        Log.i(TAG, "EDGE_CAMERA_OFF")
    }

    private enum class StillCaptureFailureReason {
        CAMERA_NOT_READY,
        CAPTURE_DIR_CREATE_FAILED,
        INVALID_OUTPUT,
        IMAGE_CAPTURE_ERROR
    }

    private data class StillCaptureStarted(
        val file: java.io.File,
        val actualCamera: VisionCamera
    )

    private sealed interface StillCaptureResult {
        data class Success(
            val file: java.io.File,
            val actualCamera: VisionCamera,
            val bytes: Long,
            val width: Int,
            val height: Int
        ) : StillCaptureResult

        data class Failure(
            val reason: StillCaptureFailureReason,
            val actualCamera: VisionCamera,
            val path: String? = null,
            val bytes: Long = 0L,
            val width: Int = 0,
            val height: Int = 0,
            val imageCaptureError: Int? = null,
            val message: String? = null,
            val cause: Throwable? = null
        ) : StillCaptureResult
    }

    private fun requestStillCapture(
        onStarted: (StillCaptureStarted) -> Unit = {},
        onComplete: (StillCaptureResult) -> Unit
    ) {
        val capture = imageCapture
        val actualCamera =
            if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) VisionCamera.FRONT
            else VisionCamera.REAR

        if (!cameraOn || capture == null) {
            onComplete(
                StillCaptureResult.Failure(
                    reason = StillCaptureFailureReason.CAMERA_NOT_READY,
                    actualCamera = actualCamera
                )
            )
            return
        }

        val timestamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            java.util.Locale.US
        ).format(java.util.Date())
        val captureDir = java.io.File(
            getExternalFilesDir(null) ?: filesDir,
            "captures"
        )

        if (!captureDir.exists() && !captureDir.mkdirs()) {
            onComplete(
                StillCaptureResult.Failure(
                    reason = StillCaptureFailureReason.CAPTURE_DIR_CREATE_FAILED,
                    actualCamera = actualCamera,
                    path = captureDir.absolutePath
                )
            )
            return
        }

        val facing = actualCamera.name
        val outputFile = java.io.File(
            captureDir,
            "robot_x_${timestamp}_${facing.lowercase(java.util.Locale.US)}.jpg"
        )
        val outputOptions =
            androidx.camera.core.ImageCapture.OutputFileOptions.Builder(outputFile).build()

        onStarted(
            StillCaptureStarted(
                file = outputFile,
                actualCamera = actualCamera
            )
        )

        capture.takePicture(
            outputOptions,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    outputFileResults: androidx.camera.core.ImageCapture.OutputFileResults
                ) {
                    val exists = outputFile.isFile
                    val bytes = if (exists) outputFile.length() else 0L
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    if (exists) {
                        android.graphics.BitmapFactory.decodeFile(
                            outputFile.absolutePath,
                            bounds
                        )
                    }
                    val width = bounds.outWidth
                    val height = bounds.outHeight

                    if (!exists || bytes <= 0L || width <= 0 || height <= 0) {
                        onComplete(
                            StillCaptureResult.Failure(
                                reason = StillCaptureFailureReason.INVALID_OUTPUT,
                                actualCamera = actualCamera,
                                path = outputFile.absolutePath,
                                bytes = bytes,
                                width = width,
                                height = height
                            )
                        )
                        return
                    }

                    onComplete(
                        StillCaptureResult.Success(
                            file = outputFile,
                            actualCamera = actualCamera,
                            bytes = bytes,
                            width = width,
                            height = height
                        )
                    )
                }

                override fun onError(
                    exception: androidx.camera.core.ImageCaptureException
                ) {
                    onComplete(
                        StillCaptureResult.Failure(
                            reason = StillCaptureFailureReason.IMAGE_CAPTURE_ERROR,
                            actualCamera = actualCamera,
                            path = outputFile.absolutePath,
                            imageCaptureError = exception.imageCaptureError,
                            message = exception.message,
                            cause = exception
                        )
                    )
                }
            }
        )
    }

    private fun captureStillImage() {
        requestStillCapture(
            onStarted = { started ->
                console.cameraCaptureButton.isEnabled = false
                Log.i(TAG, "E31_CAPTURE_REQUEST")
            },
            onComplete = { result ->
                console.cameraCaptureButton.isEnabled = cameraOn && imageCapture != null

                when (result) {
                    is StillCaptureResult.Success -> {
                        Log.i(TAG, "E31_CAPTURE_SUCCESS")
                        latestVisionCaptureFile = result.file
                        console.cameraAnalyzeButton.isEnabled = true
                    }

                    is StillCaptureResult.Failure -> {
                        when (result.reason) {
                            StillCaptureFailureReason.CAMERA_NOT_READY -> {
                                Log.w(TAG, "E31_CAPTURE_ERROR")
                            }

                            StillCaptureFailureReason.CAPTURE_DIR_CREATE_FAILED -> {
                                Log.e(TAG, "E31_CAPTURE_ERROR")
                            }

                            StillCaptureFailureReason.INVALID_OUTPUT -> {
                                Log.e(TAG, "E31_CAPTURE_ERROR")
                            }

                            StillCaptureFailureReason.IMAGE_CAPTURE_ERROR -> {
                                Log.e(TAG, "E31_CAPTURE_ERROR")
                            }
                        }
                    }
                }
            }
        )
    }

    private fun runObservationDiagnostic() {
        val requestedAt = System.currentTimeMillis()
        val request = VisionRequest(
            requestId = "observe-$requestedAt",
            camera = VisionCamera.DEFAULT,
            requestedAtEpochMs = requestedAt
        )

        console.cameraObserveButton.isEnabled = false
        console.appendRobotMessage("Observation: requesting default camera...")

        val start = requestVisionObservation(request) { observation ->
            console.cameraObserveButton.isEnabled = true
            val summary =
                if (observation.status == VisionObservationStatus.DETECTED) {
                    val detections = observation.detections.joinToString(", ") { detection ->
                        val percent = kotlin.math.round(detection.confidence * 100f).toInt()
                        "${detection.label} $percent%"
                    }
                    "Observation: DETECTED — $detections"
                } else if (observation.status == VisionObservationStatus.EMPTY) {
                    "Observation: EMPTY — no accepted detections"
                } else {
                    "Observation: ${observation.status.name}" +
                        (observation.errorCode?.let { " — $it" } ?: "")
                }
            console.appendRobotMessage(summary)
            Log.i(
                TAG,
                "E33A4_OBSERVE_DIAGNOSTIC request_id=${observation.requestId} " +
                    "status=${observation.status.name} " +
                    "camera=${observation.actualCamera?.name} " +
                    "detections=${observation.detections.size}"
            )
        }

        if (start is VisionRequestStart.Busy) {
            console.cameraObserveButton.isEnabled = true
            console.appendRobotMessage(
                "Observation: BUSY — request ${start.activeRequestId} is active"
            )
        }
    }

    private fun requestVisionObservation(
        request: VisionRequest,
        onComplete: (VisionObservation) -> Unit
    ): VisionRequestStart {
        visionCameraTransactionRequestId?.let { activeRequestId ->
            val busy = VisionRequestStart.Busy(request, activeRequestId)
            Log.w(
                TAG,
                "E33A4_CAMERA_TRANSACTION_BUSY request_id=${request.requestId} " +
                    "active_request_id=$activeRequestId"
            )
            return busy
        }

        val start = visionObservationCoordinator.begin(request)

        if (start is VisionRequestStart.Busy) {
            Log.w(
                TAG,
                "E33A3_OBSERVATION_BUSY request_id=${request.requestId} " +
                    "active_request_id=${start.activeRequestId}"
            )
            return start
        }

        start as VisionRequestStart.Accepted
        visionCameraTransactionRequestId = request.requestId
        console.setCameraControlsLocked(true)

        val priorCameraOn = cameraOn
        val priorLensFacing = cameraLensFacing
        val targetLensFacing =
            when (start.actualCamera) {
                VisionCamera.FRONT -> CameraSelector.LENS_FACING_FRONT
                VisionCamera.REAR -> CameraSelector.LENS_FACING_BACK
                VisionCamera.DEFAULT -> CameraSelector.LENS_FACING_FRONT
            }

        fun lensName(lensFacing: Int): String =
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "REAR"

        Log.i(
            TAG,
            "E33A4_CAMERA_TRANSACTION_START request_id=${request.requestId} " +
                "requested_camera=${request.camera.name} " +
                "resolved_camera=${start.actualCamera.name} " +
                "prior_on=$priorCameraOn prior_camera=${lensName(priorLensFacing)}"
        )

        fun deliverAfterRestore(observation: VisionObservation) {
            fun deliver(restored: Boolean) {
                Log.i(
                    TAG,
                    "E33A4_CAMERA_TRANSACTION_COMPLETE request_id=${request.requestId} " +
                        "status=${observation.status.name} restored=$restored " +
                        "camera_on=$cameraOn camera=${lensName(cameraLensFacing)}"
                )
                visionCameraTransactionRequestId = null
                console.setCameraControlsLocked(false)
                onComplete(observation)
            }

            if (!priorCameraOn) {
                cameraLensFacing = priorLensFacing
                stopCamera()
                Log.i(
                    TAG,
                    "E33A4_CAMERA_RESTORE request_id=${request.requestId} target=OFF"
                )
                deliver(true)
                return
            }

            if (
                cameraOn &&
                cameraLensFacing == priorLensFacing &&
                imageCapture != null
            ) {
                Log.i(
                    TAG,
                    "E33A4_CAMERA_RESTORE request_id=${request.requestId} " +
                        "target=${lensName(priorLensFacing)} action=UNCHANGED"
                )
                deliver(true)
                return
            }

            cameraLensFacing = priorLensFacing
            cameraOn = true
            console.setCameraState("STARTING")
            Log.i(
                TAG,
                "E33A4_CAMERA_RESTORE request_id=${request.requestId} " +
                    "target=${lensName(priorLensFacing)} action=REBIND"
            )
            bindCamera { restored ->
                if (!restored) {
                    Log.e(
                        TAG,
                        "E33A4_CAMERA_RESTORE_FAILED request_id=${request.requestId} " +
                            "target=${lensName(priorLensFacing)}"
                    )
                }
                deliver(restored)
            }
        }

        fun captureAndAnalyze() {
            requestStillCapture(
                onComplete = { captureResult ->
                    when (captureResult) {
                        is StillCaptureResult.Success -> {
                            val capturedAtEpochMs = System.currentTimeMillis()
                            val actualStart = start.copy(
                                actualCamera = captureResult.actualCamera
                            )

                            if (actualStart.actualCamera != start.actualCamera) {
                                Log.i(
                                    TAG,
                                    "E33A3_CAMERA_MISMATCH request_id=${request.requestId} " +
                                        "resolved=${start.actualCamera.name} " +
                                        "captured=${actualStart.actualCamera.name}"
                                )
                            }

                            visionExecutor.execute {
                                try {
                                    val detector = localVisionDetector
                                        ?: LocalVisionDetector(applicationContext).also {
                                            localVisionDetector = it
                                        }
                                    val inference = detector.analyze(captureResult.file)
                                    val observation =
                                        visionObservationCoordinator.completeFromInference(
                                            start = actualStart,
                                            inference = inference,
                                            capturedAtEpochMs = capturedAtEpochMs
                                        )

                                    Log.i(
                                        TAG,
                                        "E33A3_OBSERVATION_RESULT " +
                                            "request_id=${request.requestId} " +
                                            "status=${observation.status.name} " +
                                            "camera=${observation.actualCamera?.name} " +
                                            "detections=${observation.detections.size} " +
                                            "inference_ms=${observation.inferenceMs}"
                                    )

                                    runOnUiThread {
                                        deliverAfterRestore(observation)
                                    }
                                } catch (error: Exception) {
                                    Log.e(TAG, "E33A3_OBSERVATION_ANALYSIS_ERROR")

                                    val observation =
                                        visionObservationCoordinator.completeFailure(
                                            start = actualStart,
                                            status = VisionObservationStatus.ANALYSIS_FAILED,
                                            errorCode = "LOCAL_VISION_ANALYSIS_FAILED"
                                        )

                                    runOnUiThread {
                                        deliverAfterRestore(observation)
                                    }
                                }
                            }
                        }

                        is StillCaptureResult.Failure -> {
                            val actualStart = start.copy(
                                actualCamera = captureResult.actualCamera
                            )
                            val failure = when (captureResult.reason) {
                                StillCaptureFailureReason.CAMERA_NOT_READY ->
                                    VisionObservationStatus.CAMERA_UNAVAILABLE to
                                        "CAMERA_NOT_READY"

                                StillCaptureFailureReason.CAPTURE_DIR_CREATE_FAILED ->
                                    VisionObservationStatus.CAPTURE_FAILED to
                                        "CAPTURE_DIR_CREATE_FAILED"

                                StillCaptureFailureReason.INVALID_OUTPUT ->
                                    VisionObservationStatus.INVALID_CAPTURE to
                                        "INVALID_OUTPUT"

                                StillCaptureFailureReason.IMAGE_CAPTURE_ERROR ->
                                    VisionObservationStatus.CAPTURE_FAILED to
                                        "IMAGE_CAPTURE_ERROR"
                            }

                            val observation =
                                visionObservationCoordinator.completeFailure(
                                    start = actualStart,
                                    status = failure.first,
                                    errorCode = failure.second
                                )

                            Log.w(
                                TAG,
                                "E33A3_OBSERVATION_CAPTURE_ERROR " +
                                    "request_id=${request.requestId} " +
                                    "status=${observation.status.name} " +
                                    "error_code=${observation.errorCode} " +
                                    "camera=${observation.actualCamera?.name}"
                            )
                            deliverAfterRestore(observation)
                        }
                    }
                }
            )
        }

        val requestedCameraReady =
            cameraOn &&
                cameraLensFacing == targetLensFacing &&
                imageCapture != null

        if (requestedCameraReady) {
            Log.i(
                TAG,
                "E33A4_CAMERA_ACQUIRE request_id=${request.requestId} " +
                    "target=${start.actualCamera.name} action=UNCHANGED"
            )
            captureAndAnalyze()
        } else {
            cameraLensFacing = targetLensFacing
            cameraOn = true
            console.setCameraState("STARTING")
            Log.i(
                TAG,
                "E33A4_CAMERA_ACQUIRE request_id=${request.requestId} " +
                    "target=${start.actualCamera.name} action=REBIND"
            )
            bindCamera { bound ->
                if (bound) {
                    Log.i(
                        TAG,
                        "E34B01_CAMERA_SETTLE_START request_id=${request.requestId} " +
                            "camera=${start.actualCamera.name} delay_ms=$visionCameraSettleDelayMs"
                    )
                    console.cameraPreview.postDelayed(
                        {
                            Log.i(
                                TAG,
                                "E34B01_CAMERA_SETTLE_COMPLETE request_id=${request.requestId} " +
                                    "camera=${start.actualCamera.name} delay_ms=$visionCameraSettleDelayMs"
                            )
                            captureAndAnalyze()
                        },
                        visionCameraSettleDelayMs
                    )
                } else {
                    val observation =
                        visionObservationCoordinator.completeFailure(
                            start = start,
                            status = VisionObservationStatus.CAMERA_UNAVAILABLE,
                            errorCode = "CAMERA_BIND_FAILED"
                        )
                    Log.w(
                        TAG,
                        "E33A4_CAMERA_ACQUIRE_FAILED request_id=${request.requestId} " +
                            "target=${start.actualCamera.name}"
                    )
                    deliverAfterRestore(observation)
                }
            }
        }

        return start
    }

    private fun analyzeLatestVisionCapture() {
        val imageFile = latestVisionCaptureFile
        if (imageFile == null || !imageFile.isFile || imageFile.length() <= 0L) {
            Log.w(TAG, "E32A3_ANALYZE_ERROR")
            console.appendRobotMessage("Vision: capture an image before analysis.")
            console.cameraAnalyzeButton.isEnabled = false
            return
        }

        console.cameraAnalyzeButton.isEnabled = false
        Log.i(TAG, "E32A3_ANALYZE_START bytes=${imageFile.length()}")

        visionExecutor.execute {
            try {
                val detector = localVisionDetector
                    ?: LocalVisionDetector(applicationContext).also { localVisionDetector = it }
                val result = detector.analyze(imageFile)
                val summary =
                    if (result.detections.isEmpty()) {
                        "Vision: no accepted detections."
                    } else {
                        result.detections.joinToString(
                            prefix = "Vision: ",
                            separator = ", "
                        ) { detection ->
                            "${detection.label} ${(detection.confidence * 100.0f).toInt()}%"
                        }
                    }

                Log.i(TAG, "E32A3_ANALYZE_RESULT")

                runOnUiThread {
                    console.appendRobotMessage(summary)
                    console.cameraAnalyzeButton.isEnabled =
                        latestVisionCaptureFile?.let { it.isFile && it.length() > 0L } == true
                }
            } catch (error: Exception) {
                Log.e(TAG, "E32A3_ANALYZE_ERROR")
                runOnUiThread {
                    console.appendRobotMessage("Vision analysis failed.")
                    console.cameraAnalyzeButton.isEnabled =
                        latestVisionCaptureFile?.let { it.isFile && it.length() > 0L } == true
                }
            }
        }
    }

    private fun flipCamera() {
        if (!cameraOn) return

        cameraLensFacing =
            if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }

        console.setCameraState("STARTING")
        console.cameraFlipButton.isEnabled = false
        Log.i(
            TAG,
            "EDGE_CAMERA_FLIP target=" +
                if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT"
                else "REAR"
        )
        bindCamera()
    }

    private fun ensurePermissionsAndConnect() {
        val required = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED
            ) required += Manifest.permission.BLUETOOTH_SCAN

            if (
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) required += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) required += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (required.isNotEmpty()) {
            requestPermissions(required.toTypedArray(), PERMISSION_REQUEST)
        } else {
            beginRobotScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

       if (requestCode == VOICE_PERMISSION_REQUEST) {
if (
grantResults.isNotEmpty() &&
grantResults[0] == PackageManager.PERMISSION_GRANTED
) {
voiceInputController.markPermissionAvailable()
Log.i(TAG, "E24B_RECORD_AUDIO_PERMISSION=GRANTED")
if (voiceInputController.currentState() == VoiceInputState.READY) {
voiceInputController.toggleCapture()
}
} else {
voiceInputController.markPermissionDenied()
Log.w(TAG, "E24B_RECORD_AUDIO_PERMISSION=DENIED")
}
return
}

 if (requestCode == CAMERA_PERMISSION_REQUEST) {
            val granted =
                grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED

            if (granted && cameraOn) {
                Log.i(TAG, "EDGE_CAMERA_PERMISSION=GRANTED")
                bindCamera()
            } else {
                cameraOn = false
                console.setCameraState("ERROR")
                console.setCameraControls(false)
                Log.w(TAG, "EDGE_CAMERA_PERMISSION=DENIED")
            }
            return
        }

        if (
            requestCode == PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            beginRobotScan()
        } else {
            status.text = "Nearby devices permission required"
            console.setBleState("ERROR")
        }
    }

    private fun beginRobotScan() {
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            status.text = "Bluetooth is not enabled"
            console.setBleState("OFF")
            return
        }

        controller.onDisconnected()
        synchronized(txQueue) { txQueue.clear() }
        writing = false

        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        ready = false

        status.text = "Scanning for Robot X..."
        console.setBleState("SCANNING")
        console.setControllerState("IDLE")
        connectButton.isEnabled = false
        scanning = true

        Log.i(TAG, "BLE_SCAN_START B4")
        scanner?.startScan(scanCallback)

        handler.postDelayed({
            if (scanning) {
                stopScan()
                status.text = "Robot X not found"
                console.setBleState("OFF")
                connectButton.isEnabled = true
            }
        }, SCAN_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        scanning = false
    }

    private fun connectGatt(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(
            this,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun logNotification(value: ByteArray?) {
        if (value != null) {
            Log.d(TAG, "RX")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    private fun requestHeadsetVoiceToggle(source: String, keyName: String) {
        val currentState = voiceInputController.currentState()
        Log.i(TAG, "RXH_PROD_LISTEN_TOGGLE_REQUEST")

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            voiceInputController.markPermissionAvailable()
            voiceInputController.toggleCapture()
            Log.i(TAG, "RXH_PROD_LISTEN_TOGGLE_APPLIED")
        } else {
            Log.i(TAG, "RXH_PROD_LISTEN_TOGGLE_PERMISSION_REQUEST")
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                VOICE_PERMISSION_REQUEST
            )
        }
    }

    override fun onDestroy() {
        visionExecutor.shutdownNow()
        localVisionDetector?.close()
        localVisionDetector = null
        headsetMediaSession?.close()
        headsetMediaSession = null
        knowledgeStore.close()
 voiceOutputController.close()
        voiceInputController.close()
        stopCamera()
        controller.forceStop("activity_destroy")
        aiProbe.close()
        chatBrain.close()
        aiExecutor.shutdownNow()
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        super.onDestroy()
    }
}

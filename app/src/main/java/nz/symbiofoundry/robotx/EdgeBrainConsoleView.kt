package nz.symbiofoundry.robotx

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.view.PreviewView

/**
 * D0 operator-console shell.
 *
 * View/layout responsibility only.
 * No BLE, AI, policy or controller logic belongs in this class.
 */
class EdgeBrainConsoleView(context: Context) : LinearLayout(context) {

    val cameraContainer = FrameLayout(context)
    val cameraPreview = PreviewView(context)
    val cameraMessage = TextView(context)
    val cameraToggleButton = Button(context)
    val cameraFlipButton = Button(context)
    val cameraCaptureButton = Button(context)
    val cameraAnalyzeButton = Button(context)
    val cameraObserveButton = Button(context).apply {
        text = "OBSERVE"
        isEnabled = false
    }

    val cameraIndicator = indicator("CAM", "OFF")
    val aiIndicator = indicator("AI", "READY")
    val bleIndicator = indicator("BLE", "OFF")
    val controllerIndicator = indicator("CTRL", "IDLE")

    val status = TextView(context)
    val details = TextView(context)

    val connectButton = Button(context)

    val forwardButton = Button(context)
    val reverseButton = Button(context)
    val leftButton = Button(context)
    val rightButton = Button(context)
    val stopButton = Button(context)

    val aiInput = EditText(context)
 val micButton = Button(context)
    val aiButton = Button(context)
    val aiResult = TextView(context)

    private val chatEntries = mutableListOf<ChatEntry>()
    private lateinit var historyScroll: ScrollView

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(18, 20, 24))

        addView(buildCameraPanel(), weighted(heightWeight = 3.0f))
        addView(buildBrainPanel(), weighted(heightWeight = 4.5f))
        addView(buildControlPanel(), weighted(heightWeight = 2.5f))
    }

    private fun buildCameraPanel(): View {
        return FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)

            cameraContainer.id = View.generateViewId()
            cameraContainer.setBackgroundColor(Color.rgb(8, 10, 12))

            cameraPreview.apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            cameraContainer.addView(
                cameraPreview,
                FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            )

            cameraMessage.apply {
                text = "CAMERA OFF"
                setTextColor(Color.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            cameraContainer.addView(
                cameraMessage,
                FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            )

            addView(
                cameraContainer,
                FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            )

            addView(
                TextView(context).apply {
                    text = "PERCEPTION"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12), dp(8), dp(12), 0)
                },
                FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )

            val cameraControls = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                cameraToggleButton.apply {
                    text = "CAM ON"
                    minWidth = 0
                    minimumWidth = 0
                }

                cameraFlipButton.apply {
                    text = "FLIP"
                    minWidth = 0
                    minimumWidth = 0
                    isEnabled = false
                }
                cameraCaptureButton.apply {
                    text = "CAPTURE"
                    minWidth = 0
                    minimumWidth = 0
                    isEnabled = false
                }
                cameraAnalyzeButton.apply {
                    text = "ANALYZE"
                    minWidth = 0
                    minimumWidth = 0
                    isEnabled = false
                }

                addView(
                    cameraToggleButton,
                    LinearLayout.LayoutParams(dp(100), dp(42))
                )
                addView(
                    cameraFlipButton,
                    LinearLayout.LayoutParams(dp(80), dp(42))
                )
                addView(
                    cameraCaptureButton,
                    LinearLayout.LayoutParams(dp(100), dp(42))
                )
                addView(
                    cameraAnalyzeButton,
                    LinearLayout.LayoutParams(dp(80), dp(42))
                )
                addView(
                    cameraObserveButton,
                    LinearLayout.LayoutParams(dp(80), dp(42))
                )
            }

            addView(
                cameraControls,
                FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dp(4)
                    rightMargin = dp(6)
                }
            )
        }
    }

    private fun buildBrainPanel(): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(27, 30, 36))

            addView(
                TextView(context).apply {
                    text = "EDGE BRAIN E2.3D"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )

            historyScroll = ScrollView(context).apply {
                isFillViewport = true
                aiResult.setTextColor(Color.rgb(225, 228, 232))
                aiResult.textSize = 15f
                aiResult.setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(
                    aiResult,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            addView(
                historyScroll,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            )

            val inputRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                aiInput.hint = "Tell Robot X what to do…"
                aiInput.setSingleLine(true)
                aiInput.setTextColor(Color.WHITE)
                aiInput.setHintTextColor(Color.GRAY)

 micButton.text = "MIC"
 micButton.minWidth = 0
 micButton.minimumWidth = 0

                aiButton.text = "SEND"
                aiButton.minWidth = 0
                aiButton.minimumWidth = 0

                addView(
                    aiInput,
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
 micButton,
 LayoutParams(dp(72), LayoutParams.WRAP_CONTENT)
 )
 addView(
                    aiButton,
                    LayoutParams(dp(86), LayoutParams.WRAP_CONTENT)
                )
            }

            addView(
                inputRow,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun buildControlPanel(): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.rgb(22, 24, 29))

            val indicatorRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER

                addView(cameraIndicator, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(aiIndicator, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(bleIndicator, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(controllerIndicator, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(indicatorRow)

            val connectionRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                status.apply {
                    text = "Robot X Edge Brain E2.3D"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    maxLines = 1
                }

                details.apply {
                    setTextColor(Color.LTGRAY)
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.END
                }

                connectButton.apply {
                    text = "CONNECT"
                    minWidth = 0
                    minimumWidth = 0
                }

                addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f))
                addView(details, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(connectButton, LayoutParams(dp(100), LayoutParams.WRAP_CONTENT))
            }
            addView(connectionRow)

            val pad = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER

                forwardButton.apply { text = "▲" }
                reverseButton.apply { text = "▼" }
                leftButton.apply { text = "◀" }
                rightButton.apply { text = "▶" }
                stopButton.apply {
                    text = "■"
                    contentDescription = "STOP"
                }

                val rowTop = LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(forwardButton, padButtonParams())
                }

                val rowMiddle = LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(leftButton, padButtonParams())
                    addView(stopButton, padButtonParams())
                    addView(rightButton, padButtonParams())
                }

                val rowBottom = LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(reverseButton, padButtonParams())
                }

                addView(rowTop)
                addView(rowMiddle)
                addView(rowBottom)
            }

            addView(
                pad,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
    }

    fun appendUserMessage(text: String) {
        appendChat(ChatEntry(ChatRole.USER, text))
    }

    fun appendRobotMessage(text: String) {
        appendChat(ChatEntry(ChatRole.ROBOT, text))
    }

    fun setAiBusy(busy: Boolean) {
        aiButton.isEnabled = !busy
        setAiState(if (busy) "BUSY" else "READY")
    }

    private fun appendChat(entry: ChatEntry) {
        chatEntries += entry
        aiResult.text = chatEntries.joinToString("\n\n") {
            val label = when (it.role) {
                ChatRole.USER -> "YOU"
                ChatRole.ROBOT -> "ROBOT"
            }
            "$label  ${it.text}"
        }
        historyScroll.post {
            historyScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun setCameraState(state: String) {
        cameraIndicator.text = "CAM ● $state"
        val live = state.startsWith("LIVE")
        cameraMessage.visibility = if (live) View.GONE else View.VISIBLE
        cameraMessage.text = when {
            state == "STARTING" -> "CAMERA STARTING…"
            state == "ERROR" -> "CAMERA ERROR"
            else -> "CAMERA OFF"
        }
    }

    private var cameraControlsLocked = false
    private var cameraControlsCameraOn = false

    private fun refreshCameraControls() {
        cameraToggleButton.text = if (cameraControlsCameraOn) "CAM OFF" else "CAM ON"
        cameraToggleButton.isEnabled = !cameraControlsLocked
        cameraFlipButton.isEnabled = cameraControlsCameraOn && !cameraControlsLocked
        cameraCaptureButton.isEnabled = cameraControlsCameraOn && !cameraControlsLocked
        cameraObserveButton.isEnabled = !cameraControlsLocked
    }

    fun setCameraControls(cameraOn: Boolean) {
        cameraControlsCameraOn = cameraOn
        refreshCameraControls()
    }

    fun setCameraControlsLocked(locked: Boolean) {
        cameraControlsLocked = locked
        refreshCameraControls()
    }

    fun setAiState(state: String) {
        aiIndicator.text = "AI ● $state"
    }

    fun setBleState(state: String) {
        bleIndicator.text = "BLE ● $state"
    }

    fun setControllerState(state: String) {
        controllerIndicator.text = "CTRL ● $state"
    }

    private fun indicator(name: String, state: String): TextView =
        TextView(context).apply {
            text = "$name ● $state"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

    private fun weighted(heightWeight: Float): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, 0, heightWeight)

    private fun padButtonParams(): LayoutParams =
        LayoutParams(dp(64), dp(40)).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

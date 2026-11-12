package nz.symbiofoundry.robotx

import android.os.Handler

data class ControllerResult(
    val accepted: Boolean,
    val message: String
)

class RobotController(
    private val handler: Handler,
    private val transport: RobotTransport,
    private val eventSink: (String) -> Unit = {}
) {
    private var active = false
    private var scheduledStop: Runnable? = null

    fun isActive(): Boolean = active

    fun execute(command: RobotCommand): ControllerResult {
        val validation = RobotCommandPolicy.validate(command)
        if (!validation.accepted) {
            val message = "REJECTED ${command.action}: ${validation.reason}"
            eventSink(message)
            return ControllerResult(false, message)
        }

        if (command.action == RobotAction.STOP) {
            val sent = forceStop("manual")
            val message = if (sent) "STOP sent" else "STOP unavailable — no BLE transport"
            eventSink(message)
            return ControllerResult(sent, message)
        }

        if (active) {
            val message = "REJECTED ${command.action}: another action is active"
            eventSink(message)
            return ControllerResult(false, message)
        }

        val packets = calibratedPackets(command.action, command.speed)
            ?: return ControllerResult(false, "Unsupported action ${command.action}")

        active = true
        eventSink(
            "${command.action} — speed ${command.speed}/12 for ${command.durationMs}ms"
        )

        val firstQueued = transport.send(packets.first)
        val secondQueued = transport.send(packets.second)

        if (!firstQueued || !secondQueued) {
            active = false
            transport.send(RobotXProtocol.STOP_ALL, priority = true)
            val message = "TRANSPORT FAILURE ${command.action} — STOP prioritized"
            eventSink(message)
            return ControllerResult(false, message)
        }

        val stopTask = Runnable {
            transport.send(RobotXProtocol.STOP_ALL, priority = true)
            active = false
            scheduledStop = null
            eventSink("AUTO STOP — controller ready")
        }

        scheduledStop = stopTask
        handler.postDelayed(stopTask, command.durationMs)

        return ControllerResult(true, "accepted")
    }

    fun forceStop(reason: String): Boolean {
        scheduledStop?.let(handler::removeCallbacks)
        scheduledStop = null
        active = false

        val sent = transport.send(RobotXProtocol.STOP_ALL, priority = true)
        eventSink("STOP priority reason=$reason sent=$sent")
        return sent
    }

    fun onDisconnected() {
        scheduledStop?.let(handler::removeCallbacks)
        scheduledStop = null
        active = false
        eventSink("Controller reset — BLE disconnected")
    }

    private fun calibratedPackets(
        action: RobotAction,
        speed: Int
    ): Pair<ByteArray, ByteArray>? =
        when (action) {
            RobotAction.FORWARD -> Pair(
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M1,
                    RobotXProtocol.DIRECTION_1,
                    speed
                ),
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M2,
                    RobotXProtocol.DIRECTION_2,
                    speed
                )
            )

            RobotAction.REVERSE -> Pair(
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M1,
                    RobotXProtocol.DIRECTION_2,
                    speed
                ),
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M2,
                    RobotXProtocol.DIRECTION_1,
                    speed
                )
            )

            RobotAction.TURN_LEFT -> Pair(
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M1,
                    RobotXProtocol.DIRECTION_2,
                    speed
                ),
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M2,
                    RobotXProtocol.DIRECTION_2,
                    speed
                )
            )

            RobotAction.TURN_RIGHT -> Pair(
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M1,
                    RobotXProtocol.DIRECTION_1,
                    speed
                ),
                RobotXProtocol.motor(
                    RobotXProtocol.MOTOR_M2,
                    RobotXProtocol.DIRECTION_1,
                    speed
                )
            )

            RobotAction.STOP -> null
        }
}

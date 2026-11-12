package nz.symbiofoundry.robotx

enum class RobotAction {
    FORWARD,
    REVERSE,
    TURN_LEFT,
    TURN_RIGHT,
    STOP
}

data class RobotCommand(
    val action: RobotAction,
    val speed: Int = 3,
    val durationMs: Long = 500L
)

data class CommandValidation(
    val accepted: Boolean,
    val reason: String
)

object RobotCommandPolicy {
    const val MIN_SPEED = 1
    const val MAX_SPEED = 5
    const val MIN_DURATION_MS = 100L
    const val MAX_DURATION_MS = 1000L

    fun validate(command: RobotCommand): CommandValidation {
        if (command.action == RobotAction.STOP) {
            return CommandValidation(true, "STOP always permitted")
        }

        if (command.speed !in MIN_SPEED..MAX_SPEED) {
            return CommandValidation(
                false,
                "speed ${command.speed} outside $MIN_SPEED..$MAX_SPEED"
            )
        }

        if (command.durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
            return CommandValidation(
                false,
                "duration ${command.durationMs}ms outside " +
                    "$MIN_DURATION_MS..${MAX_DURATION_MS}ms"
            )
        }

        return CommandValidation(true, "accepted")
    }
}

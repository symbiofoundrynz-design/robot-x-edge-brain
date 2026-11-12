package nz.symbiofoundry.robotx

enum class ChatRole {
    USER,
    ROBOT
}

data class ChatEntry(
    val role: ChatRole,
    val text: String
)

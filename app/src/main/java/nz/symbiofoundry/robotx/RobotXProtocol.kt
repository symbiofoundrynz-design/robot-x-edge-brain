package nz.symbiofoundry.robotx

object RobotXProtocol {
    const val MOTOR_M1 = 0x06
    const val MOTOR_M2 = 0x07

    const val DIRECTION_1 = 0x01
    const val DIRECTION_2 = 0x02

    val AUTH = byteArrayOf(
        0x55, 0xAA.toByte(), 0x11, 0x20,
        0x55, 0x49, 0x4D, 0x38, 0x4C, 0x56,
        0x79, 0x52, 0x6E, 0x75, 0x70, 0x69,
        0x73, 0x65, 0x42, 0x76
    )

    fun motor(motor: Int, direction: Int, speed: Int): ByteArray =
        byteArrayOf(
            0x55,
            0xAA.toByte(),
            0x03,
            motor.toByte(),
            direction.toByte(),
            speed.toByte()
        )

    val STOP_ALL = byteArrayOf(
        0x55, 0xAA.toByte(), 0x03, 0x10, 0x00, 0x00
    )
}

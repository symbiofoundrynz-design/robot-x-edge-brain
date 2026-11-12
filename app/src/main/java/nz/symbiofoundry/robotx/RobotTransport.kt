package nz.symbiofoundry.robotx

interface RobotTransport {
    /**
     * Queue a protocol packet for BLE transmission.
     *
     * priority=true is reserved for STOP. Implementations must discard
     * pending movement packets and place the priority packet at the head
     * of the remaining queue.
     */
    fun send(packet: ByteArray, priority: Boolean = false): Boolean
}

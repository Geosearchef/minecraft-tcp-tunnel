package common

import java.nio.ByteBuffer

var CURRENT_SEQUENCE_NUMBER = 0L

class Packet(var type: Char = '-', var payload: ByteArray = ByteArray(1) { 0 }, var sequence: Long = -1, var ack: Long = -1, generateSequence: Boolean = false) {

    val length: Int get() = 4 + 8 + 8 + 1 + payload.size  // including length

    init {
        if(generateSequence) {
            CURRENT_SEQUENCE_NUMBER++
            sequence = CURRENT_SEQUENCE_NUMBER
        }
    }

    /**
     * @return: bytes consumed
     */
    fun fromByteArray(packet: ByteArray): Int {
        val buffer = ByteBuffer.wrap(packet)

        val length = buffer.readInt()
        sequence = buffer.readLong()
        ack = buffer.readLong()
        type = buffer.read().toChar()

        payload = ByteArray(length - 4 - 8 - 8 - 1)
        buffer.get(payload, 0, payload.size)

        return length
    }

    fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(length).apply {
            putInt(length)
            putLong(sequence)
            putLong(ack)
            put(type.toByte())
            put(payload, 0, payload.size)
        }.array()
    }

    companion object {
//        fun fromByteArray(packet : ByteArray) = Packet().apply { fromByteArray(packet) }
        fun containsPacket(data: ByteArray): Boolean {
            if(data.size < 4) {
                return false
            }
            val nextPacketLength = ByteBuffer.wrap(data).readInt()
            return nextPacketLength <= data.size
        }

        const val DATA_PACKET = 'd'
        const val CREATE_PACKET = 'c'
        const val QUIT_PACKET = 'q'
        const val ACK_PACKET = 'a'
    }
}

private fun ByteBuffer.readInt() = int
private fun ByteBuffer.readLong() = long
private fun ByteBuffer.read() = get()

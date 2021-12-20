package server

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

class ClientSocketWrapper(val targetHost: String, val targetPort: Int, val connectCallback: () -> Unit, val dataReceivedCallback: (ByteArray) -> Unit, val disconnectExperiencedCallback: () -> Unit) {

    var socket: Socket
    var input: DataInputStream
    var output: DataOutputStream
    var listener: Thread

    @Volatile
    var connected: Boolean = true

    init {
        Logger.debug("Connecting socket to $targetHost:$targetPort")
        socket = Socket(targetHost, targetPort)
        Logger.debug("Connected socket to $targetHost:$targetPort")
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())

        listener = Thread(this::listener).apply { start() }

        connectCallback()
    }

    fun listener() {
        val buffer = ByteArray(16777216) // 16 MB, enough?

        while(! socket.isClosed) {
            try {
                val bytesRead = input.read(buffer, 0, buffer.size)
                if(bytesRead == buffer.size) {
                    Logger.debug("Full buffer size used, buffer too small?")
                }
                if(bytesRead == -1) {
                    continue
                }

                val copiedBuffer = ByteArray(bytesRead)
                System.arraycopy(buffer, 0, copiedBuffer, 0, bytesRead)

                dataReceivedCallback(copiedBuffer)
            } catch(e: IOException) {
                Logger.debug("Error while reading from remote wrapper socket", e)
            }
        }

        if(connected) {
            disconnectExperiencedCallback()
        }
        disconnect()
    }

    fun sendData(data: ByteArray) {
        try {
            output.run {
                write(data, 0, data.size)
                flush()
            }
        } catch(e: IOException) {
            Logger.debug("Error while writing to remote wrapper socket, closing...", e)
            disconnect()
        }
    }

    fun disconnect() {
        connected = false
        try {
            input.close()
            output.close()
            if(!socket.isClosed) {
                socket.close()
            }
        } catch(e: IOException) { Logger.debug("Error while closing socket", e) }
    }

}
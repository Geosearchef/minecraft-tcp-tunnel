package client

import Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class ServerSocketWrapper(val port: Int, val connectCallback: () -> Unit, val dataReceivedCallback: (ByteArray) -> Unit, val disconnectRequestedCallback: () -> Unit, val bindAddress: String? = null) {

    init {
        Logger.debug("Binding local tcp socket to 127.0.0.1:$port")
    }

    val serverSocket = if(bindAddress != null) ServerSocket(port, 10, InetAddress.getByName(bindAddress)) else ServerSocket(port)
    val acceptThread = Thread { awaitConnection() }.apply { start() }

    var clientSocket: Socket? = null
    var outputStream: DataOutputStream? = null
    var listenerThread: Thread? = null
    var connected: Boolean = false


    fun onLocalConnectionAttempt(s: Socket) {
        Logger.debug("New incoming connection")

//        synchronized(this) {
//            if(connected) {
//                Logger.debug("Already connected, closing old one")
//                try {
//                    clientSocket?.close()
//                } catch (e: IOException) {
//                    Logger.error("Error while closing old local socket, proceeding anyways", e)
//                }
//                listenerThread?.interrupt()
//                connected = false
//            }
//        }

        if(connected && clientSocket != null) {
            Logger.debug("Already connected, closing old one")
            onDisconnect(clientSocket!!, true)
        }

        clientSocket = s
        outputStream = DataOutputStream(s.getOutputStream())

        connected = true
        connectCallback()

        listenerThread = Thread(this::listener).apply { start() }
    }

    fun listener() {
        val mySocket = clientSocket ?: run {
            Logger.debug("Could not start listener due to socket being null")
            return@listener
        }
        val input = DataInputStream(mySocket.getInputStream())
        val output = DataOutputStream(mySocket.getOutputStream())

        val buffer = ByteArray(16777216) // 16 MB, enough?

        while(connected && clientSocket == mySocket && !mySocket.isClosed) {
            try {
                val bytesRead = input.read(buffer, 0, buffer.size)
                if(bytesRead == buffer.size) {
                    Logger.debug("Full buffer size used, buffer too small?")
                }
                if(bytesRead == -1) {
                    Logger.debug("Read -1 bytes, disconnecting")
                    break
                }

                val copiedBuffer = ByteArray(bytesRead)
                System.arraycopy(buffer, 0, copiedBuffer, 0, bytesRead)

                dataReceivedCallback(copiedBuffer)
            } catch(e: IOException) {
                Logger.debug("Error while reading from local socket, closing...", e)
                break
            }
        }

        try {
            input.close()
            output.close()
        } catch(e: IOException) { Logger.debug("", e) }

        onDisconnect(mySocket, true)
    }

    fun sendData(data: ByteArray) {
        try {
            outputStream?.run {
                write(data, 0, data.size)
                flush()
            }
        } catch(e: IOException) {
            Logger.debug("Error while writing to local socket, closing...", e)
            clientSocket?.let { onDisconnect(it, true) }
        }
    }

    // called from listener on error to force disconnect locally and remote
    fun onDisconnect(mySocket: Socket, notifyClient: Boolean) {
        Logger.debug("Disconnecting from local client")
        if(! mySocket.isClosed) {
            Logger.debug("Socket still open, closing")
            try {
                mySocket.close()
            } catch(e: IOException) { Logger.debug("", e) }
        }
        synchronized(this) {
            if(connected) {
                connected = false
                outputStream?.close()
                clientSocket = null
            }
        }

        if(notifyClient) {
            disconnectRequestedCallback()
        }
    }


    fun awaitConnection() {
        while(true) {
            val s = serverSocket.accept()
            onLocalConnectionAttempt(s)
        }
    }
}
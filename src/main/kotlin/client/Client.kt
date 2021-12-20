package client

import Logger
import common.Packet
import common.Packet.Companion.CREATE_PACKET
import common.Packet.Companion.DATA_PACKET
import common.Packet.Companion.QUIT_PACKET
import server.ClientSocketWrapper
import server.TUNNEL_SERVER_PORT

const val TUNNEL_SERVER_ADDRESS = "localhost"

class Client() {

    val localWrapperSocket = ServerSocketWrapper(25565, this::onLocalConnect, this::onLocalDataReceived, this::onLocalDisconnectRequested)


    var fromServerReceivedPacketsCount = 0
    fun onLocalConnect() {
        tunnelConnection?.disconnect()
        tunnelConnection = null

        tunnelConnection = TunnelConnection(dataCallback = {
            fromServerReceivedPacketsCount++
            localWrapperSocket.sendData(it)
//            println("Sending to MC $fromServerReceivedPacketsCount: ${it.size}")
        })
    }

    fun onLocalDataReceived(data: ByteArray) {
        tunnelConnection!!.queueData(data)
    }

    fun onLocalDisconnectRequested() {
        tunnelConnection?.disconnect()
        tunnelConnection = null
    }

    var tunnelConnection: TunnelConnection? = null


    // todo: authorization
    // todo: control protocol
    class TunnelConnection(val dataCallback: (ByteArray) -> Unit) {

        var tunnelSocket: ClientSocketWrapper? = null
        val dataQueue = ArrayList<ByteArray>()

        @Volatile
        var running = true

        init {
            connect()
            synchronized(dataQueue) {
                Logger.info("Asking server to connect to MC server")
                tunnelSocket?.sendData(Packet(CREATE_PACKET).toByteArray())
            }
        }

        fun connect() {
            bufferedTunnelData = ByteArray(0)

            tunnelSocket = ClientSocketWrapper(TUNNEL_SERVER_ADDRESS, TUNNEL_SERVER_PORT,
                connectCallback = this::onTunnelConnect,
                dataReceivedCallback = this::onTunnelData,
                disconnectExperiencedCallback = this::onTunnelDisconnect
            )
        }

        fun queueData(data: ByteArray) {
            synchronized(dataQueue) {
                dataQueue.add(data)
            }
        }

        val queueThread = Thread() {
            while(running) {
                synchronized(dataQueue) {
                    while(dataQueue.isNotEmpty()) {
                        if(tunnelSocket?.connected == true) {
                            val rawData = dataQueue.removeAt(0)
                            val packet = Packet(DATA_PACKET, rawData)

                            sendTunnel(packet)
                        } else {
                            break
                        }
                    }
                }

                try { Thread.sleep(2) } catch(e: InterruptedException) { e.printStackTrace() }
            }
        }.apply { start() }

        fun onTunnelConnect() {
            Logger.info("Tunnel connected to remote")
        }

        // control protocol
        var nextExpectedSeq = 0L
        var receivedPackets = HashMap<Long, Packet>()

        var bufferedTunnelData = ByteArray(0)
        fun onTunnelData(data: ByteArray) {
            bufferedTunnelData += data
//            println("Tunnel received data, total length: ${bufferedTunnelData.size}")

            while(Packet.containsPacket(bufferedTunnelData)) {
                val packet = Packet()
                val consumedBytes = packet.fromByteArray(bufferedTunnelData)

                bufferedTunnelData = bufferedTunnelData.copyOfRange(consumedBytes, bufferedTunnelData.size)

                // TODO: check reliability, probably blocked by sequence numbers

                // control protocol
                if(packet.type == Packet.ACK_PACKET) {
                    processAckPacketCP(packet)
                } else {
                    processIncomingPacketCP(packet)
                }
            }
        }

        private fun processAckPacketCP(packet: Packet) {
            // process incoming ack
            if(lastAckSeq == packet.ack) {
                //retransmit this packet
                Logger.warn("Retransmitting packet ${packet.ack}")
                sendTunnel(sentPackets[packet.ack]!!)
            }
            lastAckSeq = packet.ack
        }

        private fun processIncomingPacketCP(packet: Packet) {
            receivedPackets[packet.sequence] = packet

            if (Math.random() < 0.05) {
                sentPackets.filter { it.key <= lastAckSeq }.map { it.key }.forEach(sentPackets::remove)
            }

            while (nextExpectedSeq in receivedPackets.keys) {
                nextExpectedSeq++
                onTunnelPacket(receivedPackets[nextExpectedSeq]!!)
            }

            if(nextExpectedSeq != 0L) {
                sendTunnel(Packet(Packet.ACK_PACKET, ack = nextExpectedSeq - 1))
            }
        }

        private fun onTunnelPacket(packet: Packet) {
//            println("tunnel received packet ${packet.length}")
            when (packet.type) {
                DATA_PACKET -> {
                    dataCallback(packet.payload)
                }
                else -> {
                    Logger.warn("Received packet with unknown type ${packet.type}")
                }
            }
        }

        var lastAckSeq = -1L
        val sentPackets = HashMap<Long, Packet>()
        fun sendTunnel(packet: Packet) {
            tunnelSocket?.sendData(packet.toByteArray())

            if(packet.type == DATA_PACKET) {
                sentPackets[packet.sequence] = packet
                if(Math.random() < 0.05) {
                    sentPackets.filter { it.key <= lastAckSeq }.map { it.key }.forEach(sentPackets::remove)
                }
            }
        }

        fun onTunnelDisconnect() {
            Logger.warn("Tunnel broke down, reconnecting..")
            // we lost connection, reconnect
            connect()
        }

        fun disconnect() {
            Logger.info("Safely disconnecting from tunnel server and closing the minecraft connection")
            running = false
            tunnelSocket?.sendData(Packet(QUIT_PACKET).toByteArray())
            tunnelSocket?.disconnect()
        }
    }
}

fun main(args: Array<String>) {
    Logger.logLevel = Logger.DEBUG

    val client = Client()
}




// Direct server test connection
//var socket: Socket? = null
//var thread = Thread {
//    val buffer = ByteArray(16777216) // 16 MB, enough?
//
//    while(true) {
//        try {
//            val bytesRead = inputStream!!.read(buffer, 0, buffer.size)
//            if(bytesRead == buffer.size) {
//                Logger.warn("Full buffer size used, buffer too small?")
//            }
//
//            println("Data received from server, len: ${bytesRead}")
//
//            val copiedBuffer = ByteArray(bytesRead)
//            System.arraycopy(buffer, 0, copiedBuffer, 0, bytesRead)
//
//            localWrapperSocket.sendData(copiedBuffer)
//        } catch(e: IOException) { Logger.warn("Error while reading from local socket, closing...", e) }
//    }
//}
//var inputStream: DataInputStream? = null
//var outputStream: DataOutputStream? = null
//
//
//val localWrapperSocket = LocalWrapperSocket(25565, this::onLocalConnect, this::onLocalDataReceived, this::onLocalDisconnectRequested)
//
//fun onLocalConnect() {
//    socket = Socket("minecraft.jagenka.de", 25565)
//    inputStream = DataInputStream(socket!!.getInputStream())
//    outputStream = DataOutputStream(socket!!.getOutputStream())
//    thread.start()
//}
//
//fun onLocalDataReceived(data: ByteArray) {
//    println("Data received, len: ${data.size}")
//
//    outputStream!!.write(data)
//    outputStream!!.flush()
//}
//
//fun onLocalDisconnectRequested() {
//    Logger.info("Local disconnect requested, forwarding...")
//}
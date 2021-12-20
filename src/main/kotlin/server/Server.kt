package server

import Logger
import client.ServerSocketWrapper
import common.Packet
import common.Packet.Companion.ACK_PACKET
import common.Packet.Companion.CREATE_PACKET
import common.Packet.Companion.DATA_PACKET
import common.Packet.Companion.QUIT_PACKET

const val TUNNEL_SERVER_PORT = 26656
const val TARGET_SERVER_HOSTNAME = "minecraft.jagenka.de"
const val TARGET_SERVER_PORT = 25565

class Server {
    val tunnelServer = ServerSocketWrapper(TUNNEL_SERVER_PORT,
        connectCallback = this::onTunnelConnect,
        dataReceivedCallback = this::onTunnelData,
        disconnectRequestedCallback = this::onTunnelDisconnect
    )

    val queue = ArrayList<ByteArray>()
    fun queueData(data: ByteArray) {
        synchronized(queue) {
            queue.add(data)
        }
    }
    val queueThread = Thread() {
        while(true) {
            synchronized(queue) {
                while(queue.isNotEmpty()) {
                    if(tunnelServer.connected) {
                        val rawData = queue.removeAt(0)
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
        bufferedTunnelData = ByteArray(0)
        Logger.info("Tunnel reconnected")
    }

    // control protocol
    var nextExpectedSeq = 0L
    var receivedPackets = HashMap<Long, Packet>()

    var bufferedTunnelData = ByteArray(0)
    fun onTunnelData(data: ByteArray) {
        bufferedTunnelData += data

        while(Packet.containsPacket(bufferedTunnelData)) {
            val packet = Packet()
            val consumedBytes = packet.fromByteArray(bufferedTunnelData)

            bufferedTunnelData = bufferedTunnelData.copyOfRange(consumedBytes, bufferedTunnelData.size)

            // control protocol
            if(packet.type == ACK_PACKET) {
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
            sendTunnel(Packet(ACK_PACKET, ack = nextExpectedSeq - 1))
        }
    }

    private fun onTunnelPacket(packet: Packet) {
//        println("tunnel received ${packet.length}")
        when (packet.type) {
            CREATE_PACKET -> {
                Logger.info("Connect to mc server requested by client")
                synchronized(queue) {
                    queue.clear()
                }
                wrapperConnection = WrapperConnection(
                    dataCallback = { queueData(it) },
                    disconnectExperiencedCallback = { Logger.crash(RuntimeException("The server kicked us...")) },
                )
            }
            QUIT_PACKET -> {
                Logger.info("Disconnect request by client")
                wrapperConnection?.disconnect()
                wrapperConnection = null
            }
            DATA_PACKET -> {
                wrapperConnection!!.sendData(packet.payload)
            }
            else -> {
                Logger.warn("Received packet with unknown type ${packet.type}")
            }
        }
    }

    var lastAckSeq = -1L
    val sentPackets = HashMap<Long, Packet>()
    fun sendTunnel(packet: Packet) {
        tunnelServer.sendData(packet.toByteArray())

        if(packet.type == DATA_PACKET) {
            sentPackets[packet.sequence] = packet
            if(Math.random() < 0.05) {
                sentPackets.filter { it.key <= lastAckSeq }.map { it.key }.forEach(sentPackets::remove)
            }
        }
    }

    fun onTunnelDisconnect() {
        Logger.info("Tunnel disconnected, waiting for reconnect")
    }

    var wrapperConnection: WrapperConnection? = null


    class WrapperConnection(val dataCallback: (ByteArray) -> Unit, val disconnectExperiencedCallback: () -> Unit) {
        var wrapperSocket: ClientSocketWrapper = ClientSocketWrapper(TARGET_SERVER_HOSTNAME, TARGET_SERVER_PORT,
            connectCallback = this::onWrapperConnect,
            dataReceivedCallback = this::onWrapperData,
            disconnectExperiencedCallback = this::onWrapperDisconnect
        )

        fun onWrapperConnect() {
            Logger.info("Connected to minecraft server")
        }

        var wrapperIncomingPacketsCount = 0
        fun onWrapperData(data: ByteArray) {
            wrapperIncomingPacketsCount++
//            println("Received from MC server: $wrapperIncomingPacketsCount: ${data.size}")
            dataCallback(data)
        }

        fun onWrapperDisconnect() {
            disconnectExperiencedCallback()
        }

        fun sendData(data: ByteArray) {
//            println("Sending to MC server: ${data.size}")
            wrapperSocket.sendData(data)
        }

        fun disconnect() {
            Logger.info("Disconnecting from minecraft server")
            wrapperSocket.disconnect()
        }
    }
}

fun main(args: Array<String>) {
    Logger.logLevel = Logger.DEBUG

    val server = Server()
}
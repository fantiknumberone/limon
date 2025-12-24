package com.example.limon

import kotlinx.coroutines.*
import org.zeromq.SocketType
import org.zeromq.ZMQ
import java.util.concurrent.atomic.AtomicBoolean

interface ConnectionListener {
    fun onConnected()
    fun onDisconnected()
}

class ZmqClient(private val url: String, private val listener: ConnectionListener? = null) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var context: ZMQ.Context? = null
    private var socket: ZMQ.Socket? = null
    private val connected = AtomicBoolean(false)
    private var connectionCheckJob: Job? = null
    private val SERVER_CHECK_TIMEOUT = 2000L

    fun connect() {
        if (connected.get()) return

        scope.launch {
            try {
                context = ZMQ.context(1)
                socket = context?.socket(SocketType.PUSH)
                socket?.setSendTimeOut(3000)
                socket?.setLinger(2000)
                socket?.connect(url)

                val serverAvailable = checkServerAvailability()

                if (serverAvailable) {
                    connected.set(true)
                    startConnectionCheck()
                    withContext(Dispatchers.Main) {
                        listener?.onConnected()
                    }
                } else {
                    connected.set(false)
                    closeInternal()
                    withContext(Dispatchers.Main) {
                        listener?.onDisconnected()
                    }
                }
            } catch (e: Exception) {
                connected.set(false)
                closeInternal()
                withContext(Dispatchers.Main) {
                    listener?.onDisconnected()
                }
            }
        }
    }

    private fun checkServerAvailability(): Boolean {
        return try {
            val testContext = ZMQ.context(1)
            val testSocket = testContext.socket(SocketType.REQ)
            testSocket.setReceiveTimeOut(500)
            testSocket.setSendTimeOut(500)
            testSocket.setLinger(0)
            testSocket.connect(url)

            val sent = testSocket.send("TEST", ZMQ.DONTWAIT)
            if (!sent) {
                testSocket.close()
                testContext.close()
                return false
            }

            val response = testSocket.recvStr(ZMQ.DONTWAIT)
            testSocket.close()
            testContext.close()
            sent
        } catch (e: Exception) {
            false
        }
    }

    private fun startConnectionCheck() {
        connectionCheckJob?.cancel()
        connectionCheckJob = scope.launch {
            while (isActive && connected.get()) {
                delay(5000)

                if (!checkConnection()) {
                    connected.set(false)

                    withContext(Dispatchers.Main) {
                        listener?.onDisconnected()
                    }

                    delay(2000)
                    if (isActive) {
                        connect()
                    }
                }
            }
        }
    }

    private fun checkConnection(): Boolean {
        return try {
            if (socket == null || context == null) {
                return false
            }
            val result = socket?.send(ByteArray(0), ZMQ.DONTWAIT) ?: false
            result
        } catch (e: Exception) {
            false
        }
    }

    fun send(json: String): Boolean {
        return try {
            val result = socket?.send(json, ZMQ.NOBLOCK) ?: false
            result
        } catch (e: Exception) {
            false
        }
    }

    fun isConnected(): Boolean = connected.get()

    fun close() {
        connected.set(false)
        connectionCheckJob?.cancel()
        scope.launch {
            closeInternal()
        }
    }

    private fun closeInternal() {
        try {
            socket?.close()
            context?.close()
            socket = null
            context = null
        } catch (e: Exception) {
        }
    }
}
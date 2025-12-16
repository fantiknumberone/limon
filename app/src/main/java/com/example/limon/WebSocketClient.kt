package com.example.limon

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

interface ConnectionListener {
    fun onConnected()
    fun onDisconnected()
}

class WebSocketClient(private val url: String, private val listener: ConnectionListener? = null) {

    private val TAG = "WebSocket"
    private var webSocket: WebSocket? = null
    private var connected = false

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.d(TAG, "✔ WebSocket connected")
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "⬇ Received: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "⬇ Received bytes: $bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                Log.d(TAG, "✖ Closing: $reason")
                listener?.onDisconnected()
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.e(TAG, "❌ Error: ${t.message}")
                listener?.onDisconnected()
            }
        })
    }

    fun send(json: String): Boolean {
        return try {
            webSocket?.send(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send: ${e.message}")
            false
        }
    }

    fun isConnected(): Boolean = connected

    fun close() {
        connected = false
        webSocket?.close(1000, "App closed")
    }
}